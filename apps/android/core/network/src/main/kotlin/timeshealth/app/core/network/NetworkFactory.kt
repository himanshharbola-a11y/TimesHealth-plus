package timeshealth.app.core.network

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import timeshealth.app.core.model.ApiJson

/**
 * Builds the HTTP stack. No DI framework here: the app module wires these into Hilt, typically
 * one [ServerClock], one [OkHttpClient] and one [TimesHealthApi] per process.
 *
 * Interceptor order matters: standard headers, then [AuthInterceptor], then (debug only)
 * [DebugLoggingInterceptor], which must see whether a credential was attached so it can keep
 * those bodies out of the log.
 */
object NetworkFactory {

    /** The RN client's per-request bound. Override per method with [CallTimeout]. */
    val DEFAULT_CALL_TIMEOUT: Duration = 15.seconds

    private val JSON = "application/json".toMediaType()

    /**
     * The API, ready to call.
     *
     * @param baseUrl e.g. `https://host/v1` (BuildConfig.API_BASE_URL); a trailing slash is
     *   optional.
     * @param onUnauthorized the session ended: the server rejected a token we sent. Called on an
     *   OkHttp thread, possibly once per in-flight request, so it must be idempotent and quick
     *   (clear the persona token, sign out of the identity provider, route to login).
     * @param debug adds request logging (see [DebugLoggingInterceptor]) and validates every
     *   [TimesHealthApi] method at creation.
     * @param serverClock receives `serverTime` from config, session and home responses.
     * @param logger where debug log lines go, e.g. `{ Log.d("TimesHealthApi", it) }`.
     * @param callTimeout the default whole-call deadline.
     */
    fun createApi(
        baseUrl: String,
        tokenProvider: TokenProvider,
        onUnauthorized: () -> Unit,
        debug: Boolean,
        serverClock: ServerClock = ServerClock(),
        logger: (String) -> Unit = { println(it) },
        callTimeout: Duration = DEFAULT_CALL_TIMEOUT,
    ): TimesHealthApi {
        val client = createOkHttpClient(tokenProvider, onUnauthorized, debug, logger, callTimeout)
        return createRetrofit(baseUrl, client, serverClock, validateEagerly = debug)
            .create(TimesHealthApi::class.java)
    }

    /** The OkHttp client: headers, auth, unauthorized handling, deadline, debug logging. */
    fun createOkHttpClient(
        tokenProvider: TokenProvider,
        onUnauthorized: () -> Unit,
        debug: Boolean,
        logger: (String) -> Unit = { println(it) },
        callTimeout: Duration = DEFAULT_CALL_TIMEOUT,
    ): OkHttpClient {
        require(callTimeout.isPositive()) { "callTimeout must be positive; 0 would mean no deadline" }
        return OkHttpClient.Builder()
            // Every request is bounded. A hung socket on a flaky mobile network must surface as
            // an error state, not an endless spinner. The whole-call deadline is the ONE bound,
            // like RN's single AbortController timer: OkHttp's per-phase timeouts (10 s each by
            // default) would cut a call short of its own deadline, or of a longer @CallTimeout.
            .callTimeout(callTimeout.toJavaDuration())
            .connectTimeout(java.time.Duration.ZERO)
            .readTimeout(java.time.Duration.ZERO)
            .writeTimeout(java.time.Duration.ZERO)
            .addInterceptor(StandardHeadersInterceptor())
            .addInterceptor(AuthInterceptor(tokenProvider, onUnauthorized))
            .apply { if (debug) addInterceptor(DebugLoggingInterceptor(logger)) }
            .build()
    }

    /**
     * Retrofit over [client]. Request bodies are encoded with [ApiJson]; responses are decoded
     * with it by [ApiCallAdapterFactory], which also maps every failure to [ApiRequestException].
     */
    fun createRetrofit(
        baseUrl: String,
        client: OkHttpClient,
        serverClock: ServerClock,
        validateEagerly: Boolean = false,
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .client(client)
            .addCallAdapterFactory(ApiCallAdapterFactory(serverClock))
            .addConverterFactory(ApiJson.asConverterFactory(JSON))
            .validateEagerly(validateEagerly)
            .build()

    /**
     * Retrofit resolves method paths the way a browser resolves links. The app's base URL has
     * no trailing slash ("http://10.0.2.2:4000/v1"), and resolved as-is "home" would REPLACE
     * "v1", sending every call to the wrong path. So the slash is added here.
     */
    internal fun normalizeBaseUrl(baseUrl: String): HttpUrl =
        (if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/").toHttpUrl()
}
