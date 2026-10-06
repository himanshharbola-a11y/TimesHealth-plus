package timeshealth.app.core.network

import java.io.IOException
import java.io.InterruptedIOException
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Timeout
import retrofit2.Call
import retrofit2.CallAdapter
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import timeshealth.app.core.model.ApiError
import timeshealth.app.core.model.ApiJson
import timeshealth.app.core.model.AppConfigResponse
import timeshealth.app.core.model.HomeFeedResponse
import timeshealth.app.core.model.SessionResponse

/**
 * Turns every [TimesHealthApi] call into "the decoded body, or an [ApiRequestException]".
 *
 * Retrofit runs a suspend method through the call adapter for `Call<T>`, so this factory sees
 * every method. It asks Retrofit for the raw, buffered [ResponseBody] and decodes it here, with
 * [ApiJson], instead of through a converter. That is deliberate: a converter never learns the
 * HTTP status, and the RN client's error model needs it (an HTML page on a 2xx is
 * BAD_RESPONSE *with* that status; on a 4xx/5xx it is UNKNOWN). Doing it in one place also gives
 * callers exactly one exception type, whatever failed.
 *
 * Also applies a method's [CallTimeout], and feeds `serverTime` into [ServerClock].
 */
internal class ApiCallAdapterFactory(private val serverClock: ServerClock) : CallAdapter.Factory() {

    override fun get(returnType: Type, annotations: Array<out Annotation>, retrofit: Retrofit): CallAdapter<*, *>? {
        if (getRawType(returnType) != Call::class.java) return null
        require(returnType is ParameterizedType) { "Call return type must be parameterized as Call<Foo>" }
        val timeoutMillis = annotations.filterIsInstance<CallTimeout>().firstOrNull()?.millis
        require(timeoutMillis == null || timeoutMillis > 0) { "@CallTimeout must be positive; 0 would mean no deadline" }
        // Resolving the serializer here makes a non-serializable return type fail when the
        // method is first used (or at create() with validateEagerly), not on every response.
        return ApiCallAdapter(BodyDecoder(getParameterUpperBound(0, returnType)), serverClock, timeoutMillis)
    }
}

private class ApiCallAdapter(
    private val decoder: BodyDecoder,
    private val serverClock: ServerClock,
    private val timeoutMillis: Long?,
) : CallAdapter<ResponseBody, Call<Any>> {
    override fun responseType(): Type = ResponseBody::class.java

    override fun adapt(call: Call<ResponseBody>): Call<Any> = ApiCall(call, decoder, serverClock, timeoutMillis)
}

/** Decodes a 2xx body into the method's declared return type. */
private class BodyDecoder(type: Type) {
    private val serializer: KSerializer<Any>? =
        if (type == Unit::class.java) null else ApiJson.serializersModule.serializer(type)

    /** @throws IllegalArgumentException (incl. SerializationException) when [text] isn't valid. */
    fun decode(text: String): Any {
        val serializer = serializer ?: run {
            // A Unit method still requires JSON (or nothing): RN rejects any 2xx body that
            // doesn't parse, because an HTML 200 means the request never reached the API.
            if (text.isNotBlank()) ApiJson.parseToJsonElement(text)
            return Unit
        }
        return ApiJson.decodeFromString(serializer, text)
    }
}

/**
 * Wraps Retrofit's call. Every outcome reaches the caller as a decoded body or an
 * [ApiRequestException]; only programming errors (e.g. a request body that can't be encoded)
 * pass through unchanged, so they stay loud instead of posing as "No connection".
 */
