package timeshealth.app.core.network

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test

class ServerClockTest {

    private val rig = ApiTestRig()

    @After
    fun tearDown() = rig.close()

    private fun skewFor(fixture: String): Long {
        val serverTime = Fixtures.tree(fixture).getValue("serverTime").jsonPrimitive.content
        return Instant.parse(serverTime).toEpochMilli() - DEVICE_NOW_MS
    }

    // ── The clock itself ────────────────────────────────────────────────────

    @Test
    fun `now is device time until the first sync`() {
        val clock = ServerClock { 1_000L }

        assertThat(clock.now()).isEqualTo(1_000L)
        assertThat(clock.skewMs).isEqualTo(0L)
    }

    @Test
    fun `now is device time corrected by the measured skew`() {
        var device = DEVICE_NOW_MS
        val clock = ServerClock { device }

        assertThat(clock.sync("2026-10-06T08:00:05.000Z")).isTrue()
        assertThat(clock.skewMs).isEqualTo(5_000L)

        device += 60_000 // the correction holds as the device clock moves on
        assertThat(clock.now()).isEqualTo(Instant.parse("2026-10-06T08:01:05Z").toEpochMilli())
    }

    @Test
    fun `an explicit offset parses too`() {
        val clock = ServerClock { DEVICE_NOW_MS }

        assertThat(clock.sync("2026-10-06T13:30:00+05:30")).isTrue()
        assertThat(clock.skewMs).isEqualTo(0L)
    }

    @Test
    fun `an unparseable serverTime keeps the previous skew`() {
        val clock = ServerClock { DEVICE_NOW_MS }
        clock.sync("2026-10-06T07:59:58.000Z")

        assertThat(clock.sync("not a time")).isFalse()
        assertThat(clock.skewMs).isEqualTo(-2_000L)
    }

    // ── Fed by responses ────────────────────────────────────────────────────

    @Test
    fun `config, session and home responses sync the clock`() = runTest {
        rig.enqueueFixture("config.json")
        rig.api.config()
        assertThat(rig.clock.skewMs).isEqualTo(skewFor("config.json"))

        rig.enqueueFixture("session.yoga.json")
        rig.api.session()
        assertThat(rig.clock.skewMs).isEqualTo(skewFor("session.yoga.json"))

        rig.enqueueFixture("home.both.json")
        rig.api.home()
        assertThat(rig.clock.skewMs).isEqualTo(skewFor("home.both.json"))
        assertThat(rig.clock.now()).isEqualTo(DEVICE_NOW_MS + skewFor("home.both.json"))
    }

    @Test
    fun `responses without serverTime leave the clock alone`() = runTest {
        rig.enqueueFixture("yoga-today.json")
        rig.enqueueFixture("marathon-events.free.json")

        rig.api.yogaToday()
        rig.api.marathonEvents()

        assertThat(rig.clock.skewMs).isEqualTo(0L)
    }

    @Test
    fun `an error response leaves the clock alone`() = runTest {
        rig.enqueue(503, """{"code":"UNAVAILABLE","message":"Service temporarily unavailable"}""")

        expectApiError { rig.api.home() }

        assertThat(rig.clock.skewMs).isEqualTo(0L)
    }
}
