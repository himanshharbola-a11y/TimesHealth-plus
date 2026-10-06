package timeshealth.server.feed

import java.time.Duration
import java.time.Instant
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import timeshealth.app.core.model.LiveWorkshop
import timeshealth.app.core.model.WorkshopCategory
import timeshealth.server.db.requireInstant
import timeshealth.server.db.toDbTime
import timeshealth.server.json.toIsoString

/**
 * Port of services/workshops.ts `loadWorkshopsFor`. Shared by the Home feed and (once ported)
 * GET /workshops, so the two can never disagree on price, seats or registration state.
 */
@Component
class Workshops(private val jdbc: JdbcClient) {

    fun loadFor(userId: String, hasYoga: Boolean, now: Instant): List<LiveWorkshop> {
        val paidFor = jdbc.sql(
            """SELECT "productId" FROM "Order"
               WHERE "userId" = :u AND "productType" = 'WORKSHOP' AND "status" = 'PAID'""",
        ).param("u", userId).query(String::class.java).list().toSet()

        return jdbc.sql(
            """SELECT w.*,
                      (SELECT count(*) FROM "WorkshopRegistration" r WHERE r."workshopId" = w."id") AS "taken",
                      EXISTS (SELECT 1 FROM "WorkshopRegistration" r
                              WHERE r."workshopId" = w."id" AND r."userId" = :u) AS "mine"
               FROM "LiveWorkshop" w
               WHERE w."startsAt" >= :from
               ORDER BY w."startsAt", w."id"""",
        ).param("u", userId)
            .param("from", now.minus(GRACE).toDbTime())
            .query { rs, _ ->
                val id = rs.getString("id")
                val startsAt = rs.requireInstant("startsAt")
                val category = rs.getString("category")
                val isRegistered = rs.getBoolean("mine")
                // Design copy "Free for Members": a yoga member pays nothing for yoga workshops.
                val freeForMember = hasYoga && category == "YOGA"
                val price = rs.getInt("pricePaise").takeUnless { rs.wasNull() }
                LiveWorkshop(
                    id = id,
                    title = rs.getString("title"),
                    description = rs.getString("description"),
                    category = WorkshopCategory.entries.firstOrNull { it.name == category } ?: WorkshopCategory.UNKNOWN,
                    focusArea = rs.getString("focusArea"),
                    imageUrl = rs.getString("imageUrl"),
                    startsAt = startsAt.toIsoString(),
                    durationMinutes = rs.getInt("durationMinutes"),
                    level = rs.getString("level"),
                    platform = rs.getString("platform"),
                    instructorName = rs.getString("instructorName"),
                    instructorTitle = rs.getString("instructorTitle"),
                    instructorAvatarUrl = rs.getString("instructorAvatarUrl"),
                    pricePaise = if (freeForMember) null else price?.toLong(),
                    // Computed from live counts, so it cannot drift.
                    spotsRemaining = maxOf(0, rs.getInt("totalCapacity") - rs.getInt("taken")),
                    totalCapacity = rs.getInt("totalCapacity"),
                    isRegistered = isRegistered,
                    paidSeat = isRegistered && id in paidFor,
                    joinUrl = if (isRegistered && Duration.between(now, startsAt) < JOIN_WINDOW) rs.getString("joinUrl") else null,
                )
            }.list()
    }

    companion object {
        /** Listed for two hours after start, so late joiners can find them. */
        val GRACE: Duration = Duration.ofHours(2)

        /** The join link is handed out only this close to the start. */
        val JOIN_WINDOW: Duration = Duration.ofHours(1)
    }
}
