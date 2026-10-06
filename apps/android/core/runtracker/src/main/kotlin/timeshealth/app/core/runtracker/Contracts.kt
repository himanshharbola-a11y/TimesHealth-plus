package timeshealth.app.core.runtracker

/*
 * What the app module supplies. This module knows nothing about the network
 * client or the session store; the app binds these with Hilt (@Binds into
 * SingletonComponent).
 */

/**
 * Uploads one finished run (POST /runs) as the CURRENTLY signed-in user.
 *
 * The app maps [FinishedRun] to `UploadRunRequest` (ISO `startedAt`/`endedAt`,
 * `routePolyline` sent as null when absent). Must THROW on any failure,
 * including a non-2xx response, so the run stays unsynced and is retried.
 * Uploads are idempotent on [FinishedRun.id] server-side, so a retry after a
 * lost response is safe.
 */
interface RunUploader {
    suspend fun upload(run: FinishedRun)
}

/**
 * Thrown by a [RunUploader] when the server has refused a run for good (a 4xx
 * validation answer, not a network blip). Retrying can never succeed, and
 * since uploads go one at a time, a single such run would otherwise block
 * every later run from ever syncing. The syncer gives up on that run only.
 */
class PermanentUploadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Who is signed in, for owner scoping on shared phones: one person's runs
 * must never show for, or upload as, the next person who signs in (TS schema
 * v3). Runs are filed under the profile id (`session.profile.id`).
 */
interface RunOwnerProvider {
    /** The signed-in profile id, or null when signed out or not yet known. */
    suspend fun currentOwner(): String?
}

/**
 * Optional. The profile's units setting (#7), so the tracking notification
 * shows miles to someone who runs in miles. Without a binding it shows km.
 */
fun interface RunDisplayPreferences {
    suspend fun imperial(): Boolean
}
