package timeshealth.app.ui.yoga

import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import timeshealth.app.BuildConfig
import timeshealth.app.core.data.cache.CacheKeys
import timeshealth.app.core.data.cache.ResponseCache
import timeshealth.app.core.data.repository.YogaRepository
import timeshealth.app.core.integrations.analytics.Analytics
import timeshealth.app.core.integrations.analytics.AnalyticsEvents
import timeshealth.app.core.integrations.video.PlayableStream
import timeshealth.app.core.integrations.video.VideoRef
import timeshealth.app.core.integrations.video.Videos
import timeshealth.app.core.model.MySessionsResponse
import timeshealth.app.core.model.PlaybackResponse
import timeshealth.app.core.model.YogaCatalogResponse
import timeshealth.app.core.model.YogaTodayResponse

/** A recording ready to play, and whether it is the debug stand-in. */
data class SessionStream(val stream: PlayableStream, val isSample: Boolean = false)

/**
 * What the recorded-session screens (library, session detail, player) need
 * from core:data and the video plug-in point.
 */
interface YogaSessionsGateway {
    suspend fun catalog(refresh: Boolean = false): YogaCatalogResponse
    val catalogChanges: Flow<Unit>

    suspend fun today(refresh: Boolean = false): YogaTodayResponse

    /** Saved and completed ids; [mySessionsData] shows a tap at once. */
    suspend fun mySessions(refresh: Boolean = false): MySessionsResponse
    val mySessionsData: Flow<MySessionsResponse?>

    suspend fun setSaved(sessionId: String, saved: Boolean)
    suspend fun setCompleted(sessionId: String, completed: Boolean)

    /** A fresh stream for the player. Null when the session has no video yet. */
    suspend fun stream(sessionId: String): SessionStream?

    /** The membership changed under us (a 403 on play): refetch everything that gates on it. */
    fun entitlementsChanged()
}

class RepositoryYogaSessionsGateway @Inject constructor(
    private val yoga: YogaRepository,
    private val videos: Videos,
    private val cache: ResponseCache,
    private val analytics: Analytics,
) : YogaSessionsGateway {

    override suspend fun catalog(refresh: Boolean) = yoga.catalog.get(refresh)
    override val catalogChanges: Flow<Unit> get() = yoga.catalog.changes
    override suspend fun today(refresh: Boolean) = yoga.today.get(refresh)
    override suspend fun mySessions(refresh: Boolean) = yoga.mySessions.get(refresh)
    override val mySessionsData: Flow<MySessionsResponse?> get() = yoga.mySessions.data

    override suspend fun setSaved(sessionId: String, saved: Boolean) {
        yoga.setSaved(sessionId, saved)
    }

    override suspend fun setCompleted(sessionId: String, completed: Boolean) {
        yoga.setCompleted(sessionId, completed)
    }

    override suspend fun stream(sessionId: String): SessionStream? =
        streamFor(yoga.playback(sessionId), videos::supports, { videos.resolve(it) }, BuildConfig.DEBUG)
            ?.also { analytics.track(AnalyticsEvents.videoPlayed(sessionId)) }

    override fun entitlementsChanged() = cache.invalidate(CacheKeys.AfterPurchase)

    companion object {
        /**
         * `.invalid` (RFC 2606) never resolves. Until real session media is uploaded, signed URLs
         * point there; debug builds play this public HLS test stream instead, labelled as a sample,
         * rather than a broken player.
         */
        const val DEV_SAMPLE_STREAM = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

        /**
         * Which stream to play: the dashboard's video through the plug-in point when a resolver for
         * its provider is plugged in (Slike once added), else the signed URL.
         */
        internal suspend fun streamFor(
            playback: PlaybackResponse,
            supports: (String) -> Boolean,
            resolve: suspend (VideoRef) -> PlayableStream,
            debug: Boolean,
        ): SessionStream? {
            playback.video?.takeIf { supports(it.provider) }?.let { v ->
                return SessionStream(resolve(VideoRef(provider = v.provider, id = v.ref)))
            }
            val url = playback.playbackUrl.takeIf { it.isNotBlank() } ?: return null
            if (debug && runCatching { URI(url).host.orEmpty().endsWith(".invalid") }.getOrDefault(false)) {
                return SessionStream(PlayableStream(DEV_SAMPLE_STREAM, mimeType = "application/x-mpegURL"), isSample = true)
            }
            return SessionStream(PlayableStream(url, mimeType = if (".m3u8" in url) "application/x-mpegURL" else null))
        }
    }
}
