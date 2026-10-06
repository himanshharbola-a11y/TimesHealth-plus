package timeshealth.server.db.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import timeshealth.server.db.Cuid
import timeshealth.server.json.ServerJson
import timeshealth.server.json.nowMillis

// Conventions: see IdentityEntities.kt.

/** Prisma `LiveWorkshop`. `pricePaise` null = free for entitled members. */
@Entity
@Table(name = "LiveWorkshop")
@DynamicUpdate
class LiveWorkshopEntity(
    @Id var id: String = Cuid.next(),
    var title: String,
    var description: String,
    /** YOGA | MARATHON | DIET */
    var category: String,
    var focusArea: String,
    var imageUrl: String,
    var startsAt: Instant,
    var durationMinutes: Int,
    var level: String,
    var platform: String,
    var instructorName: String,
    var instructorTitle: String,
    var instructorAvatarUrl: String,
    var pricePaise: Int? = null,
    var totalCapacity: Int,
    var joinUrl: String? = null,
)

/**
 * Prisma `WorkshopRegistration`. Capacity = UNIQUE(userId, workshopId) plus a count taken while
 * the workshop row is locked (`SELECT … FOR UPDATE`, orders.ts) — see PgSql.lockRow.
 */
@Entity
@Table(name = "WorkshopRegistration")
class WorkshopRegistrationEntity(
    @Id var id: String = Cuid.next(),
    var userId: String,
    var workshopId: String,
) {
    @Column(updatable = false)
    lateinit var createdAt: Instant

    @PrePersist
    fun onInsert() {
        if (!this::createdAt.isInitialized) createdAt = nowMillis()
    }
}

/** Prisma `RunRecord`. The id is the CLIENT's UUID, so uploads are idempotent. */
@Entity
@Table(name = "RunRecord")
@DynamicUpdate
class RunRecordEntity(
    @Id var id: String,
    var userId: String,
    var startedAt: Instant,
    var endedAt: Instant,
    var distanceKm: Double,
    var durationSeconds: Int,
    var avgPaceSecPerKm: Int,
    var caloriesBurned: Int,
    /** Private by default; never exposed to another user (docs/04 T7). */
    var routePolyline: String? = null,
    var hasAccuracyWarning: Boolean = false,
) {
    @Column(updatable = false)
    lateinit var createdAt: Instant

    @PrePersist
    fun onInsert() {
        if (!this::createdAt.isInitialized) createdAt = nowMillis()
    }
}

/** Prisma `DietLead` (PRD §9, lead capture only). Cascades with the user (DPDP). */
@Entity
@Table(name = "DietLead")
@DynamicUpdate
class DietLeadEntity(
    @Id var id: String = Cuid.next(),
    var userId: String? = null,
    var name: String,
    var phone: String,
    var condition: String? = null,
    var cuisinePreference: String? = null,
    var bestTimeToCall: String? = null,
    /** Set once forwarded to the existing diet pipeline. */
    var forwardedAt: Instant? = null,
) {
    @Column(updatable = false)
    lateinit var createdAt: Instant

    @PrePersist
    fun onInsert() {
        if (!this::createdAt.isInitialized) createdAt = nowMillis()
    }
}

/** Prisma `Article`. */
@Entity
@Table(name = "Article")
@DynamicUpdate
class ArticleEntity(
    @Id var id: String = Cuid.next(),
    var title: String,
    /** THE TIMES OF INDIA | ET DIGITAL */
    var source: String,
    var category: String,
    var imageUrl: String,
    var readTimeMinutes: Int,
    var url: String,
    var concernTag: String? = null,
    var sortOrder: Int = 0,
) {
    lateinit var publishedAt: Instant

    @PrePersist
    fun onInsert() {
        if (!this::publishedAt.isInitialized) publishedAt = nowMillis()
    }
}

/** Prisma `Reel`: instructor short videos (Home rail 4, Yoga page). */
@Entity
@Table(name = "Reel")
@DynamicUpdate
class ReelEntity(
    @Id var id: String = Cuid.next(),
    var instructorId: String,
    var thumbnailUrl: String,
    var playbackUrl: String,
    var durationSeconds: Int,
    var sortOrder: Int = 0,
)

/** Prisma `UserQuote`. */
@Entity
@Table(name = "UserQuote")
@DynamicUpdate
class UserQuoteEntity(
    @Id var id: String = Cuid.next(),
    var quote: String,
    var author: String,
    var role: String,
    var sortOrder: Int = 0,
)

/** Prisma `PromoCampaign` (§6.2). `action` is a FeedAction object, stored as jsonb. */
@Entity
@Table(name = "PromoCampaign")
@DynamicUpdate
class PromoCampaignEntity(
    @Id var id: String = Cuid.next(),
    var campaignId: String,
    var title: String,
    var subtitle: String? = null,
    var ctaLabel: String,
    var imageUrl: String? = null,
    var backgroundColor: String? = null,
    action: JsonElement = JsonObject(emptyMap()),
    /** YOGA | MARATHON | DIET | OTHER */
    var sellsProduct: String,
    var active: Boolean = true,
    var startsAt: Instant? = null,
    var endsAt: Instant? = null,
    var priority: Int = 0,
) {
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action", columnDefinition = "jsonb")
    private var actionColumn: String = action.toString()

    var action: JsonElement
        get() = ServerJson.parseToJsonElement(actionColumn)
        set(value) {
            actionColumn = value.toString()
        }
}
