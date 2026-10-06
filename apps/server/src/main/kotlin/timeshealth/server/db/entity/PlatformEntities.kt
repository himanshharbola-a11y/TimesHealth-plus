package timeshealth.server.db.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate
import timeshealth.server.db.Cuid
import timeshealth.server.json.nowMillis

// Conventions: see IdentityEntities.kt.

/**
 * Prisma `Order`. Money is integer paise, always computed server-side. `userId` is set NULL
 * (ON DELETE SET NULL) when the buyer deletes their account: the payment record is kept for
 * tax/audit and refunds, detached from the person. "Order" is a reserved word; the quoting
 * configured in application.yml handles it.
 */
@Entity
@Table(name = "Order")
@DynamicUpdate
class OrderEntity(
    @Id var id: String = Cuid.next(),
    var userId: String? = null,
    var productType: String,
    var productId: String,
    var eventId: String? = null,
    var category: String? = null,
    var tier: String? = null,
    var amountPaise: Int,
    var currency: String = "INR",
    var gateway: String = "STUB",
    var gatewayOrderId: String? = null,
    var gatewayPaymentId: String? = null,
    /** CREATED | PENDING | PAID | FAILED | PAID_NOT_GRANTED */
    var status: String = "CREATED",
    /** Granted on the webhook, never on client confirmation. */
    var entitlementGranted: Boolean = false,
    /** Why a PAID order could not be fulfilled; ops refunds. */
    var settlementNote: String? = null,
) {
    @Column(updatable = false)
    lateinit var createdAt: Instant
    lateinit var updatedAt: Instant

    @PrePersist
    fun onInsert() {
        val now = nowMillis()
        if (!this::createdAt.isInitialized) createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = nowMillis()
    }
}

/** Prisma `DeviceToken`: one row per device; the push token is the key. */
@Entity
@Table(name = "DeviceToken")
@DynamicUpdate
class DeviceTokenEntity(
    @Id var token: String,
    var userId: String,
    /** ANDROID | IOS */
    var platform: String,
    /** EXPO | FCM | APNS */
    var provider: String,
) {
    @Column(updatable = false)
    lateinit var createdAt: Instant
    lateinit var lastSeenAt: Instant

    @PrePersist
    fun onInsert() {
        val now = nowMillis()
        if (!this::createdAt.isInitialized) createdAt = now
        if (!this::lastSeenAt.isInitialized) lastSeenAt = now
    }
}

/**
 * Prisma `NotificationLog`: one row per notification attempted. UNIQUE(userId, kind, dedupeKey)
 * is inserted BEFORE sending, so retries and overlapping scheduler runs never re-send — use
 * INSERT … ON CONFLICT DO NOTHING (PgSql), not save().
 */
@Entity
@Table(name = "NotificationLog")
@DynamicUpdate
class NotificationLogEntity(
    @Id var id: String = Cuid.next(),
    var userId: String,
    var kind: String,
    var dedupeKey: String,
    var title: String? = null,
    var body: String? = null,
    var route: String? = null,
    /** 1 delivered, 0 not, -1 claimed by a sender (services/push.ts). */
    var delivered: Int = 0,
) {
    lateinit var sentAt: Instant

    @PrePersist
    fun onInsert() {
        if (!this::sentAt.isInitialized) sentAt = nowMillis()
    }
}

/** Prisma `AppConfig`: the single row (id 1) holding the update gate and maintenance switch. */
@Entity
@Table(name = "AppConfig")
@DynamicUpdate
class AppConfigEntity(
    @Id var id: Int = 1,
    var minSupportedAppVersion: String = "1.0.0",
    var maintenanceActive: Boolean = false,
    var maintenanceMessage: String? = null,
) {
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
