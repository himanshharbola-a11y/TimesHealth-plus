package timeshealth.server.session

import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import timeshealth.app.core.domain.normalizeEmail
import timeshealth.app.core.domain.normalizePhone
import timeshealth.app.core.model.AppConfigResponse
import timeshealth.app.core.model.DeleteAccountResponse
import timeshealth.app.core.model.Maintenance
import timeshealth.app.core.model.SessionResponse
import timeshealth.app.core.model.UserProfile
import timeshealth.server.db.AppConfigService
import timeshealth.server.db.entity.UserEntity
import timeshealth.server.db.repo.UserRepository
import timeshealth.server.error.ApiException
import timeshealth.server.identity.IdentityProvider
import timeshealth.server.json.RawBody
import timeshealth.server.json.toIsoString
import timeshealth.server.security.PersonaTokens
import timeshealth.server.validation.ZodObject

/**
 * The fields a PATCH /profile or POST /onboarding/step writes — Prisma's `data` object. Null means
 * "not in data" (left unchanged); [dob] is the only date.
 */
private class UserChanges {
    var name: String? = null
    var contactEmail: String? = null
    var contactEmailSet = false
    var contactPhone: String? = null
    var dob: Instant? = null
    var gender: String? = null
    var units: String? = null
    var locale: String? = null
    var healthGoal: String? = null
    var concern: String? = null

    fun applyTo(u: UserEntity) {
        name?.let { u.name = it }
        if (contactEmailSet) u.contactEmail = contactEmail
        contactPhone?.let { u.contactPhone = it }
        dob?.let { u.dob = it }
        gender?.let { u.gender = it }
        units?.let { u.units = it }
        locale?.let { u.locale = it }
        healthGoal?.let { u.healthGoal = it }
        concern?.let { u.concern = it }
    }
}

