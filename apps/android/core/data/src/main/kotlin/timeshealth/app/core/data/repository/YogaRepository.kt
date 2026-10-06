package timeshealth.app.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timeshealth.app.core.data.cache.CacheKeys
import timeshealth.app.core.data.cache.CachedResource
import timeshealth.app.core.data.cache.ResponseCache
import timeshealth.app.core.model.CompletedResponse
import timeshealth.app.core.model.JoinSessionRequest
import timeshealth.app.core.model.JoinSessionResponse
import timeshealth.app.core.model.LiveClassJoinResponse
import timeshealth.app.core.model.LiveClassListResponse
import timeshealth.app.core.model.MySessionsResponse
import timeshealth.app.core.model.OkResponse
import timeshealth.app.core.model.PlaybackResponse
import timeshealth.app.core.model.SavedResponse
import timeshealth.app.core.model.SetCompletedRequest
import timeshealth.app.core.model.SetReminderSlotRequest
import timeshealth.app.core.model.SetSavedRequest
import timeshealth.app.core.model.YogaAttendance
import timeshealth.app.core.model.YogaCatalogResponse
import timeshealth.app.core.model.YogaTodayResponse
import timeshealth.app.core.network.TimesHealthApi

/** Live batches, the session library, attendance and the user's saved/completed sessions (PRD §7). */
@Singleton
class YogaRepository @Inject constructor(
    private val api: TimesHealthApi,
    private val cache: ResponseCache,
) {

    /**
     * GET /yoga/today. Which batch is live or next moves with the clock: refetch about once a
     * minute, and only while the screen is showing.
     */
    val today: CachedResource<YogaTodayResponse> =
        CachedResource(cache, CacheKeys.YogaToday, 30.seconds) { api.yogaToday() }

    val catalog: CachedResource<YogaCatalogResponse> =
        CachedResource(cache, CacheKeys.YogaCatalog, 5.minutes) { api.yogaCatalog() }

    /** GET /yoga/live: scheduled live classes (premieres). Their state moves with the clock. */
    val liveClasses: CachedResource<LiveClassListResponse> =
        CachedResource(cache, CacheKeys.YogaLive, 30.seconds) { api.liveClasses() }

    /** Joins a live class: the stream to play. A member's join wrote an attendance mark. */
    suspend fun joinLiveClass(liveClassId: String): LiveClassJoinResponse =
        api.joinLiveClass(liveClassId).also {
            if (it.attendanceRecorded) cache.invalidate(CacheKeys.YogaAttendance, CacheKeys.Home)
        }

    /** GET /yoga/attendance. 403 NOT_ENTITLED for non-subscribers: only fetch it for subscribers. */
    val attendance: CachedResource<YogaAttendance> =
        CachedResource(cache, CacheKeys.YogaAttendance, 60.seconds) { api.yogaAttendance() }

    /**
     * Saved and completed session ids. Always refetched on [get][CachedResource.get]; its
     * [data][CachedResource.data] reflects a save/complete tap at once.
     */
    val mySessions: CachedResource<MySessionsResponse> =
        CachedResource(cache, CacheKeys.YogaMine, Duration.ZERO) { api.mySessions() }

    /** One save/complete request at a time (RN's mutation scope "yoga-my-sessions"). */
    private val sessionFlagWrites = Mutex()

    /** A FRESH signed playback URL for the player. Never cached: catalogue URLs expire within minutes. */
    suspend fun playback(sessionId: String): PlaybackResponse = api.playback(sessionId)

    /** Joins a live class. The server wrote an attendance mark, so the streak moved. */
    suspend fun join(batchId: String): JoinSessionResponse =
        api.joinSession(JoinSessionRequest(batchId)).also {
            cache.invalidate(CacheKeys.YogaAttendance, CacheKeys.Home)
        }

    suspend fun setReminderSlot(batchId: String): OkResponse =
        api.setReminderSlot(SetReminderSlotRequest(batchId)).also {
            cache.invalidate(CacheKeys.YogaToday, CacheKeys.Session)
        }

    /** Saves or unsaves [sessionId]: the state the user WANTS, never a toggle. */
    suspend fun setSaved(sessionId: String, saved: Boolean): SavedResponse =
        setSessionFlag(sessionId, saved, SessionFlag.SAVED) { api.setSaved(sessionId, SetSavedRequest(saved)) }

    /** Marks a recording completed or not: the WANTED state. Does not count as attendance. */
    suspend fun setCompleted(sessionId: String, completed: Boolean): CompletedResponse =
        setSessionFlag(sessionId, completed, SessionFlag.COMPLETED) {
            api.setCompleted(sessionId, SetCompletedRequest(completed))
        }

    private enum class SessionFlag { SAVED, COMPLETED }

    /**
     * Save / complete send the state the user wants (not "toggle"), show it at once (optimistic),
     * and run one at a time, so a double tap ends where the user's last tap left it, never
     * flipped back by a request arriving late.
     */
    private suspend fun <R> setSessionFlag(id: String, on: Boolean, flag: SessionFlag, call: suspend () -> R): R {
        cache.update<MySessionsResponse>(CacheKeys.YogaMine) { it.with(flag, id, on) }
        return sessionFlagWrites.withLock {
            try {
                call()
            } finally {
                // Success or failure, the server's copy is the truth.
                cache.invalidate(CacheKeys.YogaMine)
            }
        }
    }

    private fun MySessionsResponse.with(flag: SessionFlag, id: String, on: Boolean): MySessionsResponse {
        fun List<String>.withId() = filter { it != id }.let { rest -> if (on) rest + id else rest }
        return when (flag) {
            SessionFlag.SAVED -> copy(savedSessionIds = savedSessionIds.withId())
            SessionFlag.COMPLETED -> copy(completedSessionIds = completedSessionIds.withId())
        }
    }
}
