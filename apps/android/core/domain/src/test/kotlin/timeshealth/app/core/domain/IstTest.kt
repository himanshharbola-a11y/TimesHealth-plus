package timeshealth.app.core.domain

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import org.junit.Test

/** IST calendar helpers (apps/api/src/time.ts). IST midnight is 18:30 UTC the day before. */
class IstTest {

    private fun at(iso: String): Instant = Instant.parse(iso)

    // ── IST midnight ────────────────────────────────────────────────────────

    @Test
    fun `istDate flips at IST midnight, not UTC midnight`() {
        assertThat(istDate(at("2026-10-05T18:29:59.999Z"))).isEqualTo(LocalDate.of(2026, 10, 5))
        assertThat(istDate(at("2026-10-05T18:30:00Z"))).isEqualTo(LocalDate.of(2026, 10, 6))
        // 00:30 UTC is already 06:00 IST on the same date.
        assertThat(istDate(at("2026-10-06T00:30:00Z"))).isEqualTo(LocalDate.of(2026, 10, 6))
    }

    @Test
    fun `istDateOnly is midnight IST as an instant`() {
        assertThat(istDateOnly(at("2026-10-06T10:00:00Z"))).isEqualTo(at("2026-10-05T18:30:00Z"))
        assertThat(istDateOnly(at("2026-10-05T18:30:00Z"))).isEqualTo(at("2026-10-05T18:30:00Z"))
        assertThat(istDateOnly(at("2026-10-05T18:29:59.999Z"))).isEqualTo(at("2026-10-04T18:30:00Z"))
    }

    @Test
    fun `istCalendarDate is UTC midnight of the IST day - not a day early`() {
        // 01:30 IST on 6 Oct: the UTC date is still the 5th.
        val instant = at("2026-10-05T20:00:00Z")
        assertThat(istCalendarDate(instant)).isEqualTo(at("2026-10-06T00:00:00Z"))
        // The pitfall it exists for: istDateOnly's UTC date part is the 5th.
        assertThat(istDateOnly(instant).toString()).startsWith("2026-10-05")
    }

    @Test
    fun `istDateString is the ledger key in IST`() {
        assertThat(istDateString(at("2026-10-05T18:29:59Z"))).isEqualTo("2026-10-05")
        assertThat(istDateString(at("2026-10-05T18:30:00Z"))).isEqualTo("2026-10-06")
        assertThat(istDateString(at("2026-01-01T00:00:00Z"))).isEqualTo("2026-01-01")
    }

    // ── month rollover ──────────────────────────────────────────────────────

    @Test
    fun `istMonthStart rolls over at IST midnight on the last day`() {
        assertThat(istMonthStart(at("2026-10-31T18:29:59.999Z"))).isEqualTo(at("2026-09-30T18:30:00Z"))
        assertThat(istMonthStart(at("2026-10-31T18:30:00Z"))).isEqualTo(at("2026-10-31T18:30:00Z"))
        assertThat(istMonthStart(at("2026-10-15T06:00:00Z"))).isEqualTo(at("2026-09-30T18:30:00Z"))
    }

    @Test
    fun `istMonthStart rolls over the year`() {
        assertThat(istMonthStart(at("2026-12-31T18:30:00Z"))).isEqualTo(at("2026-12-31T18:30:00Z"))
        assertThat(istDate(istMonthStart(at("2026-12-31T18:30:00Z")))).isEqualTo(LocalDate.of(2027, 1, 1))
        assertThat(istMonthStart(at("2026-12-31T18:29:00Z"))).isEqualTo(at("2026-11-30T18:30:00Z"))
    }

    // ── batch instants ──────────────────────────────────────────────────────

    @Test
    fun `batchInstant resolves HH-mm on the IST day`() {
        val noonIst = at("2026-10-06T06:30:00Z")
        assertThat(batchInstant("06:00", 0, noonIst)).isEqualTo(at("2026-10-06T00:30:00Z"))
        assertThat(batchInstant("18:30", 0, noonIst)).isEqualTo(at("2026-10-06T13:00:00Z"))
        assertThat(batchInstant("5:15", 0, noonIst)).isEqualTo(at("2026-10-05T23:45:00Z"))
    }

    @Test
    fun `batchInstant uses the IST day even when the UTC date is the day before`() {
        // 01:30 IST on 6 Oct = 20:00 UTC on 5 Oct. Today's 05:15 batch is 6 Oct IST.
        val from = at("2026-10-05T20:00:00Z")
        assertThat(batchInstant("05:15", 0, from)).isEqualTo(at("2026-10-05T23:45:00Z"))
        assertThat(istDate(batchInstant("05:15", 0, from)!!)).isEqualTo(LocalDate.of(2026, 10, 6))
    }

    @Test
    fun `batchInstant dayOffset rolls over month and year ends`() {
        assertThat(batchInstant("06:00", 1, at("2026-10-31T10:00:00Z"))).isEqualTo(at("2026-11-01T00:30:00Z"))
        assertThat(batchInstant("06:00", 1, at("2026-12-31T10:00:00Z"))).isEqualTo(at("2027-01-01T00:30:00Z"))
        assertThat(batchInstant("06:00", -1, at("2026-03-01T10:00:00Z"))).isEqualTo(at("2026-02-28T00:30:00Z"))
    }

