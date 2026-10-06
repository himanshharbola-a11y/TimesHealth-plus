package timeshealth.app.core.data.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timeshealth.app.core.data.quietly
import timeshealth.app.core.data.security.SecureStore

/**
 * The stored QA persona token, with an in-memory copy. Port of `getPersonaToken` / `setToken` in
 * apps/mobile/src/api/client.ts.
 *
 * Only the persona token is stored by us. The identity provider's ID token never is: the provider
 * keeps its own session and refreshes the token before it expires.
 *
 * Read on every authenticated request, so after the first read it is answered from memory.
 */
@Singleton
class PersonaTokenStore @Inject constructor(private val store: SecureStore) {

    private class Loaded(val token: String?)

    @Volatile
    private var cached: Loaded? = null

    /** Serializes the first read with writes, so a slow read can't overwrite a newer token. */
    private val mutex = Mutex()

    /** The persona token, or null. A store that can't be read counts as "no persona", never a crash. */
    suspend fun get(): String? {
        cached?.let { return it.token }
        return mutex.withLock {
            cached?.let { return@withLock it.token }
            // The store can fail (e.g. Keystore trouble). Treat as signed out rather than
            // crashing the app on launch.
            val token = quietly { store.get(PERSONA_KEY) }
            cached = Loaded(token)
            token
        }
    }

    /** Stores [token], or clears it with null. */
    suspend fun set(token: String?) {
        mutex.withLock {
            cached = Loaded(token)
            // Non-fatal: the in-memory copy keeps the session (or the sign-out) for this launch.
            quietly {
                if (token == null) store.remove(PERSONA_KEY) else store.put(PERSONA_KEY, token)
            }
        }
    }

    internal companion object {
        const val PERSONA_KEY = "th_auth_token"
    }
}
