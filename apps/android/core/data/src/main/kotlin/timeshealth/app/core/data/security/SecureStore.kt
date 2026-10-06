package timeshealth.app.core.data.security

/**
 * A small encrypted key-value store for what must not sit on the phone in plain text: the QA
 * persona token, the offline race passes (each carries a 7-day signed token), the registered
 * push token and the inbox marker. The Android port of the RN app's `expo-secure-store`.
 *
 * The production implementation is [KeystoreSecureStore]; tests use an in-memory fake.
 *
 * Contract, carried over from RN, where every caller treated a failing store as "nothing saved"
 * rather than a crash:
 * - [get] never throws for an unreadable value. After a reinstall, a restored backup or a
 *   Keystore reset the encryption key is gone and old values can no longer be decrypted; they
 *   read as absent (signed out, no pass), never as a crash on launch.
 * - [put] and [remove] throw [java.io.IOException] (or a security exception) when the write fails.
 *   Callers decide whether that matters; most swallow it, as RN did.
 */
interface SecureStore {

    /** The value stored under [key], or null when absent or no longer readable. */
    suspend fun get(key: String): String?

    /** Stores [value] under [key], replacing any previous value. */
    suspend fun put(key: String, value: String)

    /** Removes every one of [keys] in a single write. Absent keys are ignored. */
    suspend fun remove(vararg keys: String)
}
