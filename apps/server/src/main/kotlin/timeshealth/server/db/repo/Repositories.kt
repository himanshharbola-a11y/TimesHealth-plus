package timeshealth.server.db.repo

import jakarta.persistence.EntityManager
import java.time.LocalDate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.support.JpaEntityInformation
import org.springframework.data.jpa.repository.support.SimpleJpaRepository
import org.springframework.data.repository.NoRepositoryBean
import org.springframework.transaction.annotation.Transactional
import timeshealth.server.db.entity.AppConfigEntity
import timeshealth.server.db.entity.ArticleEntity
import timeshealth.server.db.entity.AttendanceEntity
import timeshealth.server.db.entity.BibScanEntity
import timeshealth.server.db.entity.CompletedSessionEntity
import timeshealth.server.db.entity.DeviceTokenEntity
import timeshealth.server.db.entity.DietLeadEntity
import timeshealth.server.db.entity.IdentityConflictEntity
import timeshealth.server.db.entity.InstructorEntity
import timeshealth.server.db.entity.KitDeliveryEntity
import timeshealth.server.db.entity.LiveWorkshopEntity
import timeshealth.server.db.entity.MarathonEventEntity
import timeshealth.server.db.entity.MarathonRegistrationEntity
import timeshealth.server.db.entity.NotificationLogEntity
import timeshealth.server.db.entity.OrderEntity
import timeshealth.server.db.entity.PromoCampaignEntity
import timeshealth.server.db.entity.RaceDistanceOptionEntity
import timeshealth.server.db.entity.RaceFaqEntity
import timeshealth.server.db.entity.RaceResultEntity
import timeshealth.server.db.entity.ReelEntity
import timeshealth.server.db.entity.ReferralCreditEntity
import timeshealth.server.db.entity.ReferralEntity
import timeshealth.server.db.entity.RunRecordEntity
import timeshealth.server.db.entity.SavedSessionEntity
import timeshealth.server.db.entity.UserEntity
import timeshealth.server.db.entity.UserQuoteEntity
import timeshealth.server.db.entity.UserSessionKey
import timeshealth.server.db.entity.WorkshopRegistrationEntity
import timeshealth.server.db.entity.YogaBatchEntity
import timeshealth.server.db.entity.YogaCategoryEntity
import timeshealth.server.db.entity.YogaSessionEntity
import timeshealth.server.db.entity.YogaSubscriptionEntity

/**
 * Base for every repository: JpaRepository plus [persist].
 *
 * Every entity's id is assigned before insert (a cuid, a client UUID, a token), so Spring Data's
 * `save()` cannot tell a new row from an existing one and runs a SELECT before each INSERT. Use
 * [persist] to insert (`prisma.x.create`) and `save()`/dirty checking to update.
 */
@NoRepositoryBean
interface ThRepository<T : Any, ID : Any> : JpaRepository<T, ID> {
    /** INSERT this new row (at flush). Throws on a duplicate key, like prisma.create. */
    fun persist(entity: T): T

    /** [persist], then flush now, so a constraint violation surfaces here. */
    fun persistAndFlush(entity: T): T
}

class ThRepositoryImpl<T : Any, ID : Any>(
    entityInformation: JpaEntityInformation<T, *>,
    private val em: EntityManager,
) : SimpleJpaRepository<T, ID>(entityInformation, em), ThRepository<T, ID> {
    @Transactional
    override fun persist(entity: T): T {
        em.persist(entity)
        return entity
    }

    @Transactional
    override fun persistAndFlush(entity: T): T {
        em.persist(entity)
        em.flush()
        return entity
    }
}

// ── Identity ────────────────────────────────────────────────────────────────

interface UserRepository : ThRepository<UserEntity, String> {
    fun findByFirebaseUid(firebaseUid: String): UserEntity?
}

interface IdentityConflictRepository : ThRepository<IdentityConflictEntity, String> {
    fun findByNewUserId(newUserId: String): List<IdentityConflictEntity>
}

interface ReferralRepository : ThRepository<ReferralEntity, String> {
    fun findByUserId(userId: String): ReferralEntity?
    fun findByCode(code: String): ReferralEntity?
}

interface ReferralCreditRepository : ThRepository<ReferralCreditEntity, String>

// ── Yoga ────────────────────────────────────────────────────────────────────

