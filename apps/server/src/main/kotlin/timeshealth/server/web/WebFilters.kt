package timeshealth.server.web

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter
import timeshealth.server.config.ServerEnv
import timeshealth.server.error.ApiErrors

/**
 * @fastify/helmet 12 (helmet 7) defaults, with `contentSecurityPolicy: false` as in app.ts.
 * Spring Security's own header writers are disabled so the set is exactly helmet's.
 */
class SecurityHeadersFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        HEADERS.forEach { (name, value) -> response.setHeader(name, value) }
        chain.doFilter(request, response)
    }

    companion object {
        val HEADERS = linkedMapOf(
            "Cross-Origin-Opener-Policy" to "same-origin",
            "Cross-Origin-Resource-Policy" to "same-origin",
            "Origin-Agent-Cluster" to "?1",
            "Referrer-Policy" to "no-referrer",
            "Strict-Transport-Security" to "max-age=15552000; includeSubDomains",
            "X-Content-Type-Options" to "nosniff",
            "X-DNS-Prefetch-Control" to "off",
            "X-Download-Options" to "noopen",
            "X-Frame-Options" to "SAMEORIGIN",
            "X-Permitted-Cross-Domain-Policies" to "none",
            "X-XSS-Protection" to "0",
        )
    }
}

/**
 * @fastify/cors 10 as app.ts configures it: `origin: CORS_ORIGINS includes '*' ? true : list`,
 * `credentials: true`, defaults otherwise. Unlike Spring's CorsFilter it never REJECTS a request
 * from an origin it does not allow; it just leaves out Access-Control-Allow-Origin.
 *
 *  - every response: `Vary: Origin`, `Access-Control-Allow-Credentials: true`, and the request's
 *    Origin reflected when allowed;
 *  - OPTIONS (any path): strict preflight. Without Origin or Access-Control-Request-Method it is
 *    400 text/plain "Invalid Preflight Request"; otherwise 204 with the allowed methods and the
 *    requested headers echoed.
 */
class FastifyCorsFilter(env: ServerEnv) : OncePerRequestFilter() {
    private val reflectAny = env.corsOrigins.contains("*")
    private val allowList = env.corsOrigins

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        appendVary(response, "Origin")
        val origin = request.getHeader("Origin")
        val allowed = origin != null && (reflectAny || allowList.contains(origin))
        if (allowed) response.setHeader("Access-Control-Allow-Origin", origin)
        response.setHeader("Access-Control-Allow-Credentials", "true")

        if (request.method == "OPTIONS") {
            if (origin == null || request.getHeader("Access-Control-Request-Method") == null) {
                response.status = 400
                response.contentType = "text/plain; charset=utf-8"
                response.writer.write("Invalid Preflight Request")
                return
            }
            response.setHeader("Access-Control-Allow-Methods", "GET,HEAD,PUT,PATCH,POST,DELETE")
            appendVary(response, "Access-Control-Request-Headers")
            request.getHeader("Access-Control-Request-Headers")?.let {
                response.setHeader("Access-Control-Allow-Headers", it)
            }
            response.status = 204
            response.setContentLength(0)
            return
        }
        chain.doFilter(request, response)
    }

    private fun appendVary(response: HttpServletResponse, value: String) {
        val existing = response.getHeader("Vary")
        when {
            existing.isNullOrEmpty() -> response.setHeader("Vary", value)
            existing.split(',').none { it.trim().equals(value, ignoreCase = true) } ->
                response.setHeader("Vary", "$existing, $value")
        }
    }
}

/**
 * Fastify's `trustProxy: (address, hop) => hop < TRUST_PROXY_HOPS` (proxy-addr): walk from the
 * socket address back through X-Forwarded-For (right to left) while each hop is trusted; the
 * client is the first untrusted address. With the default of 1 that is the rightmost
 * X-Forwarded-For entry (what ngrok or one load balancer appended), so a client cannot choose its
 * own IP by sending a fake header.
 */
