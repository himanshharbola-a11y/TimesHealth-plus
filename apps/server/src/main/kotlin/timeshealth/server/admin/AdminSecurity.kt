package timeshealth.server.admin

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import timeshealth.server.config.ServerEnv
import timeshealth.server.db.Cuid
import timeshealth.server.db.toDbTime
import timeshealth.server.error.ApiException
import timeshealth.server.json.nowMillis
import timeshealth.server.media.MediaSigning
import timeshealth.server.web.ClientIp

/** What a dashboard user may do. */
enum class AdminRole(val label: String) {
    /** Everything, including managing other admins. */
    OWNER("Owner"),

    /** All content: sections, videos, classes, marathons, promos. */
    EDITOR("Editor"),

    /** Read-only. */
    VIEWER("Viewer"),
    ;

    val canEditContent: Boolean get() = this != VIEWER
    val canManageAdmins: Boolean get() = this == OWNER

    companion object {
        fun of(value: String?): AdminRole? = entries.firstOrNull { it.name == value }
    }
}

/** A signed-in dashboard user. */
data class AdminPrincipal(val id: String, val email: String, val name: String, val role: AdminRole)

/**
 * PLUG-IN POINT: how dashboard users prove who they are.
 *
 * TODAY: [PasswordAdminAuthenticator], email + bcrypt password in the AdminUser table.
 *
 * MOVING TO COMPANY SSO: add an SSO login endpoint (an OIDC redirect + callback) that finds or
 * creates the AdminUser by email and calls [AdminSessions.issue], exactly as the password login
 * does. Roles, the session cookie, the audit log and every dashboard screen stay as they are.
 * Then remove the password form from the dashboard (or keep it for break-glass access).
 */
interface AdminAuthenticator {
    fun authenticate(email: String, password: String): AdminPrincipal?
}

@Component
class AdminUsers(private val jdbc: JdbcClient) {
    private val encoder = BCryptPasswordEncoder(12)

    fun hash(password: String): String = encoder.encode(password)

    fun matches(password: String, hash: String): Boolean = runCatching { encoder.matches(password, hash) }.getOrDefault(false)

