package timeshealth.app.core.data.security

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Supplies the AES key. Production: [AndroidKeystoreKeyProvider]. Tests: a software key. */
internal interface SecretKeyProvider {
    /** The key, created on first use. */
    fun key(): SecretKey

    /** Forgets a key that can no longer be used, so the next [key] creates a fresh one. */
    fun reset()
}

/**
 * AES-256-GCM sealing for [SecureStore] values.
 *
 * Format (Base64, no line breaks): `version(1) | ivLength(1) | iv | ciphertext+tag(128-bit)`.
 *
 * - The IV comes from the cipher itself. AndroidKeyStore keys are created with randomized
 *   encryption required, so the Keystore refuses a caller-chosen IV; a fresh random one per
 *   value is what makes reusing the key with GCM safe.
 * - The entry's name is authenticated as associated data. A value copied onto another key (the
 *   persona token pasted into a pass slot, say) fails authentication instead of being read as
 *   something it isn't.
 * - The version byte leaves room to change the scheme without guessing at old data.
 */
internal class AesGcmValueCipher(private val keys: SecretKeyProvider) {

    /** Encrypts [plaintext] for the entry [name]. Throws [GeneralSecurityException] on failure. */
    fun seal(name: String, plaintext: String): String {
        val cipher = try {
            encryptor(keys.key())
        } catch (e: InvalidKeyException) {
            // The stored key exists but can't be used any more (seen after OS upgrades and
            // Keystore resets on some devices). Values sealed with it are already unreadable,
            // so start over with a new key rather than failing every write from now on.
            keys.reset()
            encryptor(keys.key())
        }
        cipher.updateAAD(aad(name))
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val out = ByteBuffer.allocate(2 + iv.size + sealed.size)
            .put(VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(sealed)
        return Base64.getEncoder().encodeToString(out.array())
    }

    /**
     * Decrypts a value sealed by [seal] for the same [name].
     *
     * @throws GeneralSecurityException when the key is gone or different (reinstall, Keystore
     *   reset), or the value was altered or moved to another name.
     * @throws IllegalArgumentException when [sealed] isn't in this format at all.
     */
    fun open(name: String, sealed: String): String {
        val bytes = Base64.getDecoder().decode(sealed)
        require(bytes.size > 2 && bytes[0] == VERSION) { "Unknown sealed-value format" }
        val ivLength = bytes[1].toInt()
        require(ivLength in 12..16 && bytes.size > 2 + ivLength) { "Malformed sealed value" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, bytes, 2, ivLength))
        cipher.updateAAD(aad(name))
        val plain = cipher.doFinal(bytes, 2 + ivLength, bytes.size - 2 - ivLength)
        return String(plain, Charsets.UTF_8)
    }

    private fun encryptor(key: SecretKey): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }

    private fun aad(name: String): ByteArray = "th-secure-store:$name".toByteArray(Charsets.UTF_8)

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val VERSION: Byte = 1
    }
}