object ClientIp {
    fun resolve(remoteAddr: String, forwardedFor: List<String>, trustProxyHops: Double): String {
        val chain = forwardedFor.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        val addrs = listOf(remoteAddr) + chain.reversed()
        for (i in 0 until addrs.size - 1) {
            // NaN hops trust nothing (hop < NaN is false), as in JavaScript.
            if (!(i < trustProxyHops)) return addrs[i]
        }
        return addrs.last()
    }

    fun of(request: HttpServletRequest, trustProxyHops: Double): String =
        resolve(request.remoteAddr, request.getHeaders("X-Forwarded-For").toList(), trustProxyHops)
}

/**
 * The global limiter from app.ts: 300 requests per minute per key, where the key is the SIGNED-IN
 * caller when a bearer token is present (`t:` + first 32 hex of sha256(token): the token itself is
 * never held as a key) and the client IP otherwise (`ip:`). Keying on the token matters: whole
 * Jio/Airtel CGNAT ranges share one IP.
 *
 * Same window semantics as @fastify/rate-limit's local store (a fixed one-minute window from the
 * key's first request, at most 5000 keys held) and the same headers: x-ratelimit-limit,
 * x-ratelimit-remaining, x-ratelimit-reset, plus retry-after on a 429. Applies to routes only.
 */
class RateLimitFilter(
    private val env: ServerEnv,
    private val routes: RouteLookup,
    private val max: Long = 300,
    private val window: Duration = Duration.ofMinutes(1),
) : OncePerRequestFilter() {
    private val buckets = Caffeine.newBuilder()
        .maximumSize(5000)
        .expireAfterAccess(window.multipliedBy(2))
        .build<String, Bucket>()

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (routes.handlerFor(request) == null) {
            chain.doFilter(request, response)
            return
        }
        val bucket = buckets.get(keyFor(request)) {
            Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(max).refillIntervally(max, window).build())
                .build()
        }
        val probe = bucket.tryConsumeAndReturnRemaining(1)
        val resetSeconds = ceil(probe.nanosToWaitForReset / 1_000_000_000.0).toLong()
        response.setHeader("x-ratelimit-limit", max.toString())
        if (probe.isConsumed) {
            response.setHeader("x-ratelimit-remaining", probe.remainingTokens.toString())
            response.setHeader("x-ratelimit-reset", resetSeconds.toString())
            chain.doFilter(request, response)
            return
        }
        val retryAfter = ceil(probe.nanosToWaitForRefill / 1_000_000_000.0).toLong()
        response.setHeader("x-ratelimit-remaining", "0")
        response.setHeader("x-ratelimit-reset", retryAfter.toString())
        response.setHeader("retry-after", retryAfter.toString())
        ApiErrors.write(response, 429, ApiErrors.forStatus(429, null, env.isProd))
    }

    fun keyFor(request: HttpServletRequest): String {
        val auth = request.getHeader("Authorization")
        if (auth != null && auth.startsWith("Bearer ")) {
            val digest = MessageDigest.getInstance("SHA-256").digest(auth.substring(7).toByteArray(Charsets.UTF_8))
            return "t:" + digest.joinToString("") { "%02x".format(it) }.substring(0, 32)
        }
        return "ip:" + ClientIp.of(request, env.trustProxyHops)
    }
}

/**
 * Fastify's request logging with app.ts's serializer: method, URL WITHOUT its query string (queries
 * carry coordinates and tokens — docs/04 T10) and request id; then status and response time.
 * Headers (Authorization) and bodies (phone, email, routePolyline) are never logged.
 */
class RequestLogFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(RequestLogFilter::class.java)
    private val nextId = AtomicLong(0)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        // Fastify's genReqId: "req-" + a base-36 counter.
        val reqId = "req-" + nextId.incrementAndGet().toString(36)
        val started = System.nanoTime()
        MDC.put("reqId", reqId)
        try {
            log.info("incoming request {} {}", request.method, request.requestURI)
            chain.doFilter(request, response)
        } finally {
            val ms = (System.nanoTime() - started) / 1_000_000.0
            log.info("request completed {} {} {} {}ms", request.method, request.requestURI, response.status, "%.1f".format(ms))
            MDC.remove("reqId")
        }
    }
}
