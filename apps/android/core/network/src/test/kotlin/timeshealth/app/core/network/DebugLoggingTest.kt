package timeshealth.app.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import timeshealth.app.core.model.UpdateProfileRequest

/** The debug log is useful, and never a credential or personal-data leak. */
class DebugLoggingTest {

    private val rig = ApiTestRig(debug = true)

    @After
    fun tearDown() = rig.close()

    private val log: String get() = rig.logLines.joinToString("\n")

    @Test
    fun `logs method, path and status`() = runTest {
        rig.enqueueFixture("home.free.json")

        rig.api.home()

        assertThat(rig.logLines.first()).isEqualTo("--> GET /v1/home")
        assertThat(rig.logLines[1]).matches("""<-- 200 GET /v1/home \(\d+ ms\)""")
    }

    @Test
    fun `never logs the token, any header, or bodies of a credentialed call`() = runTest {
        rig.enqueueFixture("session.both.json")
        rig.enqueue(body = """{"profile":${Fixtures.tree("session.both.json")["profile"]}}""")

        rig.api.session()
        rig.api.updateProfile(UpdateProfileRequest(phone = "+919876543210", email = "asha@example.com"))

        assertThat(log).doesNotContain(ApiTestRig.TOKEN)
        assertThat(log).doesNotContain("Bearer")
        assertThat(log.lowercase()).doesNotContain("authorization")
        assertThat(log).doesNotContain("9876543210")
        assertThat(log).doesNotContain("asha@example.com")
        assertThat(log).doesNotContain("entitlements") // a response body field
    }

    @Test
    fun `never logs the query string - it carries the user's location`() = runTest {
        rig.enqueueFixture("marathon-events.free.json")

        rig.api.marathonEvents(lat = 28.6139, lng = 77.209)

        assertThat(log).contains("GET /v1/marathon/events")
        assertThat(log).doesNotContain("28.6139")
        assertThat(log).doesNotContain("lat=")
    }

    @Test
    fun `logs the body of the anonymous config call`() = runTest {
        rig.enqueueFixture("config.json")

        rig.api.config()

        assertThat(log).contains("minSupportedAppVersion")
    }

    @Test
    fun `logs failures without throwing`() = runTest {
        rig.server.shutdown()

        expectApiError { rig.api.home() }

        assertThat(log).contains("<-- FAILED GET /v1/home")
    }

    @Test
    fun `release builds log nothing`() = runTest {
        ApiTestRig(debug = false).use { release ->
            release.enqueueFixture("config.json")

            release.api.config()

            assertThat(release.logLines).isEmpty()
        }
    }
}
