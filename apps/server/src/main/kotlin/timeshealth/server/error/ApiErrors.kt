package timeshealth.server.error

import jakarta.servlet.http.HttpServletResponse
import java.nio.charset.StandardCharsets
import timeshealth.app.core.model.ApiError
import timeshealth.app.core.model.ApiJson

/**
 * A reply a route sends on purpose, with its own code — Node's
 * `reply.code(409).send({ code: 'LOGIN_IDENTIFIER', message })`. Written verbatim: the
 * status-to-code mapping below does NOT apply.
 */
class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val fields: Map<String, List<String>>? = null,
) : RuntimeException(message)

/**
 * A thrown error carrying a status — Node's `Object.assign(new Error(msg), { statusCode })`.
 * Goes through the app.ts error handler: the code is derived from the status, and a 5xx message
 * is hidden in production.
 */
class HttpError(val status: Int, override val message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * The app.ts error handler and not-found handler, shared by the controller advice, the servlet
 * filters (auth, rate limit) and the /error fallback, so every error body has one shape:
 * `{ code, message, fields? }`.
 */
object ApiErrors {
    const val TOO_MANY_REQUESTS_MESSAGE = "Too many requests. Please wait a moment and try again."
    const val HIDDEN_INTERNAL_MESSAGE = "Something went wrong"

    /** app.ts: the code for a thrown error with this status. */
    fun codeFor(status: Int): String = when {
        status == 401 -> "UNAUTHORIZED"
        status == 404 -> "NOT_FOUND"
        status == 429 -> "TOO_MANY_REQUESTS" // the global limiter — apps branch on this code
        status == 503 -> "UNAVAILABLE"
        status >= 500 -> "INTERNAL"
        else -> "BAD_REQUEST"
    }

    /** app.ts: never leak internals to the client in production. */
    fun messageFor(status: Int, message: String?, isProd: Boolean): String = when {
        status == 429 -> TOO_MANY_REQUESTS_MESSAGE
        status >= 500 && isProd -> HIDDEN_INTERNAL_MESSAGE
        else -> message ?: ""
    }

    /** The body for a thrown error with a status (the app.ts setErrorHandler path). */
    fun forStatus(status: Int, message: String?, isProd: Boolean): ApiError =
        ApiError(code = codeFor(status), message = messageFor(status, message, isProd))

    /** setNotFoundHandler: unknown routes answer in the same shape as everything else. */
    val NOT_FOUND = ApiError(code = "NOT_FOUND", message = "Not found")

    /**
     * Serialised with [ApiJson], whose `explicitNulls = false` omits `fields` when there are
     * none — exactly what Node sends (the key exists only on validation errors).
     */
    fun toJson(error: ApiError): String = ApiJson.encodeToString(ApiError.serializer(), error)

    /** For filters, which answer before Spring MVC is involved. */
    fun write(response: HttpServletResponse, status: Int, error: ApiError) {
        if (response.isCommitted) return
        response.resetBuffer()
        response.status = status
        response.contentType = "application/json; charset=utf-8"
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(toJson(error))
        response.writer.flush()
    }
}
