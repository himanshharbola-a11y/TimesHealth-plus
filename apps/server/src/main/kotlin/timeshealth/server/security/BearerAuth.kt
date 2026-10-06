package timeshealth.server.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.MethodParameter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import timeshealth.server.config.ServerEnv
import timeshealth.server.db.entity.UserEntity
import timeshealth.server.error.ApiErrors
import timeshealth.server.error.HttpError
import timeshealth.server.identity.TokenRejected
import timeshealth.server.identity.VerifiedIdentity
import timeshealth.server.web.RouteLookup

/**
 * The signed-in user, as resolved by [BearerAuthFilter] (Node's `req.user`). A handler with a
 * `@CurrentUser user: UserEntity` parameter REQUIRES auth — the equivalent of
 * `{ preHandler: app.requireAuth }`. The entity is a detached request-time snapshot.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUser

/** For a handler that requires auth but does not read the user. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Authenticated

/** The Spring Security view of a resolved user. Stateless: never stored in a session. */
class UserAuthentication(val user: UserEntity) : AbstractAuthenticationToken(AuthorityUtils.NO_AUTHORITIES) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Any = user

    override fun getName(): String = user.id
}

/**
 * Port of auth.ts `requireAuth`, run only for routes that require a user (see [RouteLookup]).
 *
 * Two failure modes, and they must not be confused. A bad token is the user's problem: 401, which
 * the app treats as "sign out". A database or provider outage is OUR problem: 503, which the app
 * treats as "try again". Collapsing both into 401 would sign every active user out during a
 * two-second database blip.
 */
class BearerAuthFilter(
    private val routes: RouteLookup,
    private val auth: AuthService,
    private val env: ServerEnv,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(BearerAuthFilter::class.java)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val handler = routes.handlerFor(request)
        if (handler == null || !routes.requiresAuth(handler)) {
            chain.doFilter(request, response)
            return
        }

        val header = request.getHeader("Authorization")
        if (header == null || !header.startsWith("Bearer ")) {
            fail(response, 401, "Missing bearer token")
            return
        }

        val identity: VerifiedIdentity = try {
            auth.verifyToken(header.substring(7))
        } catch (e: TokenRejected) {
            // Only a DEFINITIVE rejection signs the app out.
            log.warn("token rejected cause={}", e.message)
            fail(response, 401, "Invalid or expired token")
            return
        } catch (e: Exception) {
            // A provider outage (network, quota): the app retries and the user stays in.
            log.error("identity provider unavailable cause={}", e.toString())
            fail(response, 503, "Sign-in service temporarily unavailable")
            return
        }

        val user = try {
            auth.resolveUser(identity)
        } catch (e: Exception) {
            log.error("user resolution failed cause={}", e.toString())
            fail(response, 503, "Service temporarily unavailable")
            return
        }

        request.setAttribute(USER_ATTRIBUTE, user)
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = UserAuthentication(user)
        SecurityContextHolder.setContext(context)
        try {
            chain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    /** A thrown error with a status, through the app.ts error-handler rules (prod hides 5xx text). */
    private fun fail(response: HttpServletResponse, status: Int, message: String) {
        ApiErrors.write(response, status, ApiErrors.forStatus(status, message, env.isProd))
    }

    companion object {
        const val USER_ATTRIBUTE = "timeshealth.user"
    }
}

/** Supplies `@CurrentUser` parameters. */
class CurrentUserArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentUser::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any = webRequest.getAttribute(BearerAuthFilter.USER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)
        ?: throw HttpError(401, "Missing bearer token")
}
