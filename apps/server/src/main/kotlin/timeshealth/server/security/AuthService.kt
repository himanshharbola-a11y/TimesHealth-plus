package timeshealth.server.security

import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import timeshealth.app.core.domain.normalizeEmail
import timeshealth.app.core.domain.normalizePhone
import timeshealth.server.config.ServerEnv
import timeshealth.server.db.entity.IdentityConflictEntity
import timeshealth.server.db.entity.UserEntity
import timeshealth.server.db.repo.IdentityConflictRepository
import timeshealth.server.db.repo.UserRepository
import timeshealth.server.identity.IdentityProvider
import timeshealth.server.identity.TokenRejected
import timeshealth.server.identity.VerifiedIdentity

/**
 * QA persona tokens (auth.ts): "uid|email|phone", minted by anyone, so tightly confined.
 */
object PersonaTokens {
    /**
     * Placeholder UID prefix for customers imported from the existing yoga and marathon systems
     * who have never signed in. Only records carrying it may be adopted by a first app login.
     */
    const val LEGACY_UID_PREFIX = "legacy:"

    /**
     * Persona identities are confined to a QA namespace: a "qa_" uid, an email on the reserved
     * .test domain, a number in the QA range. resolveUser adopts accounts by verified email/phone,
     * so without this a QA server would let a stranger sign in as any real user just by putting
     * that user's email or number in the token.
     */
    private val PERSONA_UID = Regex("^qa_[\\w-]{1,80}$")
    private val PERSONA_EMAIL = Regex("^[\\w.+-]{1,64}@th\\.test$", RegexOption.IGNORE_CASE)
    private val PERSONA_PHONE = Regex("^\\+9190000000\\d{2}$")

    /**
     * A real provider token is a JWT: three dot-separated segments and never a "|". So the two can
     * be told apart without trying one and falling back to the other.
     */
    fun isPersonaToken(token: String): Boolean = token.contains('|') && token.split('.').size != 3

    /**
     * Allowed when explicitly enabled, or in pure local dev with no real provider configured — but
     * never on a server exposed through a public tunnel without that opt-in, and never in
     * production (ServerEnv refuses ALLOW_DEV_TOKENS there; this is the second line).
     */
    fun allowed(env: ServerEnv, providerConfigured: Boolean): Boolean =
        !env.isProd && (env.allowDevTokens || (!providerConfigured && !env.publicTunnel))

    /** Parses and confines a persona token. Throws [TokenRejected] exactly where auth.ts does. */
    fun identityOf(token: String): VerifiedIdentity {
        val parts = token.split('|')
        val uid = parts.getOrElse(0) { "" }
        val email = parts.getOrElse(1) { "" }
        val phone = parts.getOrElse(2) { "" }
        if (!PERSONA_UID.matches(uid)) throw TokenRejected("Persona token must be \"qa_uid|email|phone\"")
        if ((email.isNotEmpty() && !PERSONA_EMAIL.matches(email)) || (phone.isNotEmpty() && !PERSONA_PHONE.matches(phone))) {
            throw TokenRejected("Persona tokens are limited to QA identities")
        }
        return VerifiedIdentity(uid = uid, email = email.ifEmpty { null }, emailVerified = true, phone = phone.ifEmpty { null }, name = null)
    }
}

