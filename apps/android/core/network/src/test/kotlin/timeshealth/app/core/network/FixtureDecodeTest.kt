package timeshealth.app.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import timeshealth.app.core.model.ApiError
import timeshealth.app.core.model.ApiJson

/**
 * Every recorded staging response (all personas, see core/model's fixtures) served through the
 * full HTTP stack must reach the caller exactly as ApiJson decodes it: success bodies as the
 * endpoint's type, `*.error<status>.json` as an [ApiRequestException] with that status.
 */
@RunWith(Parameterized::class)
class FixtureDecodeTest(private val fixture: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<String> = Fixtures.names.also { check(it.isNotEmpty()) { "No fixtures found" } }

        private val ERROR_STATUS = Regex("""\.error(\d{3})\.json$""")

        /** The endpoint each fixture was recorded from. Unmapped fixtures fail loudly. */
        private fun callFor(fixture: String): suspend TimesHealthApi.() -> Any =
            when (val endpoint = fixture.substringBefore('.')) {
                "bib-token" -> { { bibToken("e") } }
                "config" -> { { config() } }
                "content" -> { { content() } }
                "home" -> { { home() } }
                "marathon-events" -> { { marathonEvents() } }
                "notifications" -> { { notifications() } }
                "playback" -> { { playback("s") } }
                "race-detail" -> { { raceDetail("e") } }
                "referral" -> { { referral() } }
                "runs" -> { { runHistory() } }
                "session" -> { { session() } }
                "workshops" -> { { workshops() } }
                "yoga-attendance" -> { { yogaAttendance() } }
                "yoga-catalog" -> { { yogaCatalog() } }
                "yoga-mine" -> { { mySessions() } }
                "yoga-today" -> { { yogaToday() } }
                else -> error("Fixture $fixture has no endpoint: map '$endpoint' in FixtureDecodeTest.callFor")
            }
    }

    private val rig = ApiTestRig()

    @After
    fun tearDown() = rig.close()

    @Test
    fun `reaches the caller exactly as recorded`() = runTest {
        val errorStatus = ERROR_STATUS.find(fixture)?.groupValues?.get(1)?.toInt()
        rig.enqueueFixture(fixture, status = errorStatus ?: 200)
        val call = callFor(fixture)

        if (errorStatus == null) {
            val decoded = call(rig.api)
            val expected = ApiJson.decodeFromString(ApiJson.serializersModule.serializer(decoded.javaClass), Fixtures.text(fixture))
            assertThat(decoded).isEqualTo(expected)
        } else {
            val error = expectApiError { call(rig.api) }
            assertThat(error.status).isEqualTo(errorStatus)
            assertThat(error.error).isEqualTo(ApiJson.decodeFromString<ApiError>(Fixtures.text(fixture)))
        }
    }
}
