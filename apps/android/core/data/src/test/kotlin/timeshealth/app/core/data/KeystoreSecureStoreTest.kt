package timeshealth.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.rules.TemporaryFolder
import timeshealth.app.core.data.security.AesGcmValueCipher
import timeshealth.app.core.data.security.KeystoreSecureStore
import timeshealth.app.core.data.security.SecretKeyProvider

/**
 * The encrypted store on the JVM: real DataStore on a temp file and real AES-256-GCM, with a
 * software key standing in for the AndroidKeyStore one (which only exists on a device). Covers
 * what matters for users: values are unreadable on disk, and a lost key reads as "absent", never
 * a crash.
 */
class KeystoreSecureStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Okio storage rather than the File storage production uses: File storage renames its temp
    // file over the old one, which java.io can't do on a Windows dev machine (Android's
    // filesystem can). Okio replaces atomically on both. Same DataStore and file format otherwise.
    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                File(folder.root, "secure.preferences_pb").absolutePath.toPath()
            },
            scope = scope,
        )
    }

    /** A software AES-256 key; [reset] forgets it, as a Keystore wipe would. */
    private class SoftwareKey : SecretKeyProvider {
        private var key: SecretKey? = null
        override fun key(): SecretKey = key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
        override fun reset() {
            key = null
        }
    }

    private fun store(key: SecretKeyProvider) = KeystoreSecureStore(dataStore, AesGcmValueCipher(key), Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `values round-trip and are never stored in plain text`() = runBlocking<Unit> {
        val store = store(SoftwareKey())

        store.put("th_auth_token", "qa_yoga|yoga@th.test|+919000000002")

        assertThat(store.get("th_auth_token")).isEqualTo("qa_yoga|yoga@th.test|+919000000002")
        val onDisk = dataStore.data.first()[stringPreferencesKey("th_auth_token")]!!
        assertThat(onDisk).doesNotContain("qa_yoga")
        assertThat(File(folder.root, "secure.preferences_pb").readText(Charsets.ISO_8859_1)).doesNotContain("yoga@th.test")
    }

    @Test
    fun `the same value encrypts differently every time`() = runBlocking<Unit> {
        val store = store(SoftwareKey())

        store.put("a", "same")
        val first = dataStore.data.first()[stringPreferencesKey("a")]
        store.put("a", "same")
        val second = dataStore.data.first()[stringPreferencesKey("a")]

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `a lost key (reinstall, Keystore reset) reads as absent, and new writes work`() = runBlocking<Unit> {
        val key = SoftwareKey()
        val store = store(key)
        store.put("th_pass_delhi_half", "{\"eventId\":\"delhi_half\"}")

        key.reset()

        assertThat(store.get("th_pass_delhi_half")).isNull()
        store.put("th_pass_delhi_half", "fresh")
        assertThat(store.get("th_pass_delhi_half")).isEqualTo("fresh")
    }

    @Test
    fun `a value moved to another key, altered or garbage reads as absent`() = runBlocking<Unit> {
        val store = store(SoftwareKey())
        store.put("th_auth_token", "secret")
        val sealed = dataStore.data.first()[stringPreferencesKey("th_auth_token")]!!
        val tampered = Base64.getDecoder().decode(sealed).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        dataStore.edit {
            it[stringPreferencesKey("th_pass_x")] = sealed
            it[stringPreferencesKey("tampered")] = Base64.getEncoder().encodeToString(tampered)
            it[stringPreferencesKey("garbage")] = "not even base64 %%%"
            it[stringPreferencesKey("short")] = "AQ=="
        }

        assertThat(store.get("th_pass_x")).isNull()
        assertThat(store.get("tampered")).isNull()
        assertThat(store.get("garbage")).isNull()
        assertThat(store.get("short")).isNull()
        assertThat(store.get("th_auth_token")).isEqualTo("secret")
    }

    @Test
    fun `remove deletes several keys at once, and absent keys are fine`() = runBlocking<Unit> {
        val store = store(SoftwareKey())
        store.put("a", "1")
        store.put("b", "2")
        store.put("c", "3")

        store.remove("a", "b", "never-written")

        assertThat(store.get("a")).isNull()
        assertThat(store.get("b")).isNull()
        assertThat(store.get("c")).isEqualTo("3")
    }

    @Test
    fun `unicode survives`() = runBlocking<Unit> {
        val store = store(SoftwareKey())

        store.put("name", "आशा राव 🏃")

        assertThat(store.get("name")).isEqualTo("आशा राव 🏃")
    }
}
