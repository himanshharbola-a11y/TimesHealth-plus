package timeshealth.app.core.model

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// Live classes ("premieres"): a pre-recorded class streamed at a scheduled
// time, like a YouTube premiere. Scheduled by admins in the dashboard; every
// viewer is at the same point of the stream.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Where a video is hosted, as the app's video plug-in point resolves it
 * (core/integrations `VideoSourceResolver`): provider "url" = [ref] is an
 * HLS/MP4 address; "slike" = [ref] is a Slike media id.
 */
@Serializable
data class VideoSource(val provider: String, val ref: String)

/** Where a live class is in its life, computed by the server from its clock. */
@Serializable(with = LiveClassState.Serializer::class)
enum class LiveClassState {
    /** More than the wait-room window away. */
    SCHEDULED,

    /** The wait room is open: joining now shows a countdown, then the stream. */
    STARTING_SOON,
    LIVE,
    ENDED,
    CANCELLED,
    UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<LiveClassState>(entries, UNKNOWN)
}

/** A live class as listed (Home rail, Yoga schedule). Carries no stream: see [LiveClassJoinResponse]. */
@Serializable
data class LiveClassCard(
    val id: String,
    val title: String,
    val description: String = "",
    val imageUrl: String? = null,
    val startsAt: String,
    val endsAt: String,
    val durationMinutes: Int,
    /** Free classes are open to everyone; the rest need the yoga membership. */
    val isFree: Boolean,
    val state: LiveClassState,
    val instructorName: String? = null,
    val instructorAvatarUrl: String? = null,
    /** The daily batch this class belongs to, if any. */
    val batchId: String? = null,
    /** True when this user may watch it (free, or a member). False: the tap opens the paywall. */
    val canJoin: Boolean,
)

/** GET /yoga/live: today's and upcoming live classes, soonest first. */
@Serializable
data class LiveClassListResponse(
    val items: List<LiveClassCard> = emptyList(),
    val serverTime: String,
)

/**
 * POST /yoga/live/{id}/join. The stream and where "now" is in it. Joining a
 * member class also records attendance (once per IST day, whichever channel
 * is first).
 */
@Serializable
data class LiveClassJoinResponse(
    val liveClassId: String,
    val video: VideoSource,
    val startsAt: String,
    val endsAt: String,
    val serverTime: String,
    /** How far into the stream "now" is, in ms; 0 while the wait room counts down. */
    val positionMs: Long,
    val attendanceRecorded: Boolean,
)
