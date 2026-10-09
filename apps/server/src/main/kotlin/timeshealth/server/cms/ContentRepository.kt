package timeshealth.server.cms

import java.sql.ResultSet
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import timeshealth.server.db.instant
import timeshealth.server.db.intOrNull
import timeshealth.server.db.requireInstant
import timeshealth.server.db.stringList
import timeshealth.server.db.toDbTime
import timeshealth.server.json.ServerJson

// ── Rows ────────────────────────────────────────────────────────────────────

data class InstructorRow(
    val id: String,
    val name: String,
    val title: String,
    val specialty: String,
    val bio: String,
    val experience: String,
    val avatarUrl: String,
    val rating: Double,
    val handle: String?,
    val reelCount: Int,
)

data class SessionRow(
    val id: String,
    val categoryId: String,
    val title: String,
    val description: String,
    val imageUrl: String,
    val durationMinutes: Int,
    val level: String,
    val intensity: String,
    val caloriesBurned: Int,
    val isFree: Boolean,
    val bodyFocusTitle: String,
    val targetBodyParts: List<String>,
    val keyPoses: List<String>,
    val lifestyleImpact: String,
    val joinedCountTillDate: Int,
    val todayActiveCount: Int,
    val mediaKey: String?,
    val videoProvider: String?,
    val videoRef: String?,
    val instructor: InstructorRow,
)

data class CategoryRow(val id: String, val name: String, val sortOrder: Int)

data class BatchRow(
    val id: String,
    val title: String,
    /** "HH:mm" IST. */
    val time: String,
    val period: String,
    val instructorName: String,
    val instructorAvatarUrl: String?,
)

data class ArticleRow(
    val id: String,
    val title: String,
    val source: String,
    val category: String,
    val imageUrl: String,
    val readTimeMinutes: Int,
    val url: String,
)

data class QuoteRow(val id: String, val quote: String, val author: String, val role: String)

data class ReelRow(
    val id: String,
    val instructorId: String,
    val instructorName: String,
    val instructorHandle: String?,
    val thumbnailUrl: String,
    val playbackUrl: String,
    val durationSeconds: Int,
)

data class PromoRow(
    val campaignId: String,
    val title: String,
    val subtitle: String?,
    val ctaLabel: String,
    val imageUrl: String?,
    val backgroundColor: String?,
    val action: JsonElement,
    val sellsProduct: String,
    val startsAt: Instant?,
    val endsAt: Instant?,
)

data class LiveClassRow(
    val id: String,
    val title: String,
    val description: String,
    val imageUrl: String?,
    val startsAt: Instant,
    val durationMinutes: Int,
    val isFree: Boolean,
    val videoProvider: String,
    val videoRef: String,
    val status: String,
    val batchId: String?,
    val instructorName: String?,
    val instructorAvatarUrl: String?,
) {
    val endsAt: Instant get() = startsAt.plusSeconds(durationMinutes * 60L)
    val cancelled: Boolean get() = status == "CANCELLED"
}

data class SectionRow(
    val id: String,
    val page: String,
    val kind: String,
    val title: String,
    val subtitle: String?,
    val actionLabel: String?,
    val imageUrl: String?,
    val categoryId: String?,
    val maxItems: Int,
    val sortOrder: Int,
    val visible: Boolean,
    val audience: String,
    val startsAt: Instant?,
    val endsAt: Instant?,
) {
    /** Visible and inside its optional schedule window [startsAt, endsAt). */
    fun isShowingAt(now: Instant): Boolean =
        visible && (startsAt == null || !now.isBefore(startsAt)) && (endsAt == null || now.isBefore(endsAt))
}

data class SectionItemRow(val sectionId: String, val refType: String, val refId: String, val sortOrder: Int)

/**
 * Every global content read the app's pages need, through [ContentCache]. Hidden content
 * (`visible = false` on a session or its category, an inactive batch) never leaves here.
 */
@Repository
class ContentRepository(private val jdbc: JdbcClient, private val cache: ContentCache) {