    /** An ACTIVE admin by id (a deactivated admin's session stops working at once). */
    fun activeById(id: String): AdminPrincipal? =
        jdbc.sql("""SELECT "id", "email", "name", "role" FROM "AdminUser" WHERE "id" = :id AND "active"""")
            .param("id", id)
            .query { rs, _ -> principal(rs.getString("id"), rs.getString("email"), rs.getString("name"), rs.getString("role")) }
            .optional().orElse(null)

    data class Credentials(val principal: AdminPrincipal?, val hash: String)

    fun credentials(email: String): Credentials? =
        jdbc.sql("""SELECT * FROM "AdminUser" WHERE lower("email") = lower(:e) AND "active"""")
            .param("e", email.trim())
            .query { rs, _ ->
                Credentials(
                    principal(rs.getString("id"), rs.getString("email"), rs.getString("name"), rs.getString("role")),
                    rs.getString("passwordHash"),
                )
            }.optional().orElse(null)

    fun count(): Long = jdbc.sql("""SELECT count(*) FROM "AdminUser"""").query(Long::class.java).single()

    fun create(email: String, name: String, role: AdminRole, password: String): String {
        val id = Cuid.next()
        val now = nowMillis().toDbTime()
        jdbc.sql(
            """INSERT INTO "AdminUser" ("id", "email", "name", "role", "passwordHash", "active", "createdAt", "updatedAt")
               VALUES (:id, :email, :name, :role, :hash, true, :now, :now)""",
        ).param("id", id).param("email", email.trim().lowercase()).param("name", name.trim())
            .param("role", role.name).param("hash", hash(password)).param("now", now)
            .update()
        return id
    }

    fun touchLogin(id: String) {
        jdbc.sql("""UPDATE "AdminUser" SET "lastLoginAt" = :now WHERE "id" = :id""")
            .param("now", nowMillis().toDbTime()).param("id", id).update()
    }

    private fun principal(id: String, email: String, name: String, role: String): AdminPrincipal? =
        AdminRole.of(role)?.let { AdminPrincipal(id, email, name, it) }

    companion object {
        /** Minimum password rules for dashboard accounts. */
        fun passwordProblem(password: String): String? = when {
            password.length < 12 -> "Use at least 12 characters."
            password.length > 200 -> "Use at most 200 characters."
            password.isBlank() -> "Use a non-blank password."
            else -> null
        }
    }
}

@Component
class PasswordAdminAuthenticator(private val users: AdminUsers) : AdminAuthenticator {
    /** Compared against when the email is unknown, so timing doesn't reveal which emails exist. */
    private val decoyHash = users.hash("decoy-password-for-timing-only")

    override fun authenticate(email: String, password: String): AdminPrincipal? {
        val creds = users.credentials(email)
        val ok = users.matches(password, creds?.hash ?: decoyHash)
        return if (ok && creds != null) creds.principal else null
    }
}

/**
 * Stateless signed session tokens for the dashboard, carried in an HttpOnly, SameSite=Strict
 * cookie scoped to /admin: `v1.<adminId>.<expiresEpochSeconds>.<hmac>`. Every request re-checks
 * that the admin still exists and is active.
 */
@Component
class AdminSessions(private val env: ServerEnv, private val users: AdminUsers, private val clock: Clock) {

    fun issue(principal: AdminPrincipal, response: HttpServletResponse) {
        val expires = clock.instant().plus(TTL).epochSecond
        val token = "v1.${principal.id}.$expires.${sign(principal.id, expires)}"
        response.addCookie(cookie(token, TTL.seconds.toInt()))
    }

    fun clear(response: HttpServletResponse) = response.addCookie(cookie("", 0))

    fun resolve(request: HttpServletRequest): AdminPrincipal? {
        val token = request.cookies?.firstOrNull { it.name == COOKIE }?.value ?: return null
        val parts = token.split('.')
        if (parts.size != 4 || parts[0] != "v1") return null
        val (_, id, expiresRaw, sig) = parts
        val expires = expiresRaw.toLongOrNull() ?: return null
        if (expires <= clock.instant().epochSecond) return null
        val expected = sign(id, expires)
        if (!MessageDigest.isEqual(expected.toByteArray(), sig.toByteArray())) return null
        return users.activeById(id)
    }

    private fun sign(id: String, expires: Long) = MediaSigning.hmacHex(env.adminSessionSecret, "admin:$id:$expires")

    private fun cookie(value: String, maxAge: Int) = Cookie(COOKIE, value).apply {
        path = "/admin"
        isHttpOnly = true
        secure = env.isProd
        this.maxAge = maxAge
        setAttribute("SameSite", "Strict")
    }

    companion object {
        const val COOKIE = "th_admin"
        val TTL: Duration = Duration.ofHours(12)
    }
}

/**
 * Slows password guessing: after 20 failed sign-ins from one IP in 5 minutes, or 10 for one
 * email in 15 minutes, further attempts are refused until the window passes. Successful
 * sign-ins don't count.
 */
@Component
class AdminLoginThrottle(private val env: ServerEnv) {
    private val buckets = Caffeine.newBuilder().maximumSize(10_000).expireAfterAccess(Duration.ofHours(1)).build<String, Bucket>()

    fun allow(request: HttpServletRequest, email: String): Boolean =
        keys(request, email).all { (key, limit) -> bucket(key, limit).availableTokens > 0 }

    fun recordFailure(request: HttpServletRequest, email: String) =
        keys(request, email).forEach { (key, limit) -> bucket(key, limit).tryConsume(1) }

    private fun keys(request: HttpServletRequest, email: String) = listOf(
        "ip:${ClientIp.of(request, env.trustProxyHops)}" to (20L to Duration.ofMinutes(5)),
        "email:${email.trim().lowercase()}" to (10L to Duration.ofMinutes(15)),
    )

    private fun bucket(key: String, limit: Pair<Long, Duration>): Bucket = buckets.get(key) {
        Bucket.builder()
            .addLimit(Bandwidth.builder().capacity(limit.first).refillIntervally(limit.first, limit.second).build())
            .build()
    }
}

/**
 * Guards /admin/api/... (except login): a valid session is required; writes need a role that
 * may edit and the `X-Requested-With: th-admin` header. That header cannot be sent cross-site
 * without a CORS preflight, which /admin never grants (web/WebFilters.kt), so a forged form post
 * from another site is refused even before the SameSite cookie rule.
 */
@Component
class AdminApiInterceptor(private val sessions: AdminSessions) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        response.setHeader("Cache-Control", "no-store")
        val principal = sessions.resolve(request)
            ?: throw ApiException(401, "UNAUTHORIZED", "Please sign in to the dashboard.")
        if (request.method != "GET") {
            if (request.getHeader("X-Requested-With") != CSRF_HEADER_VALUE) {
                throw ApiException(403, "FORBIDDEN", "Missing dashboard request header.")
            }
            if (!principal.role.canEditContent) {
                throw ApiException(403, "FORBIDDEN", "Your role is read-only.")
            }
        }
        request.setAttribute(ATTRIBUTE, principal)
        return true
    }

    companion object {
        const val ATTRIBUTE = "timeshealth.admin"
        const val CSRF_HEADER_VALUE = "th-admin"

        fun principal(request: HttpServletRequest): AdminPrincipal =
            request.getAttribute(ATTRIBUTE) as? AdminPrincipal
                ?: throw ApiException(401, "UNAUTHORIZED", "Please sign in to the dashboard.")
    }
}

/**
 * Creates the first OWNER from ADMIN_BOOTSTRAP_EMAIL / ADMIN_BOOTSTRAP_PASSWORD when the
 * AdminUser table is empty. After that the owner adds everyone else in the dashboard, and the
 * two settings can be removed.
 */
@Component
class AdminBootstrap(private val env: ServerEnv, private val users: AdminUsers) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(AdminBootstrap::class.java)

    override fun run(args: ApplicationArguments) {
        val email = env.adminBootstrapEmail.trim()
        val password = env.adminBootstrapPassword
        if (email.isEmpty() || password.isEmpty()) return
        if (users.count() > 0) return
        AdminUsers.passwordProblem(password)?.let {
            log.error("admin bootstrap skipped: ADMIN_BOOTSTRAP_PASSWORD is too weak ({})", it)
            return
        }
        users.create(email, email.substringBefore('@'), AdminRole.OWNER, password)
        log.info("admin bootstrap: created the first dashboard owner")
    }
}
