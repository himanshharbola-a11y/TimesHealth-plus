package timeshealth.app.core.domain

import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

/** apps/mobile/src/lib/time.ts: server clock and display formats. */
class TimeTest {

    private fun at(iso: String): Instant = Instant.parse(iso)
    private fun ms(iso: String): Long = at(iso).toEpochMilli()

    /** A device clock the test can move. */
    private class TestClock(var millis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
        override fun millis(): Long = millis
    }

    // ── server clock ────────────────────────────────────────────────────────

    @Test
    fun `before any sync the server clock is the device clock`() {
        val device = TestClock(ms("2026-10-06T00:00:00Z"))
        val clock = ServerClock(device)
        assertThat(clock.skewMs).isEqualTo(0)
        assertThat(clock.nowMs()).isEqualTo(device.millis)
    }

    @Test
    fun `a device running 5 minutes slow is corrected, and keeps ticking`() {
        val device = TestClock(ms("2026-10-06T00:25:00Z"))
        val clock = ServerClock(device)
        assertThat(clock.syncServerTime("2026-10-06T00:30:00.000Z")).isTrue()
        assertThat(clock.skewMs).isEqualTo(5 * 60_000)
        assertThat(clock.now()).isEqualTo(at("2026-10-06T00:30:00Z"))
        device.millis += 61_000
        assertThat(clock.now()).isEqualTo(at("2026-10-06T00:31:01Z"))
    }

    @Test
    fun `a device running fast gets a negative skew`() {
        val device = TestClock(ms("2026-10-06T00:40:00Z"))
        val clock = ServerClock(device)
        clock.syncServerTime(ms("2026-10-06T00:30:00Z"))
        assertThat(clock.skewMs).isEqualTo(-10 * 60_000)
        assertThat(clock.nowMs()).isEqualTo(ms("2026-10-06T00:30:00Z"))
    }

    @Test
    fun `an offset server time is understood, and a garbage one keeps the last skew`() {
        val device = TestClock(ms("2026-10-06T00:00:00Z"))
        val clock = ServerClock(device)
        assertThat(clock.syncServerTime("2026-10-06T06:00:00+05:30")).isTrue()
        assertThat(clock.skewMs).isEqualTo(30 * 60_000)
        assertThat(clock.syncServerTime("garbage")).isFalse()
        assertThat(clock.skewMs).isEqualTo(30 * 60_000)
    }

    @Test
    fun `parseIsoInstant`() {
        assertThat(parseIsoInstant("2026-10-06T00:30:00.000Z")).isEqualTo(at("2026-10-06T00:30:00Z"))
        assertThat(parseIsoInstant("2026-10-06T06:00+05:30")).isEqualTo(at("2026-10-06T00:30:00Z"))
        assertThat(parseIsoInstant("2026-10-06")).isEqualTo(at("2026-10-06T00:00:00Z"))
        assertThat(parseIsoInstant("2026-02-30T00:00:00Z")).isNull()
        assertThat(parseIsoInstant("")).isNull()
    }

    @Test
    fun `secondsUntil rounds half-up and never goes negative`() {
        val now = ms("2026-10-06T00:00:00Z")
        assertThat(secondsUntil(now + 240_000, now)).isEqualTo(240)
        assertThat(secondsUntil(now + 1_499, now)).isEqualTo(1)
        assertThat(secondsUntil(now + 1_500, now)).isEqualTo(2)
        assertThat(secondsUntil(now, now)).isEqualTo(0)
        assertThat(secondsUntil(now - 10_000, now)).isEqualTo(0)
    }

    // ── formatCountdown ─────────────────────────────────────────────────────

    @Test
    fun `formatCountdown`() {
        assertThat(formatCountdown(-5)).isEqualTo("Starting now")
        assertThat(formatCountdown(0)).isEqualTo("Starting now")
        assertThat(formatCountdown(1)).isEqualTo("Starts in 1s")
        assertThat(formatCountdown(59)).isEqualTo("Starts in 59s")
        assertThat(formatCountdown(60)).isEqualTo("Starts in 1 min")
        assertThat(formatCountdown(240)).isEqualTo("Starts in 4 min")
        assertThat(formatCountdown(3_599)).isEqualTo("Starts in 59 min")
        assertThat(formatCountdown(3_600)).isEqualTo("Starts in 1h 0m")
        assertThat(formatCountdown(3_661)).isEqualTo("Starts in 1h 1m")
        assertThat(formatCountdown(90_000)).isEqualTo("Starts in 25h 0m")
    }

    // ── formatPace / formatDuration ─────────────────────────────────────────

