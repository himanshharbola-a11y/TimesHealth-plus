package timeshealth.app.core.network

import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Test
import retrofit2.http.GET
import timeshealth.app.core.model.ApiError
import timeshealth.app.core.model.ApiResult
import timeshealth.app.core.model.OkResponse

/** Every way a call can fail ends up as one [ApiRequestException], shaped like RN's. */
class ErrorMappingTest {

    private val rig = ApiTestRig()

    @After
    fun tearDown() = rig.close()

    // ── Status 0: no HTTP answer ────────────────────────────────────────────

    @Test
    fun `a call that hits its deadline is status 0 TIMEOUT with RN's message`() = runTest {
        ApiTestRig(callTimeout = 300.milliseconds).use { slow ->
            slow.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

            val error = expectApiError { slow.api.home() }

            assertThat(error.status).isEqualTo(0)
            assertThat(error.code).isEqualTo(ApiErrorCodes.TIMEOUT)
            assertThat(error.message).isEqualTo("That took too long. Check your connection and try again.")
            assertThat(error.isNetworkFailure).isTrue()
            assertThat(error.cause).isInstanceOf(java.io.InterruptedIOException::class.java)
        }
    }

    /** A test-only endpoint with its own, shorter deadline. */
    private interface SlowApi {
        @CallTimeout(millis = 300)
        @GET("slow")
        suspend fun slow(): OkResponse
    }

    @Test
    fun `CallTimeout overrides the client's deadline for that method only`() = runTest {
        // The client default is 15 s; the annotation must cut this call off at 300 ms.
        val slowApi = NetworkFactory.createRetrofit(rig.baseUrl, rig.client, rig.clock).create(SlowApi::class.java)
        rig.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = TimeSource.Monotonic.markNow()

        val error = expectApiError { slowApi.slow() }

        assertThat(error.code).isEqualTo(ApiErrorCodes.TIMEOUT)
        assertThat(started.elapsedNow()).isLessThan(5.seconds)
    }

    @Test
    fun `no connection is status 0 NETWORK with RN's message`() = runTest {
        rig.server.shutdown() // Nothing listens on the port any more.

        val error = expectApiError { rig.api.home() }

        assertThat(error.status).isEqualTo(0)
        assertThat(error.code).isEqualTo(ApiErrorCodes.NETWORK)
        assertThat(error.message).isEqualTo("No connection. Check your network and try again.")
        assertThat(error.cause).isInstanceOf(java.io.IOException::class.java)
    }

