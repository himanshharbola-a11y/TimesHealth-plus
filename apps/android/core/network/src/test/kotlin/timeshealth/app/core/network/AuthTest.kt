package timeshealth.app.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import timeshealth.app.core.model.SetSavedRequest

/** Credentials on the wire, and which responses end the session. */
class AuthTest {

    private val rig = ApiTestRig()

    @After
    fun tearDown() = rig.close()

    // ── Authorization header ────────────────────────────────────────────────

    @Test
    fun `authenticated calls carry the bearer token`() = runTest {
        rig.enqueueFixture("session.free.json")

        rig.api.session()

        assertThat(rig.takeRequest().getHeader("Authorization")).isEqualTo("Bearer ${ApiTestRig.TOKEN}")
    }

    @Test
    fun `the anonymous config call never carries a token, even when one is available`() = runTest {
        rig.enqueueFixture("config.json")

        rig.api.config()

        assertThat(rig.takeRequest().getHeader("Authorization")).isNull()
    }

    @Test
    fun `signed out - no Authorization header`() = runTest {
        rig.token = null
        rig.enqueueFixture("content.json")

        rig.api.content()

        assertThat(rig.takeRequest().getHeader("Authorization")).isNull()
    }

    @Test
    fun `a blank token counts as signed out`() = runTest {
        rig.token = "  "
        rig.enqueueFixture("content.json")

        rig.api.content()

        assertThat(rig.takeRequest().getHeader("Authorization")).isNull()
    }

    @Test
    fun `a failing token provider sends the request without a token instead of crashing`() = runTest {
        rig.tokenFailure = IllegalStateException("secure store unavailable")
        rig.enqueueFixture("content.json")

        rig.api.content()

        assertThat(rig.takeRequest().getHeader("Authorization")).isNull()
    }

    @Test
    fun `every request carries Accept json and the ngrok bypass header`() = runTest {
        rig.enqueueFixture("config.json")

        rig.api.config()

        val request = rig.takeRequest()
        assertThat(request.getHeader("Accept")).isEqualTo("application/json")
        assertThat(request.getHeader("ngrok-skip-browser-warning")).isEqualTo("1")
    }

    @Test
    fun `a body-less POST does not claim a JSON body`() = runTest {
        // Fastify answers 400 to an empty body declared as application/json.
        rig.enqueue(body = """{"profile":${Fixtures.tree("session.free.json")["profile"]}}""")

        rig.api.skipOnboarding()

        val request = rig.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.bodySize).isEqualTo(0)
        assertThat(request.getHeader("Content-Type")).isNull()
    }

    @Test
    fun `a POST with a body is sent as JSON`() = runTest {
        rig.enqueue(body = """{"saved":true}""")

        rig.api.setSaved("s1", SetSavedRequest(saved = true))

        assertThat(rig.takeRequest().getHeader("Content-Type")).startsWith("application/json")
    }

    // ── Which responses end the session ─────────────────────────────────────

    @Test
    fun `401 for a request that carried a token ends the session`() = runTest {
        rig.enqueue(401, """{"code":"UNAUTHORIZED","message":"Invalid or expired token"}""")

        val error = expectApiError { rig.api.home() }

        assertThat(rig.unauthorizedCalls.get()).isEqualTo(1)
        // The caller still gets the 401 itself.
        assertThat(error.status).isEqualTo(401)
        assertThat(error.code).isEqualTo("UNAUTHORIZED")
    }

    @Test
    fun `401 for a request that carried no token does not sign the user out`() = runTest {
        // E.g. a call that fired before the identity provider restored the saved login.
        rig.token = null
        rig.enqueue(401, """{"code":"UNAUTHORIZED","message":"Missing bearer token"}""")

        val error = expectApiError { rig.api.session() }

        assertThat(error.status).isEqualTo(401)
        assertThat(rig.unauthorizedCalls.get()).isEqualTo(0)
    }

    @Test
    fun `401 for a request whose token provider failed does not sign the user out`() = runTest {
        rig.tokenFailure = IllegalStateException("provider not ready")
        rig.enqueue(401, """{"code":"UNAUTHORIZED","message":"Missing bearer token"}""")

        expectApiError { rig.api.session() }

        assertThat(rig.unauthorizedCalls.get()).isEqualTo(0)
    }

    @Test
    fun `401 on the anonymous config call does not sign the user out`() = runTest {
        rig.enqueue(401, """{"code":"UNAUTHORIZED","message":"no"}""")

        expectApiError { rig.api.config() }

        assertThat(rig.unauthorizedCalls.get()).isEqualTo(0)
    }

    @Test
    fun `503 never signs the user out`() = runTest {
        // What the server sends when its database or the identity provider is unreachable.
        rig.enqueue(503, """{"code":"UNAVAILABLE","message":"Sign-in service temporarily unavailable"}""")

        val error = expectApiError { rig.api.session() }

        assertThat(error.status).isEqualTo(503)
        assertThat(error.code).isEqualTo("UNAVAILABLE")
        assertThat(rig.unauthorizedCalls.get()).isEqualTo(0)
    }

    @Test
    fun `403 is a refusal, not a sign-out`() = runTest {
        rig.enqueueFixture("yoga-attendance.free.error403.json", status = 403)

        val error = expectApiError { rig.api.yogaAttendance() }

        assertThat(error.code).isEqualTo("NOT_ENTITLED")
        assertThat(rig.unauthorizedCalls.get()).isEqualTo(0)
    }

    @Test
    fun `a throwing unauthorized handler cannot crash the call - the caller still gets the 401`() = runTest {
        ApiTestRig(onUnauthorized = { error("session store bug") }).use { broken ->
            broken.enqueue(401, """{"code":"UNAUTHORIZED","message":"Invalid or expired token"}""")

            val error = expectApiError { broken.api.home() }

            assertThat(error.status).isEqualTo(401)
        }
    }
}
