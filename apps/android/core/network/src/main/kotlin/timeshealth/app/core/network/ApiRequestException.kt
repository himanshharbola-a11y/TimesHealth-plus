package timeshealth.app.core.network

import timeshealth.app.core.model.ApiError
import timeshealth.app.core.model.ApiResult

/**
 * The ONE exception every [TimesHealthApi] call throws for an unsuccessful outcome. Port of the
 * RN `ApiRequestError` (apps/mobile/src/api/client.ts): callers catch this type and branch on
 * [status] and [ApiError.code]; they never see OkHttp, Retrofit or serialization exceptions.
 *
 * - `status == 0`: no HTTP answer at all. [ApiErrorCodes.TIMEOUT] when the call hit its deadline,
 *   [ApiErrorCodes.NETWORK] for everything else (offline, DNS, refused, reset, TLS).
 * - `2xx` with [ApiErrorCodes.BAD_RESPONSE]: the server answered, but not with the API's JSON. An
 *   HTML page from a proxy or tunnel (ngrok's offline page, a gateway error), an empty body, or
 *   a body that breaks the contract. The raw text is never shown to the user.
 * - `4xx` / `5xx`: [error] is the server's [ApiError] body. A body that isn't JSON (a gateway's HTML
 *   502) becomes [ApiErrorCodes.UNKNOWN].
 *
 * [message] is always the user-facing text: the server's message, or the RN client's wording for
 * the client-side codes. [cause] keeps the underlying exception for logs and crash reports.
 */
class ApiRequestException(
    val status: Int,
    val error: ApiError,
    cause: Throwable? = null,
) : Exception(error.message, cause) {

    /** Shorthand for [ApiError.code]. */
    val code: String get() = error.code

    /** True when the request got no HTTP answer: offline, DNS failure, refused, or a timeout. */
    val isNetworkFailure: Boolean get() = status == 0

    override fun toString(): String =
        "ApiRequestException(status=$status, code=${error.code}, message=${error.message})"
}

/**
 * Error codes the CLIENT produces. Server codes (NOT_ENTITLED, INVALID_BODY, UNAVAILABLE, ...)
 * arrive in [ApiError.code] as sent. The values match the RN client so analytics and support
 * see the same codes from both apps.
 */
object ApiErrorCodes {
    /** No connection, DNS failure, refused or reset connection, TLS failure. Status 0. */
    const val NETWORK = "NETWORK"

    /** The call hit its deadline ([CallTimeout], or the 15 s default). Status 0. */
    const val TIMEOUT = "TIMEOUT"

    /** A 2xx whose body isn't the API's JSON. */
    const val BAD_RESPONSE = "BAD_RESPONSE"

    /** A 4xx/5xx without a usable `code`, e.g. a proxy's HTML error page. */
    const val UNKNOWN = "UNKNOWN"
}

/** User-facing messages for client-side errors, word for word from apps/mobile/src/api/client.ts. */
internal object ApiErrorMessages {
    const val TIMEOUT = "That took too long. Check your connection and try again."
    const val NETWORK = "No connection. Check your network and try again."
    const val BAD_RESPONSE = "Something went wrong on our side. Please try again."
    const val UNKNOWN = "Something went wrong."
}

/**
 * Runs one API call and returns [ApiResult] instead of throwing, for callers that prefer to
 * branch. Only [ApiRequestException] is turned into [ApiResult.Err]; cancellation and
 * programming errors still propagate.
 */
suspend fun <T> apiResult(call: suspend () -> T): ApiResult<T> =
    try {
        ApiResult.Ok(call())
    } catch (e: ApiRequestException) {
        ApiResult.Err(e.error)
    }
