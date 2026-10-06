package timeshealth.app.core.network

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import timeshealth.app.core.model.ApiJson

/** The recorded staging responses, shared with :core:model (see build.gradle.kts). */
internal object Fixtures {
    fun text(name: String): String {
        val url = checkNotNull(Fixtures::class.java.classLoader.getResource("fixtures/$name")) {
            "fixtures/$name is not on the test classpath"
        }
        return url.readText()
    }

    fun tree(name: String): JsonObject = ApiJson.parseToJsonElement(text(name)).jsonObject

    /** Every recorded fixture file name. */
    val names: List<String> by lazy {
        val dir = checkNotNull(Fixtures::class.java.classLoader.getResource("fixtures")) { "fixtures/ missing" }
        java.io.File(dir.toURI()).list { _, name -> name.endsWith(".json") }.orEmpty().sorted()
    }
}

/** Device "now" used by every rig, so server-clock skew is exact. 2026-10-06T08:00:00Z. */
internal const val DEVICE_NOW_MS = 1_791_273_600_000L

/**
 * A MockWebServer plus the real network stack from [NetworkFactory], pointed at it with a base
 * URL ending in "/v1" and no trailing slash, exactly like BuildConfig.API_BASE_URL.
 */
internal class ApiTestRig(
    debug: Boolean = true,
    callTimeout: Duration = NetworkFactory.DEFAULT_CALL_TIMEOUT,
    onUnauthorized: (() -> Unit)? = null,
) : Closeable {
    val server = MockWebServer().apply { start() }

    /** What the [TokenProvider] returns; null means signed out. */
    @Volatile
    var token: String? = TOKEN

    /** When set, the [TokenProvider] throws this instead. */
    @Volatile
    var tokenFailure: Exception? = null

    val unauthorizedCalls = AtomicInteger()
    val logLines: MutableList<String> = CopyOnWriteArrayList()
    val clock = ServerClock { DEVICE_NOW_MS }

    val tokenProvider = object : TokenProvider {
        override suspend fun token(): String? {
            tokenFailure?.let { throw it }
            return token
        }
    }

    val baseUrl: String = server.url("/v1").toString().removeSuffix("/")

    val client = NetworkFactory.createOkHttpClient(
        tokenProvider = tokenProvider,
        onUnauthorized = onUnauthorized ?: { unauthorizedCalls.incrementAndGet(); Unit },
        debug = debug,
        logger = { logLines += it },
        callTimeout = callTimeout,
    )

    val api: TimesHealthApi = NetworkFactory.createRetrofit(baseUrl, client, clock, validateEagerly = true)
        .create(TimesHealthApi::class.java)

    fun enqueue(status: Int = 200, body: String = "{}", contentType: String = "application/json; charset=utf-8") {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", contentType).setBody(body))
    }

    fun enqueueFixture(name: String, status: Int = 200) = enqueue(status, Fixtures.text(name))

    fun takeRequest(): RecordedRequest =
        checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "The server received no request" }

    override fun close() {
        runCatching { server.shutdown() }
    }

    companion object {
        const val TOKEN = "eyJhbGciOiJSUzI1NiJ9.test-payload.test-signature"
    }
}

/** Runs [block] and returns the [ApiRequestException] it must throw. */
internal suspend fun expectApiError(block: suspend () -> Unit): ApiRequestException {
    try {
        block()
    } catch (e: ApiRequestException) {
        return e
    }
    throw AssertionError("Expected an ApiRequestException, but the call succeeded")
}
