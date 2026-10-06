package timeshealth.app.core.data.push

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import timeshealth.app.core.data.quietly
import timeshealth.app.core.data.security.SecureStore
import timeshealth.app.core.model.DevicePlatform
import timeshealth.app.core.model.PushProvider
import timeshealth.app.core.model.RegisterPushTokenRequest
import timeshealth.app.core.model.RemovePushTokenRequest
import timeshealth.app.core.network.TimesHealthApi

/**
 * This device's push registration with the API (PRD §11). Port of the parts of
 * apps/mobile/src/lib/notifications.ts that aren't Firebase-specific: the app module owns the
 * FCM SDK, the permission prompt and the notification channel, and hands tokens to this class.
 *
 * Push is an enhancement in V1 (WhatsApp continues; both fire), never the critical path, and
 * declining it must not break anything. So nothing here throws: failures come back as `false` or
 * are swallowed.
 *
 * The app's part:
 * - after its own priming screen says yes and the OS grants permission, and on every launch while
 *   permission is granted: fetch the FCM token and call [registerToken]. The API upserts on the
 *   token, so if a different person has signed in on this phone the device moves to their
 *   account; otherwise they would receive the previous user's race-day alerts.
 * - from `FirebaseMessagingService.onNewToken(token)`: call [onTokenRotated] with THAT token.
 */
@Singleton
class PushRegistration @Inject constructor(
    private val api: TimesHealthApi,
    private val store: SecureStore,
) {
    private val lock = Any()

    /**
     * The token already registered for the CURRENT signed-in user, and one being registered right
     * now. Together they make registration a no-op when nothing has changed: the second line of
     * defence against the loop described at [onTokenRotated]. Reset on every identity change.
     */
    private var registeredToken: String? = null
    private var pendingToken: String? = null

    /** Bumped by [reset]: a registration that was in flight then belongs to the previous user. */
    private var epoch = 0L

    /**
     * Registers [token] (FCM) for the signed-in user. A no-op returning true when that token is
     * already registered, or being registered, for this user. Returns false when it couldn't be
     * registered (blank token, offline, server error); the next launch tries again.
     *
     * Remembers the token on the device so [unregisterDevice] can remove it at sign-out, even
     * after a restart.
     */
    suspend fun registerToken(token: String): Boolean {
        if (token.isBlank()) return false
        val startedIn = synchronized(lock) {
            if (token == registeredToken || token == pendingToken) return true
            pendingToken = token
            epoch
        }
        try {
            api.registerPushToken(RegisterPushTokenRequest(token, DevicePlatform.ANDROID, PushProvider.FCM))
            synchronized(lock) {
                // Signed out (or switched user) while this was in flight: it registered the
                // previous user, so it must not stop the next one from registering.
                if (epoch == startedIn) registeredToken = token
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        } finally {
            synchronized(lock) { if (epoch == startedIn && pendingToken == token) pendingToken = null }
        }
        // Only needed for unregistering on sign-out.
        quietly { store.put(TOKEN_KEY, token) }
        return true
    }

    /**
     * FCM rotated this device's token: keep the server's copy current.
     *
     * Registers exactly the token it is HANDED and never fetches one itself. In the RN app,
     * "on change -> fetch the token -> register" re-triggered its own listener (fetching fires the
     * change event): ~44 requests a second on a test device until the API's rate limiter cut the
     * user off, breaking every other screen with it.
     */
    suspend fun onTokenRotated(token: String) {
        registerToken(token)
    }

    /**
     * Sign-out: stops this phone receiving the leaving user's alerts. Must run while the user is
     * still authenticated. Best effort; the server also prunes tokens FCM reports as dead.
     */
    suspend fun unregisterDevice() {
        quietly {
            val token = store.get(TOKEN_KEY) ?: synchronized(lock) { registeredToken } ?: return
            api.removePushToken(RemovePushTokenRequest(token))
            store.remove(TOKEN_KEY)
            reset()
        }
    }

    /**
     * The account was deleted: the server already erased its device registrations, so there is
     * nothing to remove remotely (and no account left to authenticate the call). Forget the
     * remembered token so a later sign-out doesn't try.
     */
    suspend fun forgetDevice() {
        reset()
        quietly { store.remove(TOKEN_KEY) }
    }

    /**
     * Called whenever the signed-in identity changes: the next user must register this device
     * under their own account.
     */
    fun reset() {
        synchronized(lock) {
            registeredToken = null
            pendingToken = null
            epoch++
        }
    }

    /** True once the app has shown its own priming screen, whatever the answer. Per device. */
    suspend fun hasPrimed(): Boolean = quietly { store.get(PRIMED_KEY) } == "1"

    /**
     * Records that the priming screen was shown. Permission is never requested cold: on Android
     * 13+ a denial at the OS prompt is close to permanent, so the one real ask has to be the one
     * the user already agreed to. Worst case on failure: the primer shows once more.
     */
    suspend fun markPrimed() {
        quietly { store.put(PRIMED_KEY, "1") }
    }

    internal companion object {
        const val TOKEN_KEY = "th_push_token_v1"
        const val PRIMED_KEY = "th_push_primed_v1"
    }
}