/** Port of routes/session.ts (everything except GET /home, which belongs to the feed port). */
@Service
class SessionService(
    private val users: UserRepository,
    private val entitlements: EntitlementService,
    private val appConfig: AppConfigService,
    private val identityProvider: IdentityProvider,
    private val jdbc: JdbcClient,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(SessionService::class.java)

    /**
     * GET /config. Public launch config: the app checks this before anything else, so the
     * force-update gate and maintenance switch work even for a version whose sign-in is broken.
     */
    fun config(): AppConfigResponse {
        val config = appConfig.get()
        return AppConfigResponse(
            minSupportedAppVersion = config.minSupportedAppVersion,
            maintenance = Maintenance(active = config.maintenanceActive, message = config.maintenanceMessage),
            serverTime = clock.instant().toIsoString(),
        )
    }

    /**
     * GET /session. Called once on launch: identity, entitlement and the first screen.
     * PRD §5: a returning subscriber never sees onboarding again.
     */
    @Transactional
    fun session(user: UserEntity): SessionResponse {
        val now = clock.instant()
        val resolved = entitlements.resolve(user.id, now)
        val config = appConfig.get()

        // §5: a known account — ANY yoga subscription (active OR lapsed) or race registration —
        // never sees onboarding. Keying this on an ACTIVE plan sent a lapsed subscriber back
        // through onboarding the day their plan expired.
        val knownAccount = resolved.entitlements.yoga != null || resolved.entitlements.marathon.isNotEmpty()
        val needsOnboarding = !user.onboardingCompleted && !knownAccount
        // Persist it, so the answer can never regress once the account is known.
        if (knownAccount && !user.onboardingCompleted) {
            managed(user).onboardingCompleted = true
        }

        return SessionResponse(
            // The request-time row, as Node returns req.user (before the update above).
            profile = user.toProfileDto(),
            entitlements = resolved.entitlements,
            persona = resolved.persona,
            needsOnboarding = needsOnboarding,
            serverTime = now.toIsoString(),
            minSupportedAppVersion = config.minSupportedAppVersion,
            maintenance = Maintenance(active = config.maintenanceActive, message = config.maintenanceMessage),
        )
    }

    /**
     * POST /onboarding/step. PRD §5: every step is skippable and progress is saved as it goes, so a
     * partial drop still leaves usable lead data. Hence one call per step.
     */
    @Transactional
    fun onboardingStep(user: UserEntity, body: RawBody): UserProfile {
        val z = ZodObject(body)
        val step = z.numberLiteral("step", setOf(1, 2, 3, 4))
        val name = z.string("name", trim = true, min = 1, max = 80)
        val email = z.string("email", trim = true, email = true, max = 160)
        val phone = z.string("phone", trim = true, min = 8, max = 20)
        val healthGoal = z.enum("healthGoal", ProfileEnums.HEALTH_GOALS)
        val concern = z.enum("concern", ProfileEnums.CONCERNS)
        if (!z.success) throw ApiException(400, "INVALID_BODY", "Invalid onboarding payload")

        val data = UserChanges()
        if (name != null) data.name = name
        // Step 2 ("Email + mobile") is lead and contact data, stored apart from the login
        // identifiers so typing someone else's number can never block — or claim — that person's
        // account merge. A field that is already the sign-in identifier is shown read-only;
        // anything sent for it is ignored rather than stored unseen.
        if (email != null && user.email == null) {
            data.contactEmail = normalizeEmail(email)
            data.contactEmailSet = true
        }
        if (phone != null && user.phone == null) {
            data.contactPhone = normalizePhone(phone)
                ?: throw ApiException(400, "INVALID_PHONE", "Enter a valid mobile number")
        }
        if (healthGoal != null) data.healthGoal = healthGoal
        if (concern != null) data.concern = concern

        val merged = user.copy().also(data::applyTo)
        val target = managed(user)
        data.applyTo(target)
        target.profileCompletion = computeCompletion(merged)
        if (step == 4) target.onboardingCompleted = true
        users.flush()
        return target.toProfileDto()
    }

    /** POST /onboarding/skip. Skipping everything still lands on Home with a generic feed. */
    @Transactional
    fun skipOnboarding(user: UserEntity): UserProfile {
        val target = managed(user)
        target.onboardingCompleted = true
        users.flush()
        return target.toProfileDto()
    }

    /** PATCH /profile (§10 personal details; §5's personalisation is not one-shot). */
    @Transactional
    fun updateProfile(user: UserEntity, body: RawBody): UserProfile {
        val z = ZodObject(body)
        val name = z.string("name", trim = true, min = 1, max = 80)
        val email = z.string("email", trim = true, email = true, max = 160)
        val phone = z.string("phone", trim = true, min = 8, max = 20)
        // An age the app can't sensibly serve (or a date in the future) is a typo.
        val dob = z.string("dob", datetime = true, refine = { v ->
            val at = ZodObject.parseZodDatetime(v) ?: return@string false
            val age = (clock.millis() - at.toEpochMilli()) / (365.25 * 86_400_000)
            age >= 13 && age <= 100
        })
        val gender = z.enum("gender", ProfileEnums.GENDERS)
        val units = z.enum("units", ProfileEnums.UNITS)
        val locale = z.string("locale", max = 10)
        val healthGoal = z.enum("healthGoal", ProfileEnums.HEALTH_GOALS)
        val concern = z.enum("concern", ProfileEnums.CONCERNS)
        if (!z.success) throw ApiException(400, "INVALID_BODY", "Invalid profile payload")

        val data = UserChanges().apply {
            this.name = name
            this.gender = gender
            this.units = units
            this.locale = locale
            this.healthGoal = healthGoal
            this.concern = concern
        }
        // The sign-in email/phone belong to the identity provider: an edit here would only write
        // a contact field the profile never shows. Re-sending the same value is fine; changing it
        // is refused with a reason.
        if (email != null) {
            val normalized = normalizeEmail(email)
            if (user.email != null) {
                if (normalized != user.email) throw loginLocked("email")
            } else {
                data.contactEmail = normalized
                data.contactEmailSet = true
            }
        }
        if (phone != null) {
            val normalized = normalizePhone(phone)
                ?: throw ApiException(400, "INVALID_PHONE", "Enter a valid mobile number")
            if (user.phone != null) {
                if (normalized != user.phone) throw loginLocked("phone")
            } else {
                data.contactPhone = normalized
            }
        }
        if (dob != null) data.dob = ZodObject.parseZodDatetime(dob)

        val merged = user.copy().also(data::applyTo)
        val target = managed(user)
        data.applyTo(target)
        target.profileCompletion = computeCompletion(merged)
        users.flush()
        return target.toProfileDto()
    }

    private fun loginLocked(field: String) = ApiException(
        409,
        "LOGIN_IDENTIFIER",
        "This is the ${if (field == "email") "email" else "mobile number"} you sign in with, so it can’t be changed here.",
    )

    /**
     * DELETE /account — Google Play requirement and DPDP right to erasure.
     *
     * Two stores hold this person's data and BOTH must be erased: our database (the foreign keys
     * cascade attendance, runs, registrations, devices…; orders are kept with userId set NULL for
     * tax/audit and refunds) and their identity-provider record. Deleting only our row would leave
     * the login alive, and the next request would silently recreate an empty account.
     */
    fun deleteAccount(user: UserEntity): DeleteAccountResponse {
        val uid = user.firebaseUid
        deleteUserRow(user.id)

        // Persona and imported-legacy users have no real provider record.
        val isRealUser = !uid.contains('|') && !uid.startsWith(PersonaTokens.LEGACY_UID_PREFIX) && !uid.startsWith("qa_")
        var authRecordDeleted = false
        if (isRealUser) {
            try {
                authRecordDeleted = identityProvider.deleteAccount(uid)
            } catch (e: Exception) {
                // Our data is already gone. Log loudly so support can finish the job.
                log.error("account deleted but the identity record was not err={} uid={}", e.toString(), uid)
            }
        }
        return DeleteAccountResponse(deleted = true, authRecordDeleted = authRecordDeleted)
    }

    /** `prisma.user.delete`: committed before the provider call; the database cascades. */
    @Transactional
    fun deleteUserRow(userId: String) {
        val deleted = jdbc.sql("""DELETE FROM "User" WHERE "id" = :id""").param("id", userId).update()
        check(deleted == 1) { "Record to delete does not exist." }
    }

    /** The current row, managed in this transaction (Prisma's update is by id, on the live row). */
    private fun managed(user: UserEntity): UserEntity =
        users.findById(user.id).orElseThrow { IllegalStateException("Record to update not found.") }
}
