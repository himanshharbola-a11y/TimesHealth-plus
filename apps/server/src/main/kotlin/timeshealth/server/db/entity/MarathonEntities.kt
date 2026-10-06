package timeshealth.server.db.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import timeshealth.server.db.Cuid
import timeshealth.server.json.ServerJson
import timeshealth.server.json.nowMillis

// Conventions: see IdentityEntities.kt.

/** Prisma `MarathonEvent`: one race edition. */
@Entity
@Table(name = "MarathonEvent")
@DynamicUpdate
class MarathonEventEntity(
    @Id var id: String = Cuid.next(),
    var name: String,
    var city: String,
    var venue: String,
    var imageUrl: String,
    var startsAt: Instant,
    var flagOffTime: String,
    var registrationOpen: Boolean = true,
    var rescheduledFrom: Instant? = null,
    var latitude: Double? = null,
    var longitude: Double? = null,
    var expoVenue: String? = null,
    var expoAddress: String? = null,
    var expoStartsAt: Instant? = null,
    var expoEndsAt: Instant? = null,
    /** Null: the API derives the window from expoStartsAt/EndsAt (formatIstWindow). */
    var expoPickupWindow: String? = null,
    var expoInstructions: String? = null,
    expoDocuments: List<String> = emptyList(),
) {
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "expoDocuments", columnDefinition = "text[]")
    private var expoDocumentsColumn: Array<String>? = expoDocuments.toTypedArray()

    var expoDocuments: List<String>
        get() = expoDocumentsColumn?.toList() ?: emptyList()
        set(value) {
            expoDocumentsColumn = value.toTypedArray()
        }
}

/** Prisma `RaceDistanceOption`. Money is integer paise. */
@Entity
@Table(name = "RaceDistanceOption")
@DynamicUpdate
class RaceDistanceOptionEntity(
    @Id var id: String = Cuid.next(),
    var eventId: String,
    /** 3K | 5K | 10K | 21K */
    var code: String,
    var label: String,
    var priceClassicPaise: Int,
    var pricePremiumPaise: Int,
    var wasPriceClassicPaise: Int? = null,
    var wasPricePremiumPaise: Int? = null,
    /** §8.3: sold out suppresses the upgrade banner rather than failing it. */
    var premiumSoldOut: Boolean = false,
    var registrationOpen: Boolean = true,
    var sortOrder: Int = 0,
)

/** Prisma `MarathonRegistration`. UNIQUE(userId, eventId); registrationRef is unique too. */
@Entity
@Table(name = "MarathonRegistration")
@DynamicUpdate
class MarathonRegistrationEntity(
    @Id var id: String = Cuid.next(),
    var userId: String,
    var eventId: String,
    var registrationRef: String,
    /** CLASSIC | PREMIUM */
    var tier: String,
    var category: String,
    var bibNumber: String? = null,
    var status: String = "UPCOMING",
    var tshirtSize: String? = null,
    var emergencyContactName: String? = null,
    var emergencyContactPhone: String? = null,
) {
    @Column(updatable = false)
    lateinit var registeredAt: Instant

    @PrePersist
    fun onInsert() {
        if (!this::registeredAt.isInitialized) registeredAt = nowMillis()
    }
}

/** Prisma `RaceResult` (§8.4). Rows exist only once the timing partner publishes. */
@Entity
@Table(name = "RaceResult")
@DynamicUpdate
class RaceResultEntity(
    @Id var id: String = Cuid.next(),
    var registrationId: String,
    var published: Boolean = false,
    var finishTime: String? = null,
    var chipTime: String? = null,
    var avgPace: String? = null,
    var overallRank: Int? = null,
    var ageGroupRank: Int? = null,
    var certificateUrl: String? = null,
    var medalStatus: String? = null,
    photoUrls: List<String>? = null,
) {
    /** jsonb, nullable. Kept as its JSON text; see [splits]. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "splits", columnDefinition = "jsonb")
    private var splitsColumn: String? = null

    /** Prisma `Json?`. A JSON `null` and SQL NULL both read as Kotlin null, as in Prisma. */
    var splits: JsonElement?
        get() = splitsColumn?.let(ServerJson::parseToJsonElement)?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        set(value) {
            splitsColumn = value?.toString()
        }

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "photoUrls", columnDefinition = "text[]")
    private var photoUrlsColumn: Array<String>? = photoUrls?.toTypedArray()

    var photoUrls: List<String>
        get() = photoUrlsColumn?.toList() ?: emptyList()
        set(value) {
            photoUrlsColumn = value.toTypedArray()
        }

    lateinit var updatedAt: Instant

    @PrePersist
    fun onInsert() {
        updatedAt = nowMillis()
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = nowMillis()
    }
}

/** Prisma `KitDelivery`: courier tracking (design addition). */
@Entity
@Table(name = "KitDelivery")
@DynamicUpdate
class KitDeliveryEntity(
    @Id var id: String = Cuid.next(),
    var registrationId: String,
    var status: String = "NOT_DISPATCHED",
    var courierName: String? = null,
    var trackingRef: String? = null,
    var expectedBy: Instant? = null,
)

/** Prisma `RaceFaq`. */
@Entity
@Table(name = "RaceFaq")
@DynamicUpdate
class RaceFaqEntity(
    @Id var id: String = Cuid.next(),
    var eventId: String,
    var question: String,
    var answer: String,
    var sortOrder: Int = 0,
)

/** Prisma `BibScan`: each expo-scanner verification of a digital bib (§8.3). */
@Entity
@Table(name = "BibScan")
class BibScanEntity(
    @Id var id: String = Cuid.next(),
    var registrationId: String,
    var scannerId: String,
    /** ONLINE (60s token) | OFFLINE (7-day signed payload) */
    var tokenKind: String,
) {
    @Column(updatable = false)
    lateinit var scannedAt: Instant

    @PrePersist
    fun onInsert() {
        if (!this::scannedAt.isInitialized) scannedAt = nowMillis()
    }
}
