package timeshealth.server.session

import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import timeshealth.app.core.domain.istDateOnly
import timeshealth.app.core.model.Entitlements
import timeshealth.app.core.model.MarathonEntitlement
import timeshealth.app.core.model.PersonaInfo
import timeshealth.app.core.model.RaceLifecycleStatus
import timeshealth.app.core.model.RaceTier
import timeshealth.app.core.model.UserPersona
import timeshealth.app.core.model.YogaEntitlement
import timeshealth.app.core.model.YogaStatus
import timeshealth.server.db.repo.MarathonRegistrationRepository
import timeshealth.server.db.repo.YogaSubscriptionRepository
import timeshealth.server.json.toIsoString

data class ResolvedEntitlements(val entitlements: Entitlements, val persona: PersonaInfo)

/**
 * Entitlement resolution — PRD §2, §3 (port of services/entitlements.ts).
 *
 * Three independent flags, never a tier. The single source of truth for every gated surface: the
 * client renders against the result and never computes entitlement itself.
 *
 * `active` is derived from the expiry timestamp at read time, not trusted from the stored status
 * column: a subscription that lapsed overnight reads as inactive on the next request.
 */
@Service
class EntitlementService(
    private val subscriptions: YogaSubscriptionRepository,
    private val registrations: MarathonRegistrationRepository,
) {
    @Transactional(readOnly = true)
    fun resolve(userId: String, now: Instant): ResolvedEntitlements {
        val subscription = subscriptions.findByUserId(userId)
        val regs = registrations.findWithEventByUserId(userId)

        val yoga = subscription?.let { s ->
            val notExpired = s.expiresAt > now
            val active = s.status == "ACTIVE" && notExpired
            YogaEntitlement(
                active = active,
                // A row still marked ACTIVE past its expiry reads as EXPIRED.
                status = when {
                    active -> YogaStatus.ACTIVE
                    s.status == "CANCELLED" -> YogaStatus.CANCELLED
                    else -> YogaStatus.EXPIRED
                },
                planId = s.planId,
                planLabel = s.planLabel,
                startedAt = s.startedAt.toIsoString(),
                expiresAt = s.expiresAt.toIsoString(),
                autoRenews = s.autoRenews,
                reminderSlotId = s.reminderSlotId,
            )
        }

        val marathon = regs.map { (r, eventStartsAt, eventName) ->
            MarathonEntitlement(
                eventId = r.eventId,
                eventName = eventName,
                registrationRef = r.registrationRef,
                tier = if (r.tier == "PREMIUM") RaceTier.PREMIUM else RaceTier.CLASSIC,
                category = r.category,
                bibNumber = r.bibNumber,
                status = raceStatus(eventStartsAt, r.status, now),
                registeredAt = r.registeredAt.toIsoString(),
            )
        }

        val hasYoga = yoga?.active == true
        // §8.2/§6.1 only surface races that have not finished; a completed race still counts as an
        // entitlement because its result screen stays reachable.
        val hasMarathon = marathon.isNotEmpty()

        val persona = when {
            hasYoga && hasMarathon -> UserPersona.BOTH
            hasYoga -> UserPersona.YOGA_SUBSCRIBER
            hasMarathon -> UserPersona.MARATHON_REGISTRANT
            yoga != null && !yoga.active -> UserPersona.YOGA_EXPIRED
            else -> UserPersona.FREE
        }

        return ResolvedEntitlements(
            entitlements = Entitlements(yoga = yoga, marathon = marathon, diet = null),
            persona = PersonaInfo(persona = persona, hasYoga = hasYoga, hasMarathon = hasMarathon, label = PERSONA_LABELS.getValue(persona)),
        )
    }

    companion object {
        val PERSONA_LABELS = mapOf(
            UserPersona.FREE to "Free Member",
            UserPersona.YOGA_SUBSCRIBER to "Yoga Subscriber",
            UserPersona.MARATHON_REGISTRANT to "Marathon Registered",
            UserPersona.BOTH to "Yoga + Marathon",
            UserPersona.YOGA_EXPIRED to "Previous Member",
        )

        /**
         * The race's lifecycle by the IST calendar. RACE_DAY spans the whole IST race day;
         * COMPLETED begins the next IST midnight. (UTC day boundaries made a race "COMPLETED" at
         * 05:30 IST on race morning, hiding the bib QR while runners stood at the start line.)
         */
        fun raceStatus(startsAt: Instant, stored: String, now: Instant): RaceLifecycleStatus {
            if (stored == "COMPLETED") return RaceLifecycleStatus.COMPLETED
            val dayStart = istDateOnly(startsAt)
            val dayEnd = dayStart.plus(Duration.ofHours(24))
            if (now >= dayEnd) return RaceLifecycleStatus.COMPLETED
            if (now >= dayStart) return RaceLifecycleStatus.RACE_DAY
            return RaceLifecycleStatus.UPCOMING
        }

        /** PRD §5: an expired subscriber is "treated as free for gating". */
        fun canAccessLiveYoga(e: Entitlements): Boolean = e.yoga?.active == true
        fun canAccessRecordings(e: Entitlements): Boolean = e.yoga?.active == true
        fun canAccessYogaTracker(e: Entitlements): Boolean = e.yoga?.active == true

        /** …but Home shows a re-subscribe prompt rather than a cold sell. */
        fun shouldShowRenewPrompt(e: Entitlements): Boolean = e.yoga != null && e.yoga?.active != true

        /** §8.6 — the run tracker is free for everyone. Here for explicitness. */
        fun canUseRunTracker(): Boolean = true
    }
}
