package timeshealth.server.feed

import java.time.Duration
import java.time.Instant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import timeshealth.app.core.domain.IST
import timeshealth.app.core.domain.UserSignals
import timeshealth.app.core.model.RaceLifecycleStatus
import timeshealth.app.core.model.UserPersona
import timeshealth.server.db.toDbTime
import timeshealth.server.session.ResolvedEntitlements

/**
 * What personalisation knows about one user, read in a few small queries:
 * onboarding answers, membership and races, classes attended, recordings
 * completed and saved per track, and recent runs. See core:domain
 * Personalization.kt for how they are used.
 */
@Component
class UserSignalsRepository(private val jdbc: JdbcClient) {

    fun load(userId: String, goal: String?, concern: String?, resolved: ResolvedEntitlements, now: Instant): UserSignals {
        val today = now.atZone(IST).toLocalDate()
        val attended = jdbc.sql("""SELECT "date" FROM "Attendance" WHERE "userId" = :u AND "date" > :from ORDER BY "date" DESC""")
            .param("u", userId).param("from", today.minusDays(60))
            .query { rs, _ -> rs.getDate("date").toLocalDate() }.list()
        // Current streak: consecutive days back from today (or yesterday, if today isn't done yet).
        var day = if (attended.firstOrNull() == today) today else today.minusDays(1)
        var streak = 0
        val days = attended.toSet()
        while (day in days) {
            streak++
            day = day.minusDays(1)
        }
        val runs = jdbc.sql(
            """SELECT count(*) FILTER (WHERE "startedAt" >= :since) AS n, coalesce(sum("distanceKm") FILTER (WHERE "startedAt" >= :since), 0) AS km,
                      coalesce(max("distanceKm"), 0) AS longest
               FROM "RunRecord" WHERE "userId" = :u""",
        ).param("u", userId).param("since", now.minus(Duration.ofDays(30)).toDbTime())
            .query { rs, _ -> Triple(rs.getInt("n"), rs.getDouble("km"), rs.getDouble("longest")) }.single()
        return UserSignals(
            goal = goal,
            concern = concern,
            isYogaMember = resolved.persona.hasYoga,
            isLapsedMember = resolved.persona.persona == UserPersona.YOGA_EXPIRED,
            hasUpcomingRace = resolved.entitlements.marathon.any { it.status == RaceLifecycleStatus.UPCOMING || it.status == RaceLifecycleStatus.RACE_DAY },
            classesLast7Days = attended.count { !it.isBefore(today.minusDays(6)) },
            currentStreak = streak,
            completedByCategory = perCategory("CompletedSession", userId),
            savedByCategory = perCategory("SavedSession", userId),
            runsLast30Days = runs.first,
            kmLast30Days = runs.second,
            longestRunKm = runs.third,
            hourOfDay = now.atZone(IST).hour,
        )
    }

    private fun perCategory(table: String, userId: String): Map<String, Int> =
        jdbc.sql(
            """SELECT s."categoryId" AS c, count(*) AS n FROM "$table" x JOIN "YogaSession" s ON s."id" = x."sessionId"
               WHERE x."userId" = :u GROUP BY s."categoryId"""",
        ).param("u", userId).query { rs, _ -> rs.getString("c") to rs.getInt("n") }.list().toMap()
}