    @Test
    fun `a connection dropped mid-response is NETWORK`() = runTest {
        rig.server.enqueue(
            MockResponse().setBody("""{"greeting":"Good morning""").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val error = expectApiError { rig.api.home() }

        assertThat(error.status).isEqualTo(0)
        assertThat(error.code).isEqualTo(ApiErrorCodes.NETWORK)
    }

    // ── 2xx that isn't the API's JSON ───────────────────────────────────────

    @Test
    fun `an HTML page on a 2xx is BAD_RESPONSE with that status`() = runTest {
        // E.g. ngrok's browser-warning or offline page served with 200.
        rig.enqueue(200, "<!DOCTYPE html><html><body>ngrok</body></html>", contentType = "text/html")

        val error = expectApiError { rig.api.home() }

        assertThat(error.status).isEqualTo(200)
        assertThat(error.code).isEqualTo(ApiErrorCodes.BAD_RESPONSE)
        assertThat(error.message).isEqualTo("Something went wrong on our side. Please try again.")
        assertThat(error.message).doesNotContain("ngrok")
    }

    @Test
    fun `JSON on a 2xx that breaks the contract is BAD_RESPONSE`() = runTest {
        rig.enqueue(200, """{"unexpected":true}""")

        val error = expectApiError { rig.api.session() }

        assertThat(error.status).isEqualTo(200)
        assertThat(error.code).isEqualTo(ApiErrorCodes.BAD_RESPONSE)
        assertThat(error.cause).isInstanceOf(kotlinx.serialization.SerializationException::class.java)
    }

    @Test
    fun `an empty 2xx body is BAD_RESPONSE`() = runTest {
        rig.enqueue(200, "")

        val error = expectApiError { rig.api.home() }

        assertThat(error.code).isEqualTo(ApiErrorCodes.BAD_RESPONSE)
    }

    @Test
    fun `a 204 where a body is required is BAD_RESPONSE`() = runTest {
        rig.server.enqueue(MockResponse().setResponseCode(204))

        val error = expectApiError { rig.api.notifications() }

        assertThat(error.status).isEqualTo(204)
        assertThat(error.code).isEqualTo(ApiErrorCodes.BAD_RESPONSE)
    }

    // ── 4xx / 5xx ───────────────────────────────────────────────────────────

    @Test
    fun `a JSON error body becomes the ApiError`() = runTest {
        rig.enqueueFixture("playback.locked.error403.json", status = 403)

        val error = expectApiError { rig.api.playback("locked-session") }

        assertThat(error.status).isEqualTo(403)
        assertThat(error.error).isEqualTo(ApiError(code = "NOT_ENTITLED", message = "Yoga subscription required"))
        assertThat(error.message).isEqualTo("Yoga subscription required")
        assertThat(error.isNetworkFailure).isFalse()
    }

    @Test
    fun `validation errors keep zod's string arrays per field`() = runTest {
        // Exactly what runs.ts and diet.ts send: zod's flatten().fieldErrors.
        rig.enqueue(
            400,
            """
            {"code":"INVALID_BODY","message":"Invalid lead payload",
             "fields":{"phone":["String must contain at least 8 character(s)","Invalid"],"name":["Required"]}}
            """.trimIndent(),
        )

        val error = expectApiError { rig.api.content() }

        assertThat(error.code).isEqualTo("INVALID_BODY")
        assertThat(error.error.fields).containsExactly(
            "phone", listOf("String must contain at least 8 character(s)", "Invalid"),
            "name", listOf("Required"),
        )
        assertThat(error.error.fieldMessage("phone")).isEqualTo("String must contain at least 8 character(s)")
    }

    @Test
    fun `validation errors in the TS shape - one string per field - also parse`() = runTest {
        rig.enqueue(400, """{"code":"INVALID_BODY","message":"Invalid","fields":{"phone":"Too short"}}""")

        val error = expectApiError { rig.api.content() }

        assertThat(error.error.fields).containsExactly("phone", listOf("Too short"))
    }

    @Test
    fun `a JSON error without a code is UNKNOWN but keeps the server's message`() = runTest {
        // Fastify's own 404 for an unknown route.
        rig.enqueue(404, """{"message":"Route GET:/v1/nope not found","error":"Not Found","statusCode":404}""")

        val error = expectApiError { rig.api.content() }

        assertThat(error.status).isEqualTo(404)
        assertThat(error.error).isEqualTo(ApiError(code = "UNKNOWN", message = "Route GET:/v1/nope not found"))
    }

    @Test
    fun `an HTML error page is UNKNOWN with the generic message`() = runTest {
        rig.enqueue(502, "<html><body><h1>502 Bad Gateway</h1></body></html>", contentType = "text/html")

        val error = expectApiError { rig.api.content() }

        assertThat(error.status).isEqualTo(502)
        assertThat(error.error).isEqualTo(ApiError(code = "UNKNOWN", message = "Something went wrong."))
    }

    @Test
    fun `an empty error body is UNKNOWN`() = runTest {
        rig.enqueue(500, "")

        val error = expectApiError { rig.api.content() }

        assertThat(error.error).isEqualTo(ApiError(code = "UNKNOWN", message = "Something went wrong."))
    }

    @Test
    fun `malformed fields don't lose the code and message`() = runTest {
        rig.enqueue(400, """{"code":"INVALID_BODY","message":"Invalid","fields":["not","a","map"]}""")

        val error = expectApiError { rig.api.content() }

        assertThat(error.code).isEqualTo("INVALID_BODY")
        assertThat(error.message).isEqualTo("Invalid")
    }

    // ── ApiResult ───────────────────────────────────────────────────────────

    @Test
    fun `apiResult turns the exception into ApiResult Err and success into Ok`() = runTest {
        rig.enqueueFixture("referral.json")
        rig.enqueue(409, """{"code":"OWN_REFERRAL_CODE","message":"You can’t use your own referral code."}""")

        val ok = apiResult { rig.api.referral() }
        val err = apiResult { rig.api.referral() }

        assertThat((ok as ApiResult.Ok).data.code).isEqualTo("THATHON")
        assertThat((err as ApiResult.Err).error.code).isEqualTo("OWN_REFERRAL_CODE")
    }
}
