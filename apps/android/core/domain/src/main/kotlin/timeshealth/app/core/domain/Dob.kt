package timeshealth.app.core.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/*
 * Date of birth on the profile (PRD §10: used for race categories and
 * age-group results). Port of parseDob / splitDob / formatDob in
 * apps/mobile/src/components/ProfileDrawer.tsx.
 */

/** Why a typed date of birth was refused, with the exact copy the app shows. */
enum class DobError(val message: String) {
    /** Not DD / MM / YYYY digits (1–2, 1–2, exactly 4). */
    FORMAT("Enter your date of birth as DD / MM / YYYY."),

    /** 31/04, 29/02 in a non-leap year, month 13, day 0… */
    NO_SUCH_DATE("That date doesn’t exist — check the day and month."),
    FUTURE("Your date of birth can’t be in the future."),
    TOO_YOUNG("You need to be at least 13 to use TimesHealth+."),
    TOO_OLD("Enter a date of birth within the last 100 years."),
}

/** The outcome of [parseDob]. */
sealed interface DobResult {
    /** [iso] is what PATCH /profile takes: "1990-02-01T00:00:00.000Z" (UTC midnight). */
    data class Valid(val date: LocalDate, val iso: String) : DobResult
    data class Invalid(val error: DobError) : DobResult
}

private val ONE_OR_TWO_DIGITS = Regex("\\d{1,2}")
private val FOUR_DIGITS = Regex("\\d{4}")

/** Youngest and oldest age the app serves — the server refuses outside 13–100. */
const val DOB_MIN_AGE: Int = 13
const val DOB_MAX_AGE: Int = 100

/**
 * DD / MM / YYYY → a date of birth, or why not. Checked in this order, so the
 * user sees the most basic problem first: the shape, a real calendar date
 * (leap years included), not in the future, then age [DOB_MIN_AGE]–
 * [DOB_MAX_AGE] in whole years (100 is allowed, 101 is not).
 *
 * The age is by birthday, not by days: someone born 29 Feb turns 13 on
 * 1 March in a non-leap year.
 *
 * @param today the user's local calendar date (the RN app used the phone's
 *   date: `LocalDate.now(clock)`), injected so the rule is deterministic.
 */
fun parseDob(dd: String, mm: String, yyyy: String, today: LocalDate): DobResult {
    if (!ONE_OR_TWO_DIGITS.matches(dd) || !ONE_OR_TWO_DIGITS.matches(mm) || !FOUR_DIGITS.matches(yyyy)) {
        return DobResult.Invalid(DobError.FORMAT)
    }
    val d = dd.toInt()
    val m = mm.toInt()
    val y = yyyy.toInt()
    if (m < 1 || m > 12 || d < 1 || d > daysInMonthLikeJs(y, m)) {
        return DobResult.Invalid(DobError.NO_SUCH_DATE)
    }
    val year = today.year
    val month = today.monthValue
    val date = today.dayOfMonth
    if (y > year || (y == year && (m > month || (m == month && d > date)))) {
        return DobResult.Invalid(DobError.FUTURE)
    }
    var age = year - y
    if (month < m || (month == m && date < d)) age -= 1
    if (age < DOB_MIN_AGE) return DobResult.Invalid(DobError.TOO_YOUNG)
    if (age > DOB_MAX_AGE) return DobResult.Invalid(DobError.TOO_OLD)
    val iso = "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}T00:00:00.000Z"
    return DobResult.Valid(LocalDate.of(y, m, d), iso)
}

/**
 * Days in month as the TS computed it, `Date.UTC(y, m, 0)` — including its
 * quirk that years 0–99 mean 1900–1999. Only reachable for "00xx" years,
 * which then fail the age check anyway; kept so the error shown is the same.
 */
private fun daysInMonthLikeJs(year: Int, month: Int): Int =
    YearMonth.of(if (year in 0..99) 1900 + year else year, month).lengthOfMonth()

/** A stored DOB split for the DD / MM / YYYY fields. */
data class DobParts(val dd: String, val mm: String, val yyyy: String)

/**
 * The stored DOB is a UTC midnight: read it in UTC so no time zone shifts the
 * day (in IST, "1990-02-01T00:00Z" is still 1 Feb, but west of UTC it would be
 * 31 Jan). Unparseable → empty fields.
 */
fun splitDob(iso: String): DobParts {
    val date = parseIsoInstant(iso)?.atOffset(ZoneOffset.UTC)?.toLocalDate() ?: return DobParts("", "", "")
    return DobParts(
        dd = date.dayOfMonth.toString().padStart(2, '0'),
        mm = date.monthValue.toString().padStart(2, '0'),
        yyyy = date.year.toString(),
    )
}

/** The profile drawer's "Sep" — not ICU's "Sept" (it has its own month list). */
private val PROFILE_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "1 Feb 1990" for the profile row, read in UTC (see [splitDob]). Unparseable → "—". */
fun formatDob(iso: String): String {
    val date = parseIsoInstant(iso)?.atOffset(ZoneOffset.UTC)?.toLocalDate() ?: return "—"
    return "${date.dayOfMonth} ${PROFILE_MONTHS[date.monthValue - 1]} ${date.year}"
}
