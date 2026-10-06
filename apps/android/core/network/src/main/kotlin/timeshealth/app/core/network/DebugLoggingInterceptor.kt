package timeshealth.app.core.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Debug-build request log. Hand-written instead of okhttp-logging-interceptor because that one
 * logs whatever level you give it, and its useful levels print credentials and personal data.
 *
 * What is never written, and why (docs/04 T10, mirrored by the server's own log redaction in
 * apps/api/src/app.ts):
 * - Headers. `Authorization` carries the bearer token, and debug logs end up in bug reports,
 *   screenshots and shared logcat dumps.
 * - Query strings. GET /marathon/events?lat=..&lng=.. carries the user's location.
 * - Request bodies, and response bodies of any request that carried a credential. Those hold
 *   phone numbers, emails, run routes, push tokens, signed bib QR tokens and playback URLs.
 *
 * What is written: method, path, status and timing, plus a capped response body for requests
 * that went out without a credential (GET /config, or a call made while signed out), which is
 * where a "why won't it start" investigation begins.
 *
 * Must run AFTER [AuthInterceptor] so it can tell whether a credential was attached.
 */
internal class DebugLoggingInterceptor(private val log: (String) -> Unit) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val label = "${request.method} ${request.url.encodedPath}"
        val credentialed = request.header("Authorization") != null

        log("--> $label")
        val startNs = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            // OkHttp's messages name the host at most (it redacts URLs), which is what you need
            // to spot a wrong base URL.
            log("<-- FAILED $label (${elapsedMs(startNs)} ms): ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        log("<-- ${response.code} $label (${elapsedMs(startNs)} ms)")

        if (!credentialed) {
            val body = response.peekBody(MAX_LOGGED_BODY_BYTES).string()
            if (body.isNotEmpty()) log("<-- body: $body")
        }
        return response
    }

    private fun elapsedMs(startNs: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

    private companion object {
        const val MAX_LOGGED_BODY_BYTES = 2_048L
    }
}
