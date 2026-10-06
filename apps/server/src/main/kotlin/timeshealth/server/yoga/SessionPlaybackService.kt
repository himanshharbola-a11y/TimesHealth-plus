package timeshealth.server.yoga

import java.time.Clock
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import timeshealth.app.core.model.PlaybackResponse
import timeshealth.app.core.model.VideoSource
import timeshealth.server.db.entity.UserEntity
import timeshealth.server.error.ApiException
import timeshealth.server.media.MediaSigning
import timeshealth.server.session.EntitlementService

/**
 * A recorded session's video, fetched when the player opens (catalogue URLs are short-TTL
 * signatures the app may have cached past their expiry).
 *
 * Same rules as the Node route it replaces, plus the video the admin set in the dashboard:
 * - free sessions play for anyone signed in; paid ones need the yoga membership (§6.3);
 * - [PlaybackResponse.video] is the dashboard's Slike id or stream URL, which the app resolves
 *   through its video plug-in point;
 * - [PlaybackResponse.playbackUrl] stays for older app builds: the signed media URL, else the
 *   dashboard's plain stream URL, else empty (only a provider the app resolves has it).
 */
@Service
class SessionPlaybackService(
    private val jdbc: JdbcClient,
    private val entitlements: EntitlementService,
    private val media: MediaSigning,
    private val clock: Clock,
) {

    private data class Playable(val isFree: Boolean, val mediaKey: String?, val video: VideoSource?)

    fun playback(user: UserEntity, sessionId: String): PlaybackResponse {
        // Hidden sessions still play: hiding takes a video off the shelves, not out of the
        // hands of someone already watching it.
        val row = jdbc.sql("""SELECT "isFree", "mediaKey", "videoProvider", "videoRef" FROM "YogaSession" WHERE "id" = :id""")
            .param("id", sessionId)
            .query { rs, _ ->
                val provider = rs.getString("videoProvider")
                val ref = rs.getString("videoRef")
                Playable(
                    isFree = rs.getBoolean("isFree"),
                    mediaKey = rs.getString("mediaKey")?.takeIf { it.isNotBlank() },
                    video = if (!provider.isNullOrBlank() && !ref.isNullOrBlank()) VideoSource(provider, ref) else null,
                )
            }
            .optional().orElse(null)
        if (row == null || (row.mediaKey == null && row.video == null)) {
            throw ApiException(404, "NOT_FOUND", "This video isn’t available")
        }
        val now = clock.instant()
        if (!row.isFree && !entitlements.resolve(user.id, now).persona.hasYoga) {
            throw ApiException(403, "NOT_ENTITLED", "Yoga subscription required")
        }
        val playbackUrl = when {
            row.mediaKey != null -> media.signPlaybackUrl(row.mediaKey, user.id, now)
            row.video?.provider == "url" -> row.video.ref
            else -> ""
        }
        return PlaybackResponse(playbackUrl = playbackUrl, video = row.video)
    }
}
