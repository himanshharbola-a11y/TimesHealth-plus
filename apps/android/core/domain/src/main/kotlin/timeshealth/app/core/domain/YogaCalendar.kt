package timeshealth.app.core.domain

import java.time.Instant

/*
 * The Yoga tracker's month calendar — PRD §7.1 "Month calendar,
 * present/absent". Port of MonthCalendar in apps/mobile/app/(tabs)/yoga.tsx.
 *
 * The attendance ledger is keyed by IST date, so the month — and which cell
 * is today — are worked out in IST from the SERVER clock, whatever time zone
 * the phone is set to. Otherwise a member abroad (or a phone on UTC before
 * 5:30 AM IST) would see yesterday as "today" and a real class as missed.
 */

/** How one day of the month is drawn. Precedence is top to bottom. */
enum class DayState {
    /** Today (IST) — always shown as today, attended or not. */
    TODAY,

    /** After today: nothing to show yet. */
    FUTURE,

    /** A live class was joined that IST day. */
    ATTENDED,

    /** A day inside the membership (on or after `trackingSince`) before today with no mark. */
    MISSED,

    /**
     * Before the membership began (or no tracking start known): not
     * attended, but not "missed" either — those days weren't part of it.
     */
    NEUTRAL,
}

/** One day cell. [isoDate] is "YYYY-MM-DD", the ledger's key. */
data class CalendarDay(val day: Int, val isoDate: String, val state: DayState)

/**
 * A Sunday-first month grid. [weeks] always has whole weeks of 7 cells; null
 * cells are the blanks before the 1st and after the last day.
 *
 * @property month 1–12.
 * @property title "October 2026".
 */
data class YogaMonth(
    val year: Int,
    val month: Int,
    val title: String,
    val today: Int,
    val leadingBlanks: Int,
    val daysInMonth: Int,
    val weeks: List<List<CalendarDay?>>,
)

/** The column headers, Sunday first. */
val WEEKDAY_INITIALS: List<String> = listOf("S", "M", "T", "W", "T", "F", "S")

/**
 * The current IST month for the tracker (PRD §7.1), Sunday-first. The month
 * and "today" come from the server clock in IST — see the file note for why.
 *
 * @param nowMs the SERVER clock ([ServerClock.nowMs]), not the device's.
 * @param attendedDates the API's `attendedDates` ("YYYY-MM-DD", IST).
 * @param trackingSince the API's `trackingSince` ("YYYY-MM-DD", IST) — the day
 *   the subscription began — or null.
 */
fun yogaMonth(nowMs: Long, attendedDates: Collection<String>, trackingSince: String?): YogaMonth {
    val todayDate = istDate(Instant.ofEpochMilli(nowMs))
    val year = todayDate.year
    val month = todayDate.monthValue
    val today = todayDate.dayOfMonth
    val lead = sundayFirstIndex(todayDate.withDayOfMonth(1).dayOfWeek)
    val days = todayDate.lengthOfMonth()
    val attended = attendedDates.toHashSet()

    val cells = ArrayList<CalendarDay?>()
    repeat(lead) { cells.add(null) }
    for (d in 1..days) {
        val iso = isoDay(year, month, d)
        cells.add(CalendarDay(d, iso, dayState(d, today, iso, attended, trackingSince)))
    }
    while (cells.size % 7 != 0) cells.add(null)

    return YogaMonth(
        year = year,
        month = month,
        title = "${MONTH_LONG[month - 1]} $year",
        today = today,
        leadingBlanks = lead,
        daysInMonth = days,
        weeks = cells.chunked(7),
    )
}

/**
 * The state of day [day] of the month containing [today].
 *
 * Missed counts only from `trackingSince` (inclusive) up to yesterday: days
 * before the membership aren't part of it, and today isn't over. ISO dates
 * compare correctly as strings, which is how the TS compared them.
 */
fun dayState(day: Int, today: Int, isoDate: String, attended: Set<String>, trackingSince: String?): DayState = when {
    day == today -> DayState.TODAY
    day > today -> DayState.FUTURE
    isoDate in attended -> DayState.ATTENDED
    !trackingSince.isNullOrEmpty() && isoDate >= trackingSince -> DayState.MISSED
    else -> DayState.NEUTRAL
}

/** TalkBack label for a cell: "October 5, attended" / ", missed" / ", today"; plain for others. */
fun calendarDayLabel(month: Int, day: Int, state: DayState): String {
    val suffix = when (state) {
        DayState.ATTENDED -> ", attended"
        DayState.MISSED -> ", missed"
        DayState.TODAY -> ", today"
        else -> ""
    }
    return "${MONTH_LONG[month - 1]} $day$suffix"
}

private fun isoDay(year: Int, month: Int, day: Int): String =
    "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