interface YogaSubscriptionRepository : ThRepository<YogaSubscriptionEntity, String> {
    fun findByUserId(userId: String): YogaSubscriptionEntity?
}

interface InstructorRepository : ThRepository<InstructorEntity, String>

interface YogaBatchRepository : ThRepository<YogaBatchEntity, String>

interface YogaCategoryRepository : ThRepository<YogaCategoryEntity, String>

interface YogaSessionRepository : ThRepository<YogaSessionEntity, String>

interface AttendanceRepository : ThRepository<AttendanceEntity, String> {
    fun findByUserIdOrderByDateAsc(userId: String): List<AttendanceEntity>
    fun countByUserId(userId: String): Long
    fun existsByUserIdAndDate(userId: String, date: LocalDate): Boolean
}

interface SavedSessionRepository : ThRepository<SavedSessionEntity, UserSessionKey> {
    fun findByUserId(userId: String): List<SavedSessionEntity>
}

interface CompletedSessionRepository : ThRepository<CompletedSessionEntity, UserSessionKey> {
    fun findByUserId(userId: String): List<CompletedSessionEntity>
}

// ── Marathon ────────────────────────────────────────────────────────────────

interface MarathonEventRepository : ThRepository<MarathonEventEntity, String>

interface RaceDistanceOptionRepository : ThRepository<RaceDistanceOptionEntity, String> {
    fun findByEventIdOrderBySortOrderAsc(eventId: String): List<RaceDistanceOptionEntity>
}

/** A registration with the two event fields entitlements need (Prisma `include: { event: { select } }`). */
data class RegistrationWithEvent(
    val registration: MarathonRegistrationEntity,
    val eventStartsAt: java.time.Instant,
    val eventName: String,
)

interface MarathonRegistrationRepository : ThRepository<MarathonRegistrationEntity, String> {
    fun findByUserId(userId: String): List<MarathonRegistrationEntity>
    fun findByUserIdAndEventId(userId: String, eventId: String): MarathonRegistrationEntity?

    /** entitlements.ts: the user's registrations ordered by `event.startsAt asc`. */
    @Query(
        """
        select new timeshealth.server.db.repo.RegistrationWithEvent(r, e.startsAt, e.name)
        from MarathonRegistrationEntity r join MarathonEventEntity e on e.id = r.eventId
        where r.userId = :userId
        order by e.startsAt asc
        """,
    )
    fun findWithEventByUserId(userId: String): List<RegistrationWithEvent>
}

interface RaceResultRepository : ThRepository<RaceResultEntity, String> {
    fun findByRegistrationId(registrationId: String): RaceResultEntity?
}

interface KitDeliveryRepository : ThRepository<KitDeliveryEntity, String> {
    fun findByRegistrationId(registrationId: String): KitDeliveryEntity?
}

interface RaceFaqRepository : ThRepository<RaceFaqEntity, String> {
    fun findByEventIdOrderBySortOrderAsc(eventId: String): List<RaceFaqEntity>
}

interface BibScanRepository : ThRepository<BibScanEntity, String>

// ── Content, workshops, runs, diet ──────────────────────────────────────────

interface LiveWorkshopRepository : ThRepository<LiveWorkshopEntity, String>

interface WorkshopRegistrationRepository : ThRepository<WorkshopRegistrationEntity, String> {
    fun findByUserId(userId: String): List<WorkshopRegistrationEntity>
    fun countByWorkshopId(workshopId: String): Long
}

interface RunRecordRepository : ThRepository<RunRecordEntity, String> {
    fun findByUserId(userId: String): List<RunRecordEntity>
}

interface DietLeadRepository : ThRepository<DietLeadEntity, String>

interface ArticleRepository : ThRepository<ArticleEntity, String>

interface ReelRepository : ThRepository<ReelEntity, String>

interface UserQuoteRepository : ThRepository<UserQuoteEntity, String>

interface PromoCampaignRepository : ThRepository<PromoCampaignEntity, String>

// ── Commerce, push, platform ────────────────────────────────────────────────

interface OrderRepository : ThRepository<OrderEntity, String> {
    fun findByUserId(userId: String): List<OrderEntity>
}

interface DeviceTokenRepository : ThRepository<DeviceTokenEntity, String> {
    fun findByUserId(userId: String): List<DeviceTokenEntity>
}

interface NotificationLogRepository : ThRepository<NotificationLogEntity, String>

interface AppConfigRepository : ThRepository<AppConfigEntity, Int>