    @Test
    fun `batchInstant reads times the way Number() did`() {
        val from = at("2026-10-06T06:30:00Z")
        assertThat(batchInstant("7", 0, from)).isEqualTo(at("2026-10-06T01:30:00Z")) // minutes default to 0
        assertThat(batchInstant(" 06 : 00 ", 0, from)).isEqualTo(at("2026-10-06T00:30:00Z"))
        assertThat(batchInstant("24:00", 0, from)).isEqualTo(at("2026-10-06T18:30:00Z")) // next IST midnight
        assertThat(batchInstant("ab:cd", 0, from)).isNull()
        assertThat(batchInstant("06:3x", 0, from)).isNull()
    }

    @Test
    fun `istDaysUntil counts IST calendar days`() {
        val beforeMidnight = at("2026-10-05T18:29:00Z") // 23:59 IST
        val afterMidnight = at("2026-10-05T18:31:00Z") // 00:01 IST
        assertThat(istDaysUntil(afterMidnight, beforeMidnight)).isEqualTo(1)
        assertThat(istDaysUntil(beforeMidnight, afterMidnight)).isEqualTo(-1)
        assertThat(istDaysUntil(at("2026-10-06T17:00:00Z"), at("2026-10-05T19:00:00Z"))).isEqualTo(0)
        assertThat(istDaysUntil(at("2026-11-01T00:30:00Z"), at("2026-10-31T00:30:00Z"))).isEqualTo(1)
    }

    @Test
    fun `istHour is the IST hour`() {
        assertThat(istHour(at("2026-10-05T18:29:00Z"))).isEqualTo(23)
        assertThat(istHour(at("2026-10-05T18:30:00Z"))).isEqualTo(0)
        assertThat(istHour(at("2026-10-06T00:30:00Z"))).isEqualTo(6)
    }

    // ── clock times ─────────────────────────────────────────────────────────

    @Test
    fun `minutesOfDay`() {
        assertThat(minutesOfDay("05:15")).isEqualTo(315)
        assertThat(minutesOfDay("5:15")).isEqualTo(315)
        assertThat(minutesOfDay("16:45")).isEqualTo(1005)
        assertThat(minutesOfDay("00:00")).isEqualTo(0)
        assertThat(minutesOfDay("7")).isEqualTo(420)
        assertThat(minutesOfDay("")).isEqualTo(0) // Number('') is 0
        assertThat(minutesOfDay("ab:cd")).isNull()
        assertThat(minutesOfDay("5.5:00")).isNull()
    }

    @Test
    fun `byClockTime sorts by the clock, malformed last`() {
        val sorted = listOf("16:45", "05:15", "bad", "18:30", "6:00").sortedWith(byClockTime)
        assertThat(sorted).containsExactly("05:15", "6:00", "16:45", "18:30", "bad").inOrder()
    }

    @Test
    fun `formatBatchTime`() {
        assertThat(formatBatchTime("06:30")).isEqualTo("6:30 AM")
        assertThat(formatBatchTime("00:05")).isEqualTo("12:05 AM")
        assertThat(formatBatchTime("11:59")).isEqualTo("11:59 AM")
        assertThat(formatBatchTime("12:00")).isEqualTo("12:00 PM")
        assertThat(formatBatchTime("18:30")).isEqualTo("6:30 PM")
        assertThat(formatBatchTime("5:7")).isEqualTo("5:07 AM")
        assertThat(formatBatchTime("garbage")).isEqualTo("garbage")
    }

    // ── greeting and windows ────────────────────────────────────────────────

    @Test
    fun `greetingFor switches at 12 and 17 IST and names the IST weekday`() {
        assertThat(greetingFor(at("2026-10-06T06:29:00Z"))).isEqualTo("Good morning · Tuesday") // 11:59
        assertThat(greetingFor(at("2026-10-06T06:30:00Z"))).isEqualTo("Good afternoon · Tuesday") // 12:00
        assertThat(greetingFor(at("2026-10-06T11:29:00Z"))).isEqualTo("Good afternoon · Tuesday") // 16:59
        assertThat(greetingFor(at("2026-10-06T11:30:00Z"))).isEqualTo("Good evening · Tuesday") // 17:00
        // 19:00 UTC Monday is 00:30 IST Tuesday.
        assertThat(greetingFor(at("2026-10-05T19:00:00Z"))).isEqualTo("Good morning · Tuesday")
    }

    @Test
    fun `formatIstWindow - spanning days, same day, and across months`() {
        assertThat(formatIstWindow(at("2026-10-27T04:30:00Z"), at("2026-10-28T12:30:00Z")))
            .isEqualTo("27–28 Oct · 10 AM to 6 PM")
        assertThat(formatIstWindow(at("2026-10-27T01:00:00Z"), at("2026-10-27T03:30:00Z")))
            .isEqualTo("27 Oct · 6:30 AM to 9 AM")
        assertThat(formatIstWindow(at("2026-09-30T04:30:00Z"), at("2026-10-02T12:30:00Z")))
            .isEqualTo("30 Sept – 2 Oct · 10 AM to 6 PM")
        // Midnight and noon IST read as 12.
        assertThat(formatIstWindow(at("2026-10-26T18:30:00Z"), at("2026-10-27T06:30:00Z")))
            .isEqualTo("27 Oct · 12 AM to 12 PM")
    }
}
