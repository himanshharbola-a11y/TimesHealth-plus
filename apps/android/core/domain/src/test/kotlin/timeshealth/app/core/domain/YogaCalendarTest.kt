package timeshealth.app.core.domain

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

/** The Yoga tracker's IST month calendar (PRD §7.1). */
class YogaCalendarTest {

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    /** Tue 6 Oct 2026, 10:00 IST. */
    private val now = ms("2026-10-06T04:30:00Z")

    private fun YogaMonth.stateOf(day: Int): DayState =
        weeks.flatten().filterNotNull().first { it.day == day }.state

    @Test
    fun `October 2026 - starts on a Thursday, 31 days, five full weeks`() {
        val m = yogaMonth(now, emptyList(), null)
        assertThat(m.year).isEqualTo(2026)
        assertThat(m.month).isEqualTo(10)
        assertThat(m.title).isEqualTo("October 2026")
        assertThat(m.today).isEqualTo(6)
        assertThat(m.leadingBlanks).isEqualTo(4)
        assertThat(m.daysInMonth).isEqualTo(31)
        assertThat(m.weeks).hasSize(5)
        assertThat(m.weeks.all { it.size == 7 }).isTrue()
        assertThat(m.weeks[0].take(4)).containsExactly(null, null, null, null)
        assertThat(m.weeks[0][4]?.day).isEqualTo(1)
        assertThat(m.weeks[4][6]?.day).isEqualTo(31)
    }

    @Test
    fun `grid shapes - trailing blanks, a four-week February and a six-week month`() {
        val nov = yogaMonth(ms("2026-11-10T04:30:00Z"), emptyList(), null) // starts Sunday
        assertThat(nov.leadingBlanks).isEqualTo(0)
        assertThat(nov.weeks).hasSize(5)
        assertThat(nov.weeks[4].drop(2)).containsExactly(null, null, null, null, null)

        val feb = yogaMonth(ms("2026-02-10T04:30:00Z"), emptyList(), null) // Sunday the 1st, 28 days
        assertThat(feb.weeks).hasSize(4)
        assertThat(feb.weeks.flatten().none { it == null }).isTrue()

        val leapFeb = yogaMonth(ms("2028-02-10T04:30:00Z"), emptyList(), null)
        assertThat(leapFeb.daysInMonth).isEqualTo(29)
        assertThat(leapFeb.leadingBlanks).isEqualTo(2) // Tuesday

        val aug = yogaMonth(ms("2026-08-10T04:30:00Z"), emptyList(), null) // Saturday the 1st
        assertThat(aug.leadingBlanks).isEqualTo(6)
        assertThat(aug.weeks).hasSize(6)
    }

    @Test
    fun `the month and today roll over at IST midnight, not UTC`() {
        // 18:30 UTC on 31 Oct is 00:00 IST on 1 Nov.
        val nov = yogaMonth(ms("2026-10-31T18:30:00Z"), emptyList(), null)
        assertThat(nov.title).isEqualTo("November 2026")
        assertThat(nov.today).isEqualTo(1)
        val oct = yogaMonth(ms("2026-10-31T18:29:59Z"), emptyList(), null)
        assertThat(oct.title).isEqualTo("October 2026")
        assertThat(oct.today).isEqualTo(31)
        // And the year.
        assertThat(yogaMonth(ms("2026-12-31T18:30:00Z"), emptyList(), null).title).isEqualTo("January 2027")
    }

    @Test
    fun `day states - today, future, attended, missed and before tracking`() {
        val m = yogaMonth(now, listOf("2026-10-02", "2026-10-04", "2026-10-06", "2026-10-09"), "2026-10-03")
        assertThat(m.stateOf(1)).isEqualTo(DayState.NEUTRAL) // before tracking started
        assertThat(m.stateOf(2)).isEqualTo(DayState.ATTENDED) // attended counts even before trackingSince
        assertThat(m.stateOf(3)).isEqualTo(DayState.MISSED) // trackingSince itself can be missed
        assertThat(m.stateOf(4)).isEqualTo(DayState.ATTENDED)
        assertThat(m.stateOf(5)).isEqualTo(DayState.MISSED) // yesterday
        assertThat(m.stateOf(6)).isEqualTo(DayState.TODAY) // today wins over attended
        assertThat(m.stateOf(7)).isEqualTo(DayState.FUTURE)
        assertThat(m.stateOf(9)).isEqualTo(DayState.FUTURE) // a future mark is still future
    }

    @Test
    fun `today is never missed, even unattended`() {
        val m = yogaMonth(now, emptyList(), "2026-10-01")
        assertThat(m.stateOf(6)).isEqualTo(DayState.TODAY)
        assertThat((1..5).map { m.stateOf(it) }).containsExactly(
            DayState.MISSED, DayState.MISSED, DayState.MISSED, DayState.MISSED, DayState.MISSED,
        )
    }

    @Test
    fun `no tracking start means nothing is missed`() {
        val m = yogaMonth(now, listOf("2026-10-02"), null)
        assertThat(m.stateOf(1)).isEqualTo(DayState.NEUTRAL)
        assertThat(m.stateOf(2)).isEqualTo(DayState.ATTENDED)
        assertThat(yogaMonth(now, emptyList(), "").stateOf(1)).isEqualTo(DayState.NEUTRAL)
    }

    @Test
    fun `tracking that began in an earlier month makes every past unattended day missed`() {
        val m = yogaMonth(now, emptyList(), "2025-06-15")
        assertThat((1..5).map { m.stateOf(it) }.toSet()).containsExactly(DayState.MISSED)
    }

    @Test
    fun `tracking that begins today or later leaves the past neutral`() {
        val m = yogaMonth(now, emptyList(), "2026-10-06")
        assertThat((1..5).map { m.stateOf(it) }.toSet()).containsExactly(DayState.NEUTRAL)
    }

    @Test
    fun `cells carry the ledger's ISO key`() {
        val m = yogaMonth(now, emptyList(), null)
        assertThat(m.weeks[0][4]?.isoDate).isEqualTo("2026-10-01")
        assertThat(m.weeks[4][6]?.isoDate).isEqualTo("2026-10-31")
    }

    @Test
    fun `a member abroad before 5-30 AM IST sees the IST day as today`() {
        // 23:00 UTC on 5 Oct (still the 5th in London) is 04:30 IST on the 6th.
        val m = yogaMonth(ms("2026-10-05T23:00:00Z"), emptyList(), "2026-10-01")
        assertThat(m.today).isEqualTo(6)
        assertThat(m.stateOf(5)).isEqualTo(DayState.MISSED)
    }

    @Test
    fun `weekday headers and accessibility labels`() {
        assertThat(WEEKDAY_INITIALS).containsExactly("S", "M", "T", "W", "T", "F", "S").inOrder()
        assertThat(calendarDayLabel(10, 5, DayState.ATTENDED)).isEqualTo("October 5, attended")
        assertThat(calendarDayLabel(10, 5, DayState.MISSED)).isEqualTo("October 5, missed")
        assertThat(calendarDayLabel(10, 6, DayState.TODAY)).isEqualTo("October 6, today")
        assertThat(calendarDayLabel(10, 7, DayState.FUTURE)).isEqualTo("October 7")
        assertThat(calendarDayLabel(10, 1, DayState.NEUTRAL)).isEqualTo("October 1")
    }
}
