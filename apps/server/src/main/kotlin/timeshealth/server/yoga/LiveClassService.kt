package timeshealth.server.yoga

import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Service
import timeshealth.app.core.domain.WAIT_ROOM_MINUTES
import timeshealth.app.core.domain.formatBatchTime
import timeshealth.app.core.domain.IST
import timeshealth.app.core.model.LiveClassCard
import timeshealth.app.core.model.LiveClassJoinResponse
import timeshealth.app.core.model.LiveClassListResponse
import timeshealth.app.core.model.LiveClassState
import timeshealth.app.core.model.VideoSource
import timeshealth.server.cms.ContentRepository
import timeshealth.server.cms.LiveClassRow
import timeshealth.server.db.entity.UserEntity
import timeshealth.server.error.ApiException
import timeshealth.server.json.toIsoString
import timeshealth.server.session.EntitlementService

/**
 * Live classes ("premieres"): pre-recorded classes streamed at a scheduled time, like a YouTube
 * premiere, scheduled by admins in the dashboard.
 *
 * - The stream is handed out only by [join], never in a listing: that is where membership is
 *   checked and attendance written (docs/04 T9: never from a client assertion).
 * - Joining opens with the wait room ([WAIT_ROOM_MINUTES] before the start) and closes when the
 *   class ends. §7.1: joining late still counts.
 * - Every viewer is at the same point of the stream: [LiveClassJoinResponse.positionMs].
 */
@Service
class LiveClassService(
    private val content: ContentRepository,
    private val entitlements: EntitlementService,
    private val attendance: AttendanceService,
    private val clock: Clock,
) {

    /** Today's and the coming week's classes, soonest first, including cancellations. */
    fun list(user: UserEntity): LiveClassListResponse {
        val now = clock.instant()
        val entitled = entitlements.resolve(user.id, now).persona.hasYoga
        return LiveClassListResponse(
            items = content.liveClasses(now, now.plus(LIST_AHEAD)).map { card(it, entitled, now) },
            serverTime = now.toIsoString(),
        )
    }

    fun join(user: UserEntity, id: String): LiveClassJoinResponse {
        val now = clock.instant()
        val row = content.liveClass(id) ?: throw ApiException(404, "NOT_FOUND", "Live class not found")
        if (row.cancelled) throw ApiException(409, "CLASS_CANCELLED", "${row.title} was cancelled.")
        if (!now.isBefore(row.endsAt)) throw ApiException(409, "CLASS_ENDED", "${row.title} has ended.")
        if (now.isBefore(row.startsAt.minus(WAIT_ROOM))) {
            val at = formatBatchTime(row.startsAt.atOffset(IST).toLocalTime().toString().take(5))
            throw ApiException(
                409, "CLASS_NOT_OPEN",
                "${row.title} isn't open yet — it opens an hour before $at.",
            )
        }

        val member = EntitlementService.canAccessLiveYoga(entitlements.resolve(user.id, now).entitlements)
        if (!row.isFree && !member) throw ApiException(403, "NOT_ENTITLED", "Yoga subscription required")

        // The membership attendance ledger: one mark per IST day, whichever channel is first.
        // A non-member watching a free class isn't tracked (the tracker is a membership feature).
        val recorded = member && attendance.record(user.id, row.batchId, "APP", now)

        return LiveClassJoinResponse(
            liveClassId = row.id,
            video = VideoSource(row.videoProvider, row.videoRef),
            startsAt = row.startsAt.toIsoString(),
            endsAt = row.endsAt.toIsoString(),
            serverTime = now.toIsoString(),
            positionMs = maxOf(0L, Duration.between(row.startsAt, now).toMillis()),
            attendanceRecorded = recorded,
        )
    }

    companion object {
        val WAIT_ROOM: Duration = Duration.ofMinutes(WAIT_ROOM_MINUTES)
        val LIST_AHEAD: Duration = Duration.ofDays(7)

        fun stateOf(row: LiveClassRow, now: Instant): LiveClassState = when {
            row.cancelled -> LiveClassState.CANCELLED
            !now.isBefore(row.endsAt) -> LiveClassState.ENDED
            !now.isBefore(row.startsAt) -> LiveClassState.LIVE
            !now.isBefore(row.startsAt.minus(WAIT_ROOM)) -> LiveClassState.STARTING_SOON
            else -> LiveClassState.SCHEDULED
        }

        fun card(row: LiveClassRow, entitled: Boolean, now: Instant) = LiveClassCard(
            id = row.id,
            title = row.title,
            description = row.description,
            imageUrl = row.imageUrl,
            startsAt = row.startsAt.toIsoString(),
            endsAt = row.endsAt.toIsoString(),
            durationMinutes = row.durationMinutes,
            isFree = row.isFree,
            state = stateOf(row, now),
            instructorName = row.instructorName,
            instructorAvatarUrl = row.instructorAvatarUrl,
            batchId = row.batchId,
            canJoin = row.isFree || entitled,
        )
    }
}
