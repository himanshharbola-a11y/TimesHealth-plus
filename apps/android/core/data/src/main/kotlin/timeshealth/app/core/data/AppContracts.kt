package timeshealth.app.core.data

import android.util.Log
import kotlinx.coroutines.flow.Flow

/*
 * What the app module supplies. This module knows nothing about BuildConfig, Firebase or the run
 * tracker; the app binds these with Hilt (@Binds / @Provides into SingletonComponent).
 */

/** Build-time facts the HTTP stack needs. The app binds one from its BuildConfig. */
interface AppConfig {
    /** e.g. `BuildConfig.API_BASE_URL` ("https://host/v1"); a trailing slash is optional. */
    val apiBaseUrl: String

    /**
     * `BuildConfig.DEBUG`. Turns on the redacted request log and eager API validation (see
     * NetworkFactory). Never true in a release: even redacted, logs end up in bug reports.
     */
    val debug: Boolean

    /** Where debug request-log lines go. Only called when [debug] is true. */
    fun log(line: String) {
        Log.d("TimesHealthApi", line)
    }
}

/**
 * THE identity seam, as `apps/mobile/src/lib/identity.ts`: Firebase Auth today, Times SSO later.
 * Everything in this module talks "identity", never "Firebase", so the swap means a new
 * implementation in the app module (and the server's auth provider) and nothing else.
 *
 * Push messaging is deliberately NOT behind this seam: FCM survives an auth-provider swap.
 */
interface IdentityGateway {

    /** The signed-in user's stable id, or null when nobody is signed in. */
    fun currentUserId(): String?

    /**
     * A bearer token for the API, or null when signed out. The provider caches it and refreshes
     * it before expiry, so calling this per request is cheap; [forceRefresh] skips the cache
     * (e.g. after the server changed the user's claims).
     *
     * May throw; callers treat a failure as "no token", never as a crash.
     */
    suspend fun idToken(forceRefresh: Boolean): String?

    /**
     * Signs out of the provider (and of Google Sign-In, if used). Should not throw: signing out
     * must always succeed from the user's point of view. Failures are swallowed by the caller
     * anyway.
     */
    suspend fun signOut()

    /**
     * The signed-in uid: the CURRENT value as soon as it is collected (once the provider has
     * restored any saved login), then every change. Null means signed out. Firebase:
     * `callbackFlow` over `AuthStateListener`.
     */
    val authState: Flow<String?>
}

/**
 * Stops GPS tracking for the user who is leaving. The app binds it to the run tracker's
 * `RunTracker.suspendForSignOut()`.
 *
 * Required, not optional, on purpose: a missing binding fails the build instead of letting the
 * phone keep recording someone's location after they have signed out. Their run stays parked on
 * the phone under their name and syncs when they come back.
 */
fun interface TrackingSuspender {
    suspend fun suspendTrackingForSignOut()
}
