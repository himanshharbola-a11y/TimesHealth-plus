package timeshealth.server.db.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import timeshealth.server.db.Cuid
import timeshealth.server.json.nowMillis

/*
 * Entity conventions (every file in this package):
 *  - One entity per Prisma model, table and column names exactly as Prisma wrote them. The
 *    physical naming strategy is the identity and every identifier is quoted (application.yml),
 *    so a Kotlin property `firebaseUid` IS the column "firebaseUid".
 *  - Foreign keys are plain scalar columns (`userId: String`), not JPA associations. The cascades
 *    Prisma declared (ON DELETE CASCADE / SET NULL) live in the database (V1__baseline.sql), so
 *    deleting a User row removes or detaches its data exactly as `prisma.user.delete` did. Join in
 *    JPQL (`join MarathonEventEntity e on e.id = r.eventId`) or SQL where a route needs to.
 *  - Ids default to a Prisma-format cuid ([Cuid]). Insert new rows with `repository.persist(x)`
 *    (ThRepository), not `save`, which would SELECT first because the id is already set.
 *  - `@updatedAt` columns are set on every insert/update by the entity callbacks, as Prisma does.
 *    A raw SQL UPDATE must set "updatedAt" itself (Prisma's updateMany does).
 *  - `@default(now())` columns are filled on insert when not set.
 *  - `@DynamicUpdate`: an UPDATE writes only the changed columns, like `prisma.x.update`.
 *  - Prisma `String[]` columns are nullable `text[]`; Prisma reads NULL as `[]`, and so do the
 *    List views here.
 */

/** Prisma `User`. */
@Entity
@Table(name = "User")
@DynamicUpdate
class UserEntity(
    @Id var id: String = Cuid.next(),
    /** The identity provider's subject (Firebase UID today). The only link to auth. */
    var firebaseUid: String,
    /** Identifiers asserted by the login provider (or our own import). resolveUser matches on these. */
    var email: String? = null,
    var phone: String? = null,
    /** What the user typed in onboarding/profile. Contact data only, never identity. */
    var contactEmail: String? = null,
    var contactPhone: String? = null,
    var name: String? = null,
    var dob: Instant? = null,
    var gender: String? = null,
    var healthGoal: String? = null,
    var concern: String? = null,
    var units: String = "METRIC",
    var locale: String = "en-IN",
    var onboardingCompleted: Boolean = false,
    var profileCompletion: Int = 0,
    var referredByCode: String? = null,
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

    /** A detached copy (for request-time snapshots, like Node's `req.user`). */
    fun copy(): UserEntity = UserEntity(
        id, firebaseUid, email, phone, contactEmail, contactPhone, name, dob, gender, healthGoal, concern,
        units, locale, onboardingCompleted, profileCompletion, referredByCode,
    ).also { c ->
        if (this::createdAt.isInitialized) c.createdAt = createdAt
        if (this::updatedAt.isInitialized) c.updatedAt = updatedAt
    }
}

/**
 * Prisma `IdentityConflict`: a login that matched existing records which could not be merged
 * safely. A fresh account was created; support merges by hand (PRD §5).
 */
@Entity
@Table(name = "IdentityConflict")
@DynamicUpdate
class IdentityConflictEntity(
    @Id var id: String = Cuid.next(),
    var newUserId: String,
    matchedUserIds: List<String> = emptyList(),
    /** MULTIPLE_UNCLAIMED | ALREADY_CLAIMED */
    var reason: String,
    var resolvedAt: Instant? = null,
) {
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "matchedUserIds", columnDefinition = "text[]")
    private var matchedUserIdsColumn: Array<String>? = matchedUserIds.toTypedArray()

    var matchedUserIds: List<String>
        get() = matchedUserIdsColumn?.toList() ?: emptyList()
        set(value) {
            matchedUserIdsColumn = value.toTypedArray()
        }

    @Column(updatable = false)
    lateinit var createdAt: Instant

    @PrePersist
    fun onInsert() {
        if (!this::createdAt.isInitialized) createdAt = nowMillis()
    }
}

/** Prisma `Referral` (Refer & Win, PRD §8.3). Counts only completed paid registrations. */
@Entity
@Table(name = "Referral")
@DynamicUpdate
class ReferralEntity(
    @Id var id: String = Cuid.next(),
    var userId: String,
    var code: String,
    var confirmedReferrals: Int = 0,
    var luckyDrawEntries: Int = 0,
    var guaranteedUpgradeUnlocked: Boolean = false,
    /** Set when the earned free Premium upgrade is used — claimable once. */
    var upgradeClaimedAt: Instant? = null,
) {
    @Column(updatable = false)
    lateinit var createdAt: Instant

    @PrePersist
    fun onInsert() {
        if (!this::createdAt.isInitialized) createdAt = nowMillis()
    }
}

/**
 * Prisma `ReferralCredit`: one friend's paid registration credited to one referrer. The unique
 * (referralId, referredUserId) pair stops the same friend counting twice.
 */
@Entity
@Table(name = "ReferralCredit")
class ReferralCreditEntity(
    @Id var id: String = Cuid.next(),
    var referralId: String,
    var referredUserId: String,
    var orderId: String,
) {
    @Column(updatable = false)
    lateinit var createdAt: Instant

    @PrePersist
    fun onInsert() {
        if (!this::createdAt.isInitialized) createdAt = nowMillis()
    }
}