/** Port of auth.ts `verifyToken` and `resolveUser`. */
@Service
class AuthService(
    private val env: ServerEnv,
    private val provider: IdentityProvider,
    private val users: UserRepository,
    private val conflicts: IdentityConflictRepository,
    private val em: EntityManager,
) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)

    /** Persona token or provider token → identity. [TokenRejected] = 401; anything else = 503. */
    fun verifyToken(token: String): VerifiedIdentity {
        if (PersonaTokens.isPersonaToken(token)) {
            if (!PersonaTokens.allowed(env, provider.configured())) {
                throw TokenRejected("Persona tokens are not accepted on this server")
            }
            return PersonaTokens.identityOf(token)
        }
        // Firebase today, Times SSO tomorrow — the identity package is the seam.
        return provider.verify(token)
    }

    /**
     * Resolves a verified identity to exactly one User row.
     *
     * PRD §5: "Existing web user logs in with a different identifier (phone on app, email on web)
     * must resolve to one account."
     *
     * Order matters. Match on the provider UID first: the only identifier we issued ourselves.
     * Email and phone are claims about the world and can collide, so they are only used to ADOPT an
     * account that has no UID yet — never to take over one that already belongs to another UID.
     */
    @Transactional
    fun resolveUser(identity: VerifiedIdentity): UserEntity {
        users.findByFirebaseUid(identity.uid)?.let { return it }

        // Normalised so "Ravi@Gmail.com" / "9876543210" match "ravi@gmail.com" / "+919876543210".
        // An UNVERIFIED email is never used to match: anyone can sign up with someone else's
        // address, and adopting on it would hand them that person's subscription, race and bib.
        val email = normalizeEmail(identity.email)
        val matchEmail = if (identity.emailVerified) email else null
        val phone = normalizePhone(identity.phone)

        var conflict: Pair<List<String>, String>? = null

        if (matchEmail != null || phone != null) {
            val candidates = findCandidates(matchEmail, phone)

            // Imported records (existing customers who never opened the app) carry a placeholder
            // UID. Only those may be adopted.
            val unclaimed = candidates.filter { it.firebaseUid.startsWith(PersonaTokens.LEGACY_UID_PREFIX) }
            val claimed = candidates.filterNot { it.firebaseUid.startsWith(PersonaTokens.LEGACY_UID_PREFIX) }

            // Exactly one unclaimed match and nothing already claimed → link it. This is the
            // migration path that keeps a web subscriber's streak intact.
            if (unclaimed.size == 1 && claimed.isEmpty()) {
                val target = unclaimed[0]
                target.firebaseUid = identity.uid
                target.email = target.email ?: matchEmail
                target.phone = target.phone ?: phone
                target.name = target.name ?: identity.name
                // A known (imported) customer never sees onboarding (§5).
                target.onboardingCompleted = true
                em.flush()
                return target
            }

            // Ambiguous — several records match, or one already belongs to a different login. Do
            // NOT guess: a fresh account is recoverable, merging the wrong two is not. But never
            // SILENTLY — the clash is recorded for support to merge.
            if (candidates.isNotEmpty()) {
                conflict = candidates.map { it.id } to (if (unclaimed.size > 1) "MULTIPLE_UNCLAIMED" else "ALREADY_CLAIMED")
            }
        }

        val created = users.persistAndFlush(
            UserEntity(
                firebaseUid = identity.uid,
                // Login identifiers only when the provider vouches for them; an unverified email
                // is kept as contact data, never as an identity.
                email = matchEmail,
                contactEmail = if (identity.emailVerified) null else email,
                phone = phone,
                name = identity.name,
            ),
        )

        conflict?.let { (matched, reason) ->
            conflicts.persistAndFlush(IdentityConflictEntity(newUserId = created.id, matchedUserIds = matched, reason = reason))
            log.warn(
                "identity collision — new account created, recorded for support merge newUserId={} matched={} reason={}",
                created.id, matched.size, reason,
            )
        }
        return created
    }

    /** `prisma.user.findMany({ where: { OR: [{ email }, { phone }] }, take: 5 })`. */
    private fun findCandidates(email: String?, phone: String?): List<UserEntity> {
        val clauses = listOfNotNull(email?.let { "u.email = :email" }, phone?.let { "u.phone = :phone" })
        val query = em.createQuery("select u from UserEntity u where ${clauses.joinToString(" or ")}", UserEntity::class.java)
        email?.let { query.setParameter("email", it) }
        phone?.let { query.setParameter("phone", it) }
        return query.setMaxResults(5).resultList
    }
}
