package timeshealth.server.web

import jakarta.servlet.http.HttpServletRequest
import java.util.concurrent.ConcurrentHashMap
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.util.ServletRequestPathUtils
import timeshealth.server.security.Authenticated
import timeshealth.server.security.CurrentUser

/**
 * Which controller method (if any) a request will reach, resolved in the servlet filters so they
 * can apply Fastify's per-route semantics before Spring MVC runs:
 *  - auth is per route (`{ preHandler: app.requireAuth }`): a route needs a signed-in user when its
 *    handler takes a `@CurrentUser` parameter or is annotated [Authenticated];
 *  - the rate limiter covers routes only, not the 404 handler (@fastify/rate-limit hooks onRoute).
 *
 * The result is cached on the request.
 */
class RouteLookup(private val mappings: ObjectProvider<RequestMappingHandlerMapping>) {
    private val requiresAuthCache = ConcurrentHashMap<HandlerMethod, Boolean>()

    fun handlerFor(request: HttpServletRequest): HandlerMethod? {
        if (request.getAttribute(ATTR_RESOLVED) == true) return request.getAttribute(ATTR_HANDLER) as HandlerMethod?
        val handler = try {
            if (!ServletRequestPathUtils.hasParsedRequestPath(request)) ServletRequestPathUtils.parseAndCache(request)
            mappings.orderedStream()
                .map { it.getHandler(request)?.handler as? HandlerMethod }
                .filter { it != null }
                .findFirst()
                .orElse(null)
        } catch (e: Exception) {
            // A known path with the wrong method: not a route (Fastify answers 404).
            null
        }
        request.setAttribute(ATTR_RESOLVED, true)
        if (handler != null) request.setAttribute(ATTR_HANDLER, handler)
        return handler
    }

    fun requiresAuth(handler: HandlerMethod): Boolean = requiresAuthCache.getOrPut(handler) {
        handler.hasMethodAnnotation(Authenticated::class.java) ||
            handler.beanType.isAnnotationPresent(Authenticated::class.java) ||
            handler.methodParameters.any { it.hasParameterAnnotation(CurrentUser::class.java) }
    }

    companion object {
        private const val ATTR_RESOLVED = "timeshealth.route.resolved"
        private const val ATTR_HANDLER = "timeshealth.route.handler"
    }
}
