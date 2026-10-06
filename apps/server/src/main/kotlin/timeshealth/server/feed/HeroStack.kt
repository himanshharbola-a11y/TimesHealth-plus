package timeshealth.server.feed

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import timeshealth.app.core.domain.WAIT_ROOM_MINUTES
import timeshealth.app.core.domain.YOGA_BATCH_DURATION_MINUTES
import timeshealth.app.core.domain.batchInstant
import timeshealth.app.core.domain.byClockTime
import timeshealth.app.core.domain.istDate
import timeshealth.app.core.domain.istDaysUntil
import timeshealth.app.core.domain.isLiveNow
import timeshealth.app.core.model.Entitlements
import timeshealth.app.core.model.HeroMyRace
import timeshealth.app.core.model.HeroRaceResult
import timeshealth.app.core.model.HeroSellMarathon
import timeshealth.app.core.model.HeroSellYoga
import timeshealth.app.core.model.HeroSessionState
import timeshealth.app.core.model.HeroSlot
import timeshealth.app.core.model.HeroYogaRenew
import timeshealth.app.core.model.HeroYogaSession
import timeshealth.app.core.model.PersonaInfo
import timeshealth.app.core.model.RaceLifecycleStatus
import timeshealth.server.cms.BatchRow
import timeshealth.server.cms.ContentRepository
import timeshealth.server.cms.LiveClassRow
import timeshealth.server.db.instant
import timeshealth.server.db.requireInstant
import timeshealth.server.db.toDbTime
import timeshealth.server.json.toIsoString
import timeshealth.server.session.EntitlementService

/** A daily batch resolved to a real start time. */
data class ScheduledBatch(val batch: BatchRow, val startsAt: Instant, val isToday: Boolean)

/** getTodaySchedule: the class live now (if any) and the next one to start. */
data class TodaySchedule(val live: ScheduledBatch?, val next: ScheduledBatch?)

/**
 * Port of the hero half of services/feed.ts (PRD §6.1). The hard priority rule:
 *   1. a yoga member's class ALWAYS wins slot 1;
 *   2. whatever loses drops to slot 2.
 *
 * New with the admin CMS: when an admin scheduled a live class (premiere) for the batch shown,
 * the yoga slot carries its id, title and artwork, and the app plays it in-app.
 */
@Component
class HeroStack(private val content: ContentRepository, private val jdbc: JdbcClient) {

    fun todaySchedule(now: Instant): TodaySchedule {
        // Clock order: after the last class, "tomorrow" means the earliest batch (05:15).
        val batches = content.batches().sortedWith(compareBy(byClockTime) { it.time })
        if (batches.isEmpty()) return TodaySchedule(null, null)

        val todays = batches.mapNotNull { b -> batchInstant(b.time, 0, now)?.let { ScheduledBatch(b, it, true) } }
            .sortedBy { it.startsAt }
        val live = todays.firstOrNull { isLiveNow(it.startsAt, now) }
        val upcoming = todays.firstOrNull { it.startsAt.isAfter(now) }
        if (upcoming != null) return TodaySchedule(live, upcoming)

        // §6.1: all of today's batches have passed (or a rest day): the next scheduled class with its DAY.
        val first = batches.first()
        val tomorrow = batchInstant(first.time, 1, now)?.let { ScheduledBatch(first, it, false) }
        return TodaySchedule(live, tomorrow)
    }

    fun build(
        userId: String,
        entitlements: Entitlements,
        persona: PersonaInfo,
        schedule: TodaySchedule,
        streak: Int,
        now: Instant,
    ): List<HeroSlot> {
        val slots = mutableListOf<HeroSlot>()
        val renew = EntitlementService.shouldShowRenewPrompt(entitlements)

        if (persona.hasYoga) {
            yogaSessionSlot(schedule, now)?.let(slots::add)
        } else if (renew) {
            // §5: a warm re-subscribe prompt, never a cold sell.
            slots += renewSlot(entitlements, streak, now)
        }

        if (persona.hasMarathon) myRaceSlot(userId, entitlements, now)?.let(slots::add)

        // Marathon is per event (§2): only a race still AHEAD suppresses selling the next one.
        val hasRaceAhead = entitlements.marathon.any { it.status != RaceLifecycleStatus.COMPLETED }
        if (slots.size < 2 && !persona.hasYoga && !renew) slots += sellYogaSlot()
        if (slots.size < 2 && !hasRaceAhead) {
            sellMarathonSlot(now, entitlements.marathon.map { it.eventId })?.let(slots::add)
        }
        // No third fill: a second identical sell banner is worse than a single hero.
        return slots.take(2)
    }

    // ── Yoga ──────────────────────────────────────────────────────────────────

