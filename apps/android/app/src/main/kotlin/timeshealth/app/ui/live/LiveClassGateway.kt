package timeshealth.app.ui.live

import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import timeshealth.app.core.data.repository.YogaRepository
import timeshealth.app.core.integrations.analytics.Analytics
import timeshealth.app.core.integrations.analytics.AnalyticsEvents
import timeshealth.app.core.integrations.video.PlayableStream
import timeshealth.app.core.integrations.video.VideoRef
import timeshealth.app.core.integrations.video.Videos
import timeshealth.app.core.model.LiveClassCard
import timeshealth.app.core.model.LiveClassJoinResponse
import timeshealth.app.core.network.ServerClock
import timeshealth.app.reminders.ClassReminders

/**
 * What the live class screen needs: the class, joining it, turning its video
 * ref into something playable (through the video plug-in point, so Slike
 * plugs in without touching this screen), reminders and the server clock.
 */
interface LiveClassGateway {
    /** The class as listed (title, times, state), or null when it isn't in the coming week. */
    suspend fun card(id: String, refresh: Boolean = false): LiveClassCard?

    suspend fun join(id: String): LiveClassJoinResponse

    suspend fun resolve(join: LiveClassJoinResponse, startsAtMs: Long): PlayableStream

    val reminded: StateFlow<Set<String>>

    fun remind(card: LiveClassCard, startsAtMs: Long)

    fun cancelReminder(id: String)

    fun nowMs(): Long
}

class RepositoryLiveClassGateway @Inject constructor(
    private val yoga: YogaRepository,
    private val videos: Videos,
    private val reminders: ClassReminders,
    private val clock: ServerClock,
    private val analytics: Analytics,
) : LiveClassGateway {

    override suspend fun card(id: String, refresh: Boolean): LiveClassCard? =
        yoga.liveClasses.get(refresh).also { clock.sync(it.serverTime) }.items.firstOrNull { it.id == id }

    override suspend fun join(id: String): LiveClassJoinResponse =
        yoga.joinLiveClass(id).also {
            // The join answer carries the server's clock: the wait room counts down from it.
            clock.sync(it.serverTime)
            analytics.track(AnalyticsEvents.classJoined(id))
        }

    override suspend fun resolve(join: LiveClassJoinResponse, startsAtMs: Long): PlayableStream =
        videos.resolve(VideoRef(provider = join.video.provider, id = join.video.ref, premiereStartEpochMs = startsAtMs))

    override val reminded: StateFlow<Set<String>> get() = reminders.reminded

    override fun remind(card: LiveClassCard, startsAtMs: Long) {
        reminders.schedule(card.id, card.title, startsAtMs, clock.now())
        analytics.track(AnalyticsEvents.reminderSet(card.batchId ?: card.id))
    }

    override fun cancelReminder(id: String) = reminders.cancel(id)

    override fun nowMs(): Long = clock.now()
}
