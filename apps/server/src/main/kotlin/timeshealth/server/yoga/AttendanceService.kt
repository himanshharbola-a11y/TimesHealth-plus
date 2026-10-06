package timeshealth.server.yoga

import java.time.Instant
import java.time.LocalDate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import timeshealth.app.core.domain.istDate
import timeshealth.server.db.Cuid

/**
 * Port of services/attendance.ts, the parts the feed and live classes use.
 *
 * Single-source attendance (PRD §7.1, do-not-cut): every channel (app join, live class join,
 * WhatsApp link) writes here, and UNIQUE(userId, date) makes two joins on one IST day one mark,
 * whichever channel arrived first. Joining late still counts; watching a recording never calls
 * this.
 */
@Service
class AttendanceService(private val jdbc: JdbcClient) {

    /** Records today's (IST) mark. Returns false when the user was already marked today. */
    fun record(userId: String, batchId: String?, source: String, now: Instant): Boolean {
        // A DATE column: the IST calendar day itself (see istCalendarDate in core/domain).
        val inserted = jdbc.sql(
            """INSERT INTO "Attendance" ("id", "userId", "date", "batchId", "source")
               VALUES (:id, :userId, :date, :batchId, :source)
               ON CONFLICT ("userId", "date") DO NOTHING""",
        ).param("id", Cuid.next())
            .param("userId", userId)
            .param("date", istDate(now))
            .param("batchId", batchId)
            .param("source", source)
            .update()
        return inserted == 1
    }

    /** The longest run of consecutive attended IST days. */
    fun bestStreak(userId: String): Int {
        val days = jdbc.sql("""SELECT "date" FROM "Attendance" WHERE "userId" = :u ORDER BY "date"""")
            .param("u", userId)
            .query { rs, _ -> rs.getObject("date", LocalDate::class.java) }
            .list()
        return bestStreak(days)
    }

    companion object {
        /** computeBestStreak: [ascending] days, consecutive runs. */
        fun bestStreak(ascending: List<LocalDate>): Int {
            var best = 0
            var run = 0
            ascending.forEachIndexed { i, day ->
                run = if (i > 0 && ascending[i - 1].plusDays(1) == day) run + 1 else 1
                if (run > best) best = run
            }
            return best
        }
    }
}
