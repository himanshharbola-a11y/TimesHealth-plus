package timeshealth.app.core.domain

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

/** ProfileDrawer parseDob: DD/MM/YYYY, a real date, not in the future, age 13–100. */
class DobTest {

    private val today = LocalDate.of(2026, 10, 6)

    private fun parse(dd: String, mm: String, yyyy: String, on: LocalDate = today) = parseDob(dd, mm, yyyy, on)
    private fun errorOf(dd: String, mm: String, yyyy: String, on: LocalDate = today) =
        (parse(dd, mm, yyyy, on) as? DobResult.Invalid)?.error

    @Test
    fun `a valid date gives the ISO the API takes`() {
        assertThat(parse("01", "02", "1990")).isEqualTo(DobResult.Valid(LocalDate.of(1990, 2, 1), "1990-02-01T00:00:00.000Z"))
        // One-digit day and month are allowed.
        assertThat(parse("1", "2", "1990")).isEqualTo(DobResult.Valid(LocalDate.of(1990, 2, 1), "1990-02-01T00:00:00.000Z"))
    }

    @Test
    fun `the shape must be DD MM YYYY digits`() {
        assertThat(errorOf("", "02", "1990")).isEqualTo(DobError.FORMAT)
        assertThat(errorOf("123", "02", "1990")).isEqualTo(DobError.FORMAT)
        assertThat(errorOf("01", "2a", "1990")).isEqualTo(DobError.FORMAT)
        assertThat(errorOf("01", "02", "90")).isEqualTo(DobError.FORMAT)
        assertThat(errorOf("01", "02", "19900")).isEqualTo(DobError.FORMAT)
        assertThat(errorOf(" 1", "02", "1990")).isEqualTo(DobError.FORMAT)
    }

    @Test
    fun `the date must exist`() {
        assertThat(errorOf("31", "04", "1990")).isEqualTo(DobError.NO_SUCH_DATE)
        assertThat(errorOf("00", "01", "1990")).isEqualTo(DobError.NO_SUCH_DATE)
        assertThat(errorOf("15", "13", "1990")).isEqualTo(DobError.NO_SUCH_DATE)
        assertThat(errorOf("15", "00", "1990")).isEqualTo(DobError.NO_SUCH_DATE)
        assertThat(errorOf("32", "01", "1990")).isEqualTo(DobError.NO_SUCH_DATE)
        assertThat(parse("31", "12", "1990")).isInstanceOf(DobResult.Valid::class.java)
    }

    @Test
    fun `leap day - only in leap years, 1900 isn't one and 2000 is`() {
        assertThat(parse("29", "02", "2000")).isInstanceOf(DobResult.Valid::class.java)
        assertThat(parse("29", "02", "1996")).isInstanceOf(DobResult.Valid::class.java)
        assertThat(errorOf("29", "02", "2001")).isEqualTo(DobError.NO_SUCH_DATE)
        assertThat(errorOf("29", "02", "1900", on = LocalDate.of(1990, 1, 1))).isEqualTo(DobError.NO_SUCH_DATE)
    }

    @Test
    fun `leap-day birthday - 13 on 1 March in a non-leap year, not on 28 Feb`() {
        assertThat(errorOf("29", "02", "2012", on = LocalDate.of(2025, 2, 28))).isEqualTo(DobError.TOO_YOUNG)
        assertThat(parse("29", "02", "2012", on = LocalDate.of(2025, 3, 1)))
            .isEqualTo(DobResult.Valid(LocalDate.of(2012, 2, 29), "2012-02-29T00:00:00.000Z"))
        // In a leap year the birthday itself comes round.
        assertThat(parse("29", "02", "2012", on = LocalDate.of(2028, 2, 29))).isInstanceOf(DobResult.Valid::class.java)
    }

    @Test
    fun `not in the future - tomorrow is refused, today is merely too young`() {
        assertThat(errorOf("07", "10", "2026")).isEqualTo(DobError.FUTURE)
        assertThat(errorOf("01", "11", "2026")).isEqualTo(DobError.FUTURE)
        assertThat(errorOf("01", "01", "2027")).isEqualTo(DobError.FUTURE)
        assertThat(errorOf("06", "10", "2026")).isEqualTo(DobError.TOO_YOUNG)
    }

    @Test
    fun `age 13 starts on the 13th birthday`() {
        assertThat(parse("06", "10", "2013")).isInstanceOf(DobResult.Valid::class.java)
        assertThat(errorOf("07", "10", "2013")).isEqualTo(DobError.TOO_YOUNG)
        assertThat(errorOf("01", "01", "2014")).isEqualTo(DobError.TOO_YOUNG)
    }

    @Test
    fun `age 100 is allowed, 101 is not`() {
        assertThat(parse("06", "10", "1926")).isInstanceOf(DobResult.Valid::class.java) // exactly 100
        assertThat(parse("07", "10", "1925")).isInstanceOf(DobResult.Valid::class.java) // 100, 101 tomorrow
        assertThat(errorOf("06", "10", "1925")).isEqualTo(DobError.TOO_OLD) // 101 today
        assertThat(errorOf("01", "01", "1900")).isEqualTo(DobError.TOO_OLD)
    }

    @Test
    fun `years 0000-0099 keep the TS Date-UTC quirk for which error shows`() {
        // Date.UTC(0, 2, 0) is February 1900 (28 days), so 29/02/0000 "doesn't exist"…
        assertThat(errorOf("29", "02", "0000")).isEqualTo(DobError.NO_SUCH_DATE)
        // …while 29/02/0004 (1904, a leap year) exists and is simply too old.
        assertThat(errorOf("29", "02", "0004")).isEqualTo(DobError.TOO_OLD)
    }

    @Test
    fun `error copy is the app's`() {
        assertThat(DobError.FORMAT.message).isEqualTo("Enter your date of birth as DD / MM / YYYY.")
        assertThat(DobError.TOO_YOUNG.message).isEqualTo("You need to be at least 13 to use TimesHealth+.")
        assertThat(DobError.TOO_OLD.message).isEqualTo("Enter a date of birth within the last 100 years.")
    }

    @Test
    fun `splitDob and formatDob read the stored UTC midnight in UTC`() {
        assertThat(splitDob("1990-02-01T00:00:00.000Z")).isEqualTo(DobParts("01", "02", "1990"))
        assertThat(splitDob("not a date")).isEqualTo(DobParts("", "", ""))
        assertThat(formatDob("1990-09-01T00:00:00.000Z")).isEqualTo("1 Sep 1990")
        assertThat(formatDob("2012-02-29T00:00:00.000Z")).isEqualTo("29 Feb 2012")
        assertThat(formatDob("")).isEqualTo("—")
    }

    @Test
    fun `round trip - what parseDob saves, splitDob reads back`() {
        val saved = parse("29", "02", "2000") as DobResult.Valid
        assertThat(splitDob(saved.iso)).isEqualTo(DobParts("29", "02", "2000"))
    }
}