    private fun yogaSessionSlot(schedule: TodaySchedule, now: Instant): HeroSlot? {
        schedule.live?.let { live ->
            val premiere = premiereFor(live, now)
            return HeroYogaSession(
                title = premiere?.title ?: live.batch.title,
                subtitle = "Live now · with ${premiere?.instructorName ?: live.batch.instructorName}",
                ctaLabel = "Join Live Session",
                imageUrl = premiere?.imageUrl ?: YOGA_HERO_IMAGE,
                sessionId = live.batch.id,
                batchId = live.batch.id,
                state = HeroSessionState.LIVE,
                startsAt = live.startsAt.toIsoString(),
                secondsToStart = 0,
                // Issued by the join call, which also writes attendance.
                joinUrl = null,
                instructorName = premiere?.instructorName ?: live.batch.instructorName,
                instructorAvatarUrl = premiere?.instructorAvatarUrl ?: live.batch.instructorAvatarUrl,
                durationMinutes = YOGA_BATCH_DURATION_MINUTES.toInt(),
                liveClassId = premiere?.id,
            )
        }
        val next = schedule.next ?: return null
        val seconds = secondsBetween(now, next.startsAt)
        val startingSoon = next.isToday && seconds <= WAIT_ROOM_MINUTES * 60
        val premiere = premiereFor(next, now)
        return HeroYogaSession(
            title = premiere?.title ?: next.batch.title,
            subtitle = "with ${premiere?.instructorName ?: next.batch.instructorName}",
            // Only an open wait room is joinable; a later class opens the day's schedule.
            ctaLabel = if (startingSoon) "Join Wait Room" else "View Schedule",
            imageUrl = premiere?.imageUrl ?: YOGA_HERO_IMAGE,
            sessionId = next.batch.id,
            batchId = next.batch.id,
            state = when {
                startingSoon -> HeroSessionState.STARTING_SOON
                next.isToday -> HeroSessionState.SCHEDULED_TODAY
                else -> HeroSessionState.SCHEDULED_LATER
            },
            startsAt = next.startsAt.toIsoString(),
            // Null when not today: the app shows the day instead of a timer.
            secondsToStart = if (next.isToday) seconds.toInt() else null,
            joinUrl = null,
            instructorName = premiere?.instructorName ?: next.batch.instructorName,
            instructorAvatarUrl = premiere?.instructorAvatarUrl ?: next.batch.instructorAvatarUrl,
            durationMinutes = YOGA_BATCH_DURATION_MINUTES.toInt(),
            liveClassId = premiere?.id,
        )
    }

    /** The live class an admin scheduled for this batch on its day, if any (not cancelled). */
    private fun premiereFor(batch: ScheduledBatch, now: Instant): LiveClassRow? {
        val day = istDate(batch.startsAt)
        return content.liveClasses(now, now.plus(Duration.ofDays(2)))
            .firstOrNull { !it.cancelled && it.batchId == batch.batch.id && istDate(it.startsAt) == day }
    }

    private fun renewSlot(entitlements: Entitlements, streak: Int, now: Instant) = HeroYogaRenew(
        title = "Your saved practice is still here.",
        // Never "your 0-day streak": a member who never attended gets the general line.
        subtitle = if (streak > 0) {
            "Your $streak-day streak history is preserved. Re-subscribe to return to live batches."
        } else {
            "Your saved sessions and history are preserved. Re-subscribe to return to live batches."
        },
        ctaLabel = "Renew Membership",
        imageUrl = null,
        expiredAt = entitlements.yoga?.expiresAt ?: now.toIsoString(),
        preservedStreak = streak,
    )

    private fun sellYogaSlot() = HeroSellYoga(
        // The design's copy (HomeScreen.kt), line break included.
        title = "Eight live yoga classes\nevery single day",
        subtitle = "Taught by certified masters from The Yoga Institute. Join morning or evening.",
        ctaLabel = "Explore Yoga Membership",
        imageUrl = YOGA_HERO_IMAGE,
        planId = "yoga_annual",
        // Resolved by the paywall; never priced from the client.
        pricePaise = 0,
    )

    // ── Marathon ──────────────────────────────────────────────────────────────

    private class EventRow(
        val id: String,
        val name: String,
        val city: String,
        val imageUrl: String,
        val startsAt: Instant,
        val flagOffTime: String,
    )