private class ApiCall(
    private val delegate: Call<ResponseBody>,
    private val decoder: BodyDecoder,
    private val serverClock: ServerClock,
    private val timeoutMillis: Long?,
) : Call<Any> {

    override fun enqueue(callback: Callback<Any>) {
        applyTimeout()
        delegate.enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                val success = try {
                    Response.success(bodyOf(response), response.raw())
                } catch (e: Throwable) {
                    // Normally an ApiRequestException. Anything else is forwarded too: Retrofit
                    // swallows exceptions thrown from onResponse, which would leave the caller's
                    // coroutine suspended forever.
                    callback.onFailure(this@ApiCall, e)
                    return
                }
                callback.onResponse(this@ApiCall, success)
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                callback.onFailure(this@ApiCall, t.toApiFailure())
            }
        })
    }

    override fun execute(): Response<Any> {
        applyTimeout()
        val response = try {
            delegate.execute()
        } catch (e: IOException) {
            throw e.toApiFailure()
        }
        return Response.success(bodyOf(response), response.raw())
    }

    /**
     * Overrides OkHttp's whole-call deadline for this call only. RealCall reads it when the call
     * starts, so setting it before enqueue/execute is enough.
     */
    private fun applyTimeout() {
        timeoutMillis?.let { delegate.timeout().timeout(it, TimeUnit.MILLISECONDS) }
    }

    private fun bodyOf(response: Response<ResponseBody>): Any {
        val status = response.code()
        if (!response.isSuccessful) {
            throw ApiRequestException(status, parseApiError(response.errorBody()?.readTextOrNull()))
        }
        // 204/205 arrive with no body at all; decode treats that as empty text.
        val text = response.body()?.readTextOrNull().orEmpty()
        val body = try {
            decoder.decode(text)
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException: not JSON, or JSON that
            // breaks the contract. Either way the raw body is not something to show the user.
            throw ApiRequestException(status, ApiError(ApiErrorCodes.BAD_RESPONSE, ApiErrorMessages.BAD_RESPONSE), e)
        }
        serverTimeOf(body)?.let(serverClock::sync)
        return body
    }

    override fun clone(): Call<Any> = ApiCall(delegate.clone(), decoder, serverClock, timeoutMillis)

    override fun isExecuted(): Boolean = delegate.isExecuted

    override fun cancel() = delegate.cancel()

    override fun isCanceled(): Boolean = delegate.isCanceled

    override fun request(): Request = delegate.request()

    override fun timeout(): Timeout = delegate.timeout()
}

/** The responses that carry the server's clock (§6.1: countdowns tick from server time). */
private fun serverTimeOf(body: Any): String? = when (body) {
    is AppConfigResponse -> body.serverTime
    is SessionResponse -> body.serverTime
    is HomeFeedResponse -> body.serverTime
    else -> null
}

/**
 * Status 0 for anything that never produced an HTTP answer, as in RN. OkHttp signals its call
 * deadline (and socket timeouts) with [InterruptedIOException]; every other [IOException] is a
 * connectivity failure. Anything else is a bug and is returned unchanged.
 */
private fun Throwable.toApiFailure(): Throwable = when (this) {
    is ApiRequestException -> this
    is InterruptedIOException ->
        ApiRequestException(0, ApiError(ApiErrorCodes.TIMEOUT, ApiErrorMessages.TIMEOUT), this)
    is IOException ->
        ApiRequestException(0, ApiError(ApiErrorCodes.NETWORK, ApiErrorMessages.NETWORK), this)
    else -> this
}

/**
 * The [ApiError] for a 4xx/5xx body, matching RN: a JSON object supplies `code`, `message` and
 * `fields` (zod's `string[]` per field, or a plain string); a missing code becomes UNKNOWN and a
 * missing message the generic one. Anything that isn't a JSON object, such as a gateway's HTML
 * 502 page, becomes UNKNOWN with the generic message.
 */
private fun parseApiError(text: String?): ApiError {
    val json = text
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { ApiJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject
    val code = json.stringField("code") ?: ApiErrorCodes.UNKNOWN
    val message = json.stringField("message") ?: ApiErrorMessages.UNKNOWN
    if (json == null) return ApiError(code, message)

    // Decode through ApiError's own serializer so `fields` gets the model's normalisation.
    val normalised = JsonObject(json + mapOf<String, JsonElement>("code" to JsonPrimitive(code), "message" to JsonPrimitive(message)))
    return runCatching { ApiJson.decodeFromJsonElement(ApiError.serializer(), normalised) }
        .getOrElse { ApiError(code, message) }
}

private fun JsonObject?.stringField(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Retrofit has already buffered the body in memory, so this only fails if the read did. */
private fun ResponseBody.readTextOrNull(): String? = use {
    try {
        it.string()
    } catch (e: IOException) {
        null
    }
}