    fun sections(page: String): List<SectionRow> = cache.get("sections:$page") {
        jdbc.sql("""SELECT * FROM "FeedSection" WHERE "page" = :page ORDER BY "sortOrder", "id"""")
            .param("page", page)
            .query { rs, _ -> rs.toSection() }
            .list()
    }

    fun sectionItems(sectionId: String): List<SectionItemRow> = cache.get("section-items:$sectionId") {
        jdbc.sql(
            """SELECT * FROM "FeedSectionItem" WHERE "sectionId" = :id ORDER BY "sortOrder", "id"""",
        ).param("id", sectionId).query { rs, _ ->
            SectionItemRow(rs.getString("sectionId"), rs.getString("refType"), rs.getString("refId"), rs.getInt("sortOrder"))
        }.list()
    }

    /** Active daily batches with their instructor. Unordered: callers sort by clock time. */
    fun batches(): List<BatchRow> = cache.get("batches") {
        jdbc.sql(
            """SELECT b."id", b."title", b."time", b."period", i."name" AS "iName", i."avatarUrl" AS "iAvatar"
               FROM "YogaBatch" b JOIN "Instructor" i ON i."id" = b."instructorId"
               WHERE b."active"""",
        ).query { rs, _ ->
            BatchRow(
                rs.getString("id"), rs.getString("title"), rs.getString("time"), rs.getString("period"),
                rs.getString("iName"), rs.getString("iAvatar"),
            )
        }.list()
    }

    /** Dashboard switch: re-rank Home per user. On unless an admin turned it off. */
    fun personalizeHome(): Boolean = cache.get("config:personalizeHome") {
        jdbc.sql("""SELECT "personalizeHome" FROM "AppConfig" ORDER BY "id" LIMIT 1""")
            .query(Boolean::class.java).optional().orElse(true)
    }

    fun categories(): List<CategoryRow> = cache.get("categories") {
        jdbc.sql("""SELECT "id", "name", "sortOrder" FROM "YogaCategory" WHERE "visible" ORDER BY "sortOrder", "id"""")
            .query { rs, _ -> CategoryRow(rs.getString("id"), rs.getString("name"), rs.getInt("sortOrder")) }
            .list()
    }

    fun sessionsInCategory(categoryId: String, take: Int): List<SessionRow> = cache.get("sessions:$categoryId:$take") {
        sessions("""s."categoryId" = :cat""", mapOf("cat" to categoryId), take)
    }

    fun freeSessions(take: Int): List<SessionRow> = cache.get("sessions:free:$take") {
        sessions("""s."isFree"""", emptyMap(), take)
    }

    /** The given sessions in the given order, skipping hidden or deleted ones. */
    fun sessionsByIds(ids: List<String>): List<SessionRow> {
        if (ids.isEmpty()) return emptyList()
        val byId = cache.get("sessions:ids:${ids.joinToString(",")}") {
            sessions("""s."id" IN (:ids)""", mapOf("ids" to ids), ids.size)
        }.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    fun articles(take: Int): List<ArticleRow> = cache.get("articles:$take") {
        jdbc.sql("""SELECT * FROM "Article" ORDER BY "sortOrder", "publishedAt" DESC LIMIT :take""")
            .param("take", take)
            .query { rs, _ ->
                ArticleRow(
                    rs.getString("id"), rs.getString("title"), rs.getString("source"), rs.getString("category"),
                    rs.getString("imageUrl"), rs.getInt("readTimeMinutes"), rs.getString("url"),
                )
            }.list()
    }

    fun quotes(take: Int): List<QuoteRow> = cache.get("quotes:$take") {
        jdbc.sql("""SELECT * FROM "UserQuote" ORDER BY "sortOrder", "id" LIMIT :take""")
            .param("take", take)
            .query { rs, _ -> QuoteRow(rs.getString("id"), rs.getString("quote"), rs.getString("author"), rs.getString("role")) }
            .list()
    }

    fun reels(take: Int): List<ReelRow> = cache.get("reels:$take") {
        jdbc.sql(
            """SELECT r.*, i."name" AS "iName", i."handle" AS "iHandle"
               FROM "Reel" r JOIN "Instructor" i ON i."id" = r."instructorId"
               ORDER BY r."sortOrder", r."id" LIMIT :take""",
        ).param("take", take).query { rs, _ ->
            ReelRow(
                rs.getString("id"), rs.getString("instructorId"), rs.getString("iName"), rs.getString("iHandle"),
                rs.getString("thumbnailUrl"), rs.getString("playbackUrl"), rs.getInt("durationSeconds"),
            )
        }.list()
    }

    /**
     * Active campaigns inside their window at [now], highest priority first. The window is
     * applied per call, so a cached list can't show a campaign early or late.
     */
    fun activePromos(now: Instant): List<PromoRow> = cache.get("promos") {
        jdbc.sql("""SELECT * FROM "PromoCampaign" WHERE "active" ORDER BY "priority" DESC, "id"""")
            .query { rs, _ ->
                PromoRow(
                    campaignId = rs.getString("campaignId"),
                    title = rs.getString("title"),
                    subtitle = rs.getString("subtitle"),
                    ctaLabel = rs.getString("ctaLabel"),
                    imageUrl = rs.getString("imageUrl"),
                    backgroundColor = rs.getString("backgroundColor"),
                    action = ServerJson.parseToJsonElement(rs.getString("action")),
                    sellsProduct = rs.getString("sellsProduct"),
                    startsAt = rs.instant("startsAt"),
                    endsAt = rs.instant("endsAt"),
                )
            }.list()
    }.filter { p -> (p.startsAt == null || !p.startsAt.isAfter(now)) && (p.endsAt == null || !p.endsAt.isBefore(now)) }

    /**
     * Live classes that haven't ended by [from] and start before [until], soonest first.
     * Cancelled ones are included (a user who set a reminder must see the cancellation); the
     * caller decides whether to show them. Cached per minute of [from].
     */
    fun liveClasses(from: Instant, until: Instant): List<LiveClassRow> {
        val minute = from.epochSecond / 60
        return cache.get("live:$minute:${until.epochSecond / 60}") {
            jdbc.sql(
                """SELECT l.*, i."name" AS "iName", i."avatarUrl" AS "iAvatar"
                   FROM "LiveClass" l LEFT JOIN "Instructor" i ON i."id" = l."instructorId"
                   WHERE l."startsAt" < :until
                     AND l."startsAt" + make_interval(mins => l."durationMinutes") > :from
                   ORDER BY l."startsAt", l."id"""",
            ).param("from", from.toDbTime())
                .param("until", until.toDbTime())
                .query { rs, _ -> rs.toLiveClass() }
                .list()
        }
    }

    fun liveClass(id: String): LiveClassRow? =
        jdbc.sql(
            """SELECT l.*, i."name" AS "iName", i."avatarUrl" AS "iAvatar"
               FROM "LiveClass" l LEFT JOIN "Instructor" i ON i."id" = l."instructorId"
               WHERE l."id" = :id""",
        ).param("id", id).query { rs, _ -> rs.toLiveClass() }.optional().orElse(null)

    private fun sessions(where: String, params: Map<String, Any>, take: Int): List<SessionRow> =
        jdbc.sql(
            """SELECT s.*, i."id" AS "i_id", i."name" AS "i_name", i."title" AS "i_title",
                      i."specialty" AS "i_specialty", i."bio" AS "i_bio", i."experience" AS "i_experience",
                      i."avatarUrl" AS "i_avatarUrl", i."rating" AS "i_rating", i."handle" AS "i_handle",
                      i."reelCount" AS "i_reelCount"
               FROM "YogaSession" s
               JOIN "Instructor" i ON i."id" = s."instructorId"
               JOIN "YogaCategory" c ON c."id" = s."categoryId"
               WHERE $where AND s."visible" AND c."visible"
               ORDER BY s."sortOrder", s."id"
               LIMIT :take""",
        ).params(params).param("take", take).query { rs, _ -> rs.toSession() }.list()

    private fun ResultSet.toSession() = SessionRow(
        id = getString("id"),
        categoryId = getString("categoryId"),
        title = getString("title"),
        description = getString("description"),
        imageUrl = getString("imageUrl"),
        durationMinutes = getInt("durationMinutes"),
        level = getString("level"),
        intensity = getString("intensity"),
        caloriesBurned = getInt("caloriesBurned"),
        isFree = getBoolean("isFree"),
        bodyFocusTitle = getString("bodyFocusTitle"),
        targetBodyParts = stringList("targetBodyParts"),
        keyPoses = stringList("keyPoses"),
        lifestyleImpact = getString("lifestyleImpact"),
        joinedCountTillDate = getInt("joinedCountTillDate"),
        todayActiveCount = getInt("todayActiveCount"),
        mediaKey = getString("mediaKey"),
        videoProvider = getString("videoProvider"),
        videoRef = getString("videoRef"),
        instructor = InstructorRow(
            id = getString("i_id"),
            name = getString("i_name"),
            title = getString("i_title"),
            specialty = getString("i_specialty"),
            bio = getString("i_bio"),
            experience = getString("i_experience"),
            avatarUrl = getString("i_avatarUrl"),
            rating = getDouble("i_rating"),
            handle = getString("i_handle"),
            reelCount = getInt("i_reelCount"),
        ),
    )

    private fun ResultSet.toLiveClass() = LiveClassRow(
        id = getString("id"),
        title = getString("title"),
        description = getString("description"),
        imageUrl = getString("imageUrl"),
        startsAt = requireInstant("startsAt"),
        durationMinutes = getInt("durationMinutes"),
        isFree = getBoolean("isFree"),
        videoProvider = getString("videoProvider"),
        videoRef = getString("videoRef"),
        status = getString("status"),
        batchId = getString("batchId"),
        instructorName = getString("iName"),
        instructorAvatarUrl = getString("iAvatar"),
    )

    private fun ResultSet.toSection() = SectionRow(
        id = getString("id"),
        page = getString("page"),
        kind = getString("kind"),
        title = getString("title"),
        subtitle = getString("subtitle"),
        actionLabel = getString("actionLabel"),
        imageUrl = getString("imageUrl"),
        categoryId = getString("categoryId"),
        maxItems = intOrNull("maxItems") ?: 10,
        sortOrder = getInt("sortOrder"),
        visible = getBoolean("visible"),
        audience = getString("audience"),
        startsAt = instant("startsAt"),
        endsAt = instant("endsAt"),
    )
}