    private fun events(ids: Collection<String>): Map<String, EventRow> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.sql("""SELECT * FROM "MarathonEvent" WHERE "id" IN (:ids)""")
            .param("ids", ids)
            .query { rs, _ ->
                EventRow(
                    rs.getString("id"), rs.getString("name"), rs.getString("city"), rs.getString("imageUrl"),
                    rs.requireInstant("startsAt"), rs.getString("flagOffTime"),
                )
            }.list().associateBy { it.id }
    }

    private fun myRaceSlot(userId: String, entitlements: Entitlements, now: Instant): HeroSlot? {
        val events = events(entitlements.marathon.map { it.eventId })
        // §6.1 edge case: several registrations → the nearest by date.
        val target = entitlements.marathon
            .filter { it.status != RaceLifecycleStatus.COMPLETED }
            .sortedBy { events[it.eventId]?.startsAt ?: Instant.EPOCH }
            .firstOrNull()
            ?: return raceResultSlot(userId, entitlements, events, now)
        val event = events[target.eventId] ?: return null

        // Calendar days in IST, so "1 day to go" means tomorrow, not "24h from now".
        val days = maxOf(0L, istDaysUntil(event.startsAt, now)).toInt()
        val isRaceDay = target.status == RaceLifecycleStatus.RACE_DAY
        return HeroMyRace(
            title = event.name,
            subtitle = when {
                isRaceDay -> "Race day"
                days == 1 -> "1 day to go"
                else -> "$days days to go"
            },
            // Race morning: the pass is what the runner needs at the gate.
            ctaLabel = if (isRaceDay) "Open Digital Bib & Pass" else "Open Race Dashboard",
            imageUrl = event.imageUrl,
            eventId = event.id,
            startsAt = event.startsAt.toIsoString(),
            daysRemaining = days,
            bibNumber = target.bibNumber,
            category = target.category,
            flagOffTime = event.flagOffTime,
            isRaceDay = isRaceDay,
        )
    }

    /**
     * §6.1 / §8.4: once the race is run, the result state. Held for [RESULT_HERO_DAYS] after the
     * result is PUBLISHED (timing partners can take a week); a result that never arrives lets go
     * after [PENDING_RESULT_MAX_DAYS] so the next edition can be sold.
     */
    private fun raceResultSlot(
        userId: String,
        entitlements: Entitlements,
        events: Map<String, EventRow>,
        now: Instant,
    ): HeroSlot? {
        val completed = entitlements.marathon.filter { it.status == RaceLifecycleStatus.COMPLETED }.map { it.eventId }
        if (completed.isEmpty()) return null

        data class Reg(val eventId: String, val published: Boolean?, val updatedAt: Instant?)
        val regs = jdbc.sql(
            """SELECT m."eventId", r."published", r."updatedAt"
               FROM "MarathonRegistration" m LEFT JOIN "RaceResult" r ON r."registrationId" = m."id"
               WHERE m."userId" = :u AND m."eventId" IN (:ids)""",
        ).param("u", userId).param("ids", completed).query { rs, _ ->
            Reg(rs.getString("eventId"), rs.getObject("published") as Boolean?, rs.instant("updatedAt"))
        }.list()

        val chosen = regs.filter { r ->
            if (r.published == true && r.updatedAt != null) {
                Duration.between(r.updatedAt, now) <= Duration.ofDays(RESULT_HERO_DAYS)
            } else {
                val raceAt = events[r.eventId]?.startsAt
                raceAt != null && Duration.between(raceAt, now) <= Duration.ofDays(PENDING_RESULT_MAX_DAYS)
            }
        }.sortedByDescending { events[it.eventId]?.startsAt ?: Instant.EPOCH }.firstOrNull() ?: return null

        val event = events[chosen.eventId] ?: return null
        val published = chosen.published == true
        return HeroRaceResult(
            title = event.name,
            subtitle = if (published) "Your result is ready" else "Results are being published",
            ctaLabel = if (published) "Check Result & Certificate" else "Result pending",
            imageUrl = event.imageUrl,
            eventId = event.id,
            resultPublished = published,
        )
    }

    /** §6.1 "Sell marathon (nearest edition)": never one the user already holds. */
    private fun sellMarathonSlot(now: Instant, exclude: List<String>): HeroSlot? {
        val event = jdbc.sql(
            """SELECT * FROM "MarathonEvent"
               WHERE "startsAt" > :now AND "registrationOpen"
                 ${if (exclude.isEmpty()) "" else """AND "id" NOT IN (:ex)"""}
               ORDER BY "startsAt", "id" LIMIT 1""",
        ).param("now", now.toDbTime())
            .apply { if (exclude.isNotEmpty()) param("ex", exclude) }
            .query { rs, _ ->
                EventRow(
                    rs.getString("id"), rs.getString("name"), rs.getString("city"), rs.getString("imageUrl"),
                    rs.requireInstant("startsAt"), rs.getString("flagOffTime"),
                )
            }.optional().orElse(null) ?: return null

        val prices = jdbc.sql(
            """SELECT "priceClassicPaise" FROM "RaceDistanceOption" WHERE "eventId" = :id ORDER BY "sortOrder"""",
        ).param("id", event.id).query(Int::class.java).list()
        return HeroSellMarathon(
            title = event.name,
            subtitle = "${event.city} · ${prices.size} distances",
            ctaLabel = "Register Now",
            imageUrl = event.imageUrl,
            eventId = event.id,
            fromPricePaise = prices.filter { it > 0 }.minOrNull()?.toLong() ?: 0L,
            city = event.city,
            startsAt = event.startsAt.toIsoString(),
        )
    }

    companion object {
        /** The yoga hero photo (the design's ThCardImage) when a batch has no artwork. */
        const val YOGA_HERO_IMAGE =
            "https://images.unsplash.com/photo-1545389336-cf090694435e?auto=format&fit=crop&w=900&q=75"

        /** §8.4: the result state stays on Home this long after publishing. */
        const val RESULT_HERO_DAYS = 14L

        /** A result the timing partner never publishes must not hold Home forever. */
        const val PENDING_RESULT_MAX_DAYS = 30L

        /** `Math.round((to - from) / 1000)`. */
        fun secondsBetween(from: Instant, to: Instant): Long =
            ((to.toEpochMilli() - from.toEpochMilli()) / 1000.0).roundToLong()
    }
}
