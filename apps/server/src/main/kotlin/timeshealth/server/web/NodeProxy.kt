package timeshealth.server.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import timeshealth.server.config.ServerEnv
import timeshealth.server.error.ApiErrors

/**
 * TEMPORARY, while routes move from the Node server to this one ("strangler" migration).
 *
 * This server is the app's single address. A /v1 route ported here is served here (Spring picks
 * the most specific mapping, so every real controller wins over this catch-all); any other /v1
 * request is forwarded unchanged to the Node server at NODE_UPSTREAM_URL, and its answer is
 * relayed back. Each newly ported route simply stops reaching Node. When nothing is left, delete
 * this file and the setting.
 *
 * - Auth is Node's job for forwarded routes: the Authorization header passes through untouched.
 * - The client's IP is forwarded as X-Forwarded-For (resolved under this server's own
 *   TRUST_PROXY_HOPS). Node then needs TRUST_PROXY_HOPS = its own proxies + 1.
 * - Node down or slow: 503 UNAVAILABLE in the API's error shape, which the app retries.
 * - With NODE_UPSTREAM_URL unset (production after the port), these routes are 404s, as before.
 */
@RestController
class NodeProxy(private val env: ServerEnv) {
    private val log = LoggerFactory.getLogger(NodeProxy::class.java)
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    @RequestMapping("/v1/**")
    fun forward(request: HttpServletRequest, response: HttpServletResponse) {
        val upstream = env.nodeUpstreamUrl.trimEnd('/')
        if (upstream.isEmpty()) {
            ApiErrors.write(response, 404, ApiErrors.NOT_FOUND)
            return
        }

        val body = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (body.size > MAX_BODY_BYTES) {
            ApiErrors.write(response, 413, ApiErrors.forStatus(413, "Request body is too large", env.isProd))
            return
        }

        val query = request.queryString?.let { "?$it" } ?: ""
        val builder = HttpRequest.newBuilder(URI.create(upstream + request.requestURI + query))
            .timeout(Duration.ofSeconds(30))
            .method(
                request.method,
                if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body),
            )
        for (name in request.headerNames.toList()) {
            if (name.lowercase() in DROP_REQUEST_HEADERS) continue
            for (value in request.getHeaders(name).toList()) builder.header(name, value)
        }
        builder.header("X-Forwarded-For", ClientIp.of(request, env.trustProxyHops))

        val answer = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: Exception) {
            log.error("node upstream failed path={} cause={}", request.requestURI, e.toString())
            ApiErrors.write(response, 503, ApiErrors.forStatus(503, "Service temporarily unavailable", env.isProd))
            return
        }

        response.status = answer.statusCode()
        answer.headers().map().forEach { (name, values) ->
            if (name.lowercase() in DROP_RESPONSE_HEADERS) return@forEach
            // Replace, not add: this server's own security/CORS headers carry the same names.
            values.forEachIndexed { i, v -> if (i == 0) response.setHeader(name, v) else response.addHeader(name, v) }
        }
        response.outputStream.write(answer.body())
    }

    companion object {
        /** Fastify's default bodyLimit. */
        const val MAX_BODY_BYTES = 1_048_576

        private val HOP_BY_HOP = setOf(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade",
        )
        // java.net.http sets host and content-length itself and refuses them from callers.
        private val DROP_REQUEST_HEADERS = HOP_BY_HOP + setOf("host", "content-length", "expect", "x-forwarded-for")
        private val DROP_RESPONSE_HEADERS = HOP_BY_HOP + setOf("content-length", ":status")
    }
}
