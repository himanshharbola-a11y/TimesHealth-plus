package timeshealth.app.core.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * [SecureStore] backed by DataStore Preferences, every value sealed with AES-256-GCM under a key
 * that lives in AndroidKeyStore and never leaves it.
 *
 * Why not androidx.security-crypto (EncryptedSharedPreferences): it is deprecated, and it fails
 * hard (crashes on read) when its master key is lost, which is exactly the case below.
 *
 * Key loss is expected, not exceptional. Keystore keys don't survive a reinstall and are wiped by
 * some OS upgrades and "reset" flows, while the DataStore file may not be. A value that can't be
 * decrypted reads as absent: the user is signed out, the pass is re-saved the next time the race
 * page loads, and nothing crashes on launch. The unreadable value is left in place (and replaced
 * by the next write) rather than deleted, so a transient Keystore failure on race morning can
 * never destroy a pass that would have opened a second later.
 *
 * Keystore operations are IPC into a system service and may block, so they run on [crypto].
 */
class KeystoreSecureStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: AesGcmValueCipher,
    private val crypto: CoroutineDispatcher = Dispatchers.IO,
) : SecureStore {

    override suspend fun get(key: String): String? {
        val sealed = try {
            dataStore.data.first()[stringPreferencesKey(key)]
        } catch (e: IOException) {
            null
        } ?: return null
        return try {
            withContext(crypto) { cipher.open(key, sealed) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // GeneralSecurityException (key gone or different, value altered), a malformed value,
            // or one of the RuntimeExceptions some Keystore builds throw. All mean "not readable".
            null
        }
    }

    override suspend fun put(key: String, value: String) {
        val sealed = try {
            withContext(crypto) { cipher.seal(key, value) }
        } catch (e: GeneralSecurityException) {
            throw IOException("Couldn't encrypt the value for $key", e)
        } catch (e: RuntimeException) {
            // e.g. android.security.keystore ProviderException / KeyStoreException wrappers.
            throw IOException("Couldn't encrypt the value for $key", e)
        }
        dataStore.edit { it[stringPreferencesKey(key)] = sealed }
    }

    override suspend fun remove(vararg keys: String) {
        if (keys.isEmpty()) return
        dataStore.edit { prefs -> keys.forEach { prefs.remove(stringPreferencesKey(it)) } }
    }

    companion object {
        /** DataStore allows one instance per file per process, so call this once (a singleton). */
        fun create(context: Context): KeystoreSecureStore = KeystoreSecureStore(
            dataStore = PreferenceDataStoreFactory.create(
                // A corrupt file reads as empty (signed out, no passes) instead of failing every
                // read until the app is reinstalled.
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
            ),
            cipher = AesGcmValueCipher(AndroidKeystoreKeyProvider(KEY_ALIAS)),
        )

        private const val FILE_NAME = "th_secure_store"
        private const val KEY_ALIAS = "th_secure_store_aes_v1"
    }
}

/**
 * The AES-256 key in AndroidKeyStore. It can encrypt and decrypt but is never exported.
 *
 * Deliberately NOT bound to user authentication or an unlocked device: run uploads and token
 * reads happen in the background with the screen locked, and a phone without a screen lock must
 * still work (the RN app learned that one from expo-secure-store).
 */
internal class AndroidKeystoreKeyProvider(private val alias: String) : SecretKeyProvider {

    private val lock = Any()

    @Volatile
    private var cached: SecretKey? = null

    override fun key(): SecretKey {
        cached?.let { return it }
        // Synchronized so two first reads can't each generate a key, the second silently
        // replacing the first and orphaning whatever it had just encrypted.
        synchronized(lock) {
            cached?.let { return it }
            val keyStore = keyStore()
            val existing = try {
                keyStore.getKey(alias, null) as? SecretKey
            } catch (e: UnrecoverableKeyException) {
                keyStore.deleteEntry(alias)
                null
            }
            return (existing ?: generate()).also { cached = it }
        }
    }

    override fun reset() {
        synchronized(lock) {
            cached = null
            try {
                keyStore().deleteEntry(alias)
            } catch (e: GeneralSecurityException) {
                // Nothing usable to delete; the next key() generates over it.
            }
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun generate(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
