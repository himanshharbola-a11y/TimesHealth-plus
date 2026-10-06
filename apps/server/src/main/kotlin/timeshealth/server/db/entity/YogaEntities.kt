package timeshealth.server.db.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import timeshealth.server.db.Cuid
import timeshealth.server.json.nowMillis

// Conventions: see IdentityEntities.kt.

/** Prisma `YogaSubscription`. `active` is derived from expiresAt at read time, never trusted. */
@Entity
@Table(name = "YogaSubscription")
@DynamicUpdate
class YogaSubscriptionEntity(
    @Id var id: String = Cuid.next(),
    var userId: String,
    var planId: String,
    var planLabel: String,
    /** ACTIVE | EXPIRED | CANCELLED */
    var status: String,
    var startedAt: Instant,
    var expiresAt: Instant,
    var autoRenews: Boolean = true,
    /** The batch chosen for reminders. The user may still join any batch (§7.1). */
    var reminderSlotId: String? = null,
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

/** Prisma `Instructor`. */
@Entity
@Table(name = "Instructor")
@DynamicUpdate
class InstructorEntity(
    @Id var id: String = Cuid.next(),
    var name: String,
    var title: String,
    var specialty: String,
    var bio: String,
    var experience: String,
    var avatarUrl: String,
    var rating: Double = 0.0,
    var handle: String? = null,
    var reelCount: Int = 0,
)

/** Prisma `YogaBatch`: one of the 8 daily live slots. `time` is "HH:mm" IST. */
@Entity
@Table(name = "YogaBatch")
@DynamicUpdate
class YogaBatchEntity(
    @Id var id: String = Cuid.next(),
    var title: String,
    var time: String,
    /** MORNING | EVENING — never sort on it (EVENING < MORNING); sort by time. */
    var period: String,
    var sortOrder: Int = 0,
    var instructorId: String,
)

/** Prisma `YogaCategory`. */
@Entity
@Table(name = "YogaCategory")
@DynamicUpdate
class YogaCategoryEntity(
    @Id var id: String = Cuid.next(),
    var name: String,
    var tagline: String,
    var bodyTargetSummary: String,
    var imageUrl: String,
    var bannerTheme: String = "NEUTRAL",
    var totalYogisJoined: Int = 0,
    var sortOrder: Int = 0,
    /** Maps an onboarding concern to this category for feed ordering (§6.3). */
    var concernTag: String? = null,
)

/** Prisma `YogaSession`: a recorded session. `mediaKey` is a storage key, signed per request. */
@Entity
@Table(name = "YogaSession")
@DynamicUpdate
class YogaSessionEntity(
    @Id var id: String = Cuid.next(),
    var categoryId: String,
    var title: String,
    var description: String,
    var imageUrl: String,
    var durationMinutes: Int,
    var level: String,
    var intensity: String,
    var caloriesBurned: Int = 0,
    /** §6.3: selected sessions open to everyone. */
    var isFree: Boolean = false,
    var bodyFocusTitle: String,
    targetBodyParts: List<String> = emptyList(),
    keyPoses: List<String> = emptyList(),
    var lifestyleImpact: String,
    var instructorId: String,
    var joinedCountTillDate: Int = 0,
    var todayActiveCount: Int = 0,
    var mediaKey: String? = null,
) {
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "targetBodyParts", columnDefinition = "text[]")
    private var targetBodyPartsColumn: Array<String>? = targetBodyParts.toTypedArray()

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "keyPoses", columnDefinition = "text[]")
    private var keyPosesColumn: Array<String>? = keyPoses.toTypedArray()

    var targetBodyParts: List<String>
        get() = targetBodyPartsColumn?.toList() ?: emptyList()
        set(value) {
            targetBodyPartsColumn = value.toTypedArray()
        }

    var keyPoses: List<String>
        get() = keyPosesColumn?.toList() ?: emptyList()
        set(value) {
            keyPosesColumn = value.toTypedArray()
        }
}

/**
 * Prisma `Attendance`: the single-source attendance ledger (PRD §7.1). UNIQUE(userId, date)
 * makes "two joins in one IST day = one mark" — insert with ON CONFLICT DO NOTHING (see PgSql).
 *
 * [date] is the IST CALENDAR day (a DATE column). Node writes `istCalendarDate(instant)`, UTC
 * midnight of the IST day, whose date part is that day: here simply `istDate(instant)`.
 */
@Entity
@Table(name = "Attendance")
class AttendanceEntity(
    @Id var id: String = Cuid.next(),
    var userId: String,
    var date: LocalDate,
    var batchId: String? = null,
    /** APP | WHATSAPP | WEB | MANUAL — for reconciliation, never for gating. */
    var source: String,
) {
    @Column(updatable = false)
    lateinit var createdAt: Instant

    @PrePersist
    fun onInsert() {
        if (!this::createdAt.isInitialized) createdAt = nowMillis()
    }
}

/** Composite key of SavedSession / CompletedSession (`@@id([userId, sessionId])`). */
data class UserSessionKey(var userId: String = "", var sessionId: String = "") : Serializable

/** Prisma `SavedSession`. */
@Entity
@Table(name = "SavedSession")
@IdClass(UserSessionKey::class)
class SavedSessionEntity(
    @Id var userId: String,
    @Id var sessionId: String,
) {
    @Column(updatable = false)
    lateinit var createdAt: Instant

    @PrePersist
    fun onInsert() {
        if (!this::createdAt.isInitialized) createdAt = nowMillis()
    }
}

/** Prisma `CompletedSession`. Completing a recording does NOT write attendance (§7.1). */
@Entity
@Table(name = "CompletedSession")
@IdClass(UserSessionKey::class)
class CompletedSessionEntity(
    @Id var userId: String,
    @Id var sessionId: String,
) {
    @Column(updatable = false)
    lateinit var createdAt: Instant

    @PrePersist
    fun onInsert() {
        if (!this::createdAt.isInitialized) createdAt = nowMillis()
    }
}