    @Test
    fun `formatPace rounds the total first - 13 59 point 6 is 14'00 not 13'60`() {
        assertThat(formatPace(839.6)).isEqualTo("14'00\" /km")
        assertThat(formatPace(839.4)).isEqualTo("13'59\" /km")
        assertThat(formatPace(839.5)).isEqualTo("14'00\" /km") // half-up, like Math.round
        assertThat(formatPace(59.5)).isEqualTo("1'00\" /km")
    }

    @Test
    fun `formatPace`() {
        assertThat(formatPace(358.0)).isEqualTo("5'58\" /km")
        assertThat(formatPace(300.0)).isEqualTo("5'00\" /km")
        assertThat(formatPace(3_600.0)).isEqualTo("60'00\" /km")
        assertThat(formatPace(0.0)).isEqualTo("--'--\" /km")
        assertThat(formatPace(-1.0)).isEqualTo("--'--\" /km")
        assertThat(formatPace(Double.NaN)).isEqualTo("--'--\" /km")
        assertThat(formatPace(Double.POSITIVE_INFINITY)).isEqualTo("--'--\" /km")
    }

    @Test
    fun `formatDuration`() {
        assertThat(formatDuration(0)).isEqualTo("00:00")
        assertThat(formatDuration(59)).isEqualTo("00:59")
        assertThat(formatDuration(61)).isEqualTo("01:01")
        assertThat(formatDuration(3_599)).isEqualTo("59:59")
        assertThat(formatDuration(3_600)).isEqualTo("1:00:00")
        assertThat(formatDuration(36_061)).isEqualTo("10:01:01")
    }

    // ── dates and times (IST) ───────────────────────────────────────────────

    @Test
    fun `formatSessionDate prints what en-IN ICU printed on the RN app`() {
        assertThat(formatSessionDate(at("2026-10-15T00:30:00Z"))).isEqualTo("Thu, 15 Oct, 6:00 am")
        assertThat(formatSessionDate(at("2026-09-03T13:05:00Z"))).isEqualTo("Thu, 3 Sept, 6:35 pm")
        assertThat(formatSessionDate(at("2026-01-01T06:30:00Z"))).isEqualTo("Thu, 1 Jan, 12:00 pm")
        // 18:35 UTC on the 5th is 00:05 IST on the 6th.
        assertThat(formatSessionDate(at("2026-10-05T18:35:00Z"))).isEqualTo("Tue, 6 Oct, 12:05 am")
    }

    @Test
    fun `formatSessionDate can be shown in another zone`() {
        assertThat(formatSessionDate(at("2026-10-15T00:30:00Z"), ZoneOffset.UTC)).isEqualTo("Thu, 15 Oct, 12:30 am")
    }

    @Test
    fun `formatTimeOfDay`() {
        assertThat(formatTimeOfDay(at("2026-10-06T00:30:00Z"))).isEqualTo("6:00 am")
        assertThat(formatTimeOfDay(at("2026-10-05T18:35:00Z"))).isEqualTo("12:05 am")
        assertThat(formatTimeOfDay(at("2026-10-06T14:00:00Z"))).isEqualTo("7:30 pm")
    }

    @Test
    fun `formatDayAndTime - today, tomorrow, then the weekday`() {
        val now = ms("2026-10-06T04:30:00Z") // Tue 10:00 IST
        assertThat(formatDayAndTime(at("2026-10-06T14:00:00Z"), now)).isEqualTo("Today · 7:30 pm")
        assertThat(formatDayAndTime(at("2026-10-06T23:45:00Z"), now)).isEqualTo("Tomorrow · 5:15 am")
        assertThat(formatDayAndTime(at("2026-10-08T00:30:00Z"), now)).isEqualTo("Thu · 6:00 am")
        assertThat(formatDayAndTime(at("2026-10-05T00:30:00Z"), now)).isEqualTo("Mon · 6:00 am")
    }

    @Test
    fun `formatDayAndTime - Tomorrow begins at IST midnight`() {
        val now = ms("2026-10-05T18:29:00Z") // Mon 23:59 IST
        assertThat(formatDayAndTime(at("2026-10-05T18:00:00Z"), now)).isEqualTo("Today · 11:30 pm")
        assertThat(formatDayAndTime(at("2026-10-05T18:30:00Z"), now)).isEqualTo("Tomorrow · 12:00 am")
        // One minute later it is the 6th in IST, though still the 5th in UTC.
        val later = ms("2026-10-05T18:30:00Z")
        assertThat(formatDayAndTime(at("2026-10-05T18:30:00Z"), later)).isEqualTo("Today · 12:00 am")
    }
}
