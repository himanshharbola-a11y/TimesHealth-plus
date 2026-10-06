package timeshealth.app.core.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/*
 * All scheduling in this product is IST. Yoga batches are defined as local
 * wall-clock times ("05:15", "18:30") and attendance is bucketed by IST
 * calendar day, so every date boundary here is +05:30 — never the phone's
 * zone (a member abroad, or a phone left on UTC) and never the server's
 * (UTC in production).
 *
 * Port of the client-relevant helpers in apps/api/src/time.ts, so the app
 * draws its date lines exactly where the server does.
 */

/**
 * India Standard Time as a fixed +05:30 offset — what the server uses. Not
 * `Asia/Kolkata`: that zone has different historical offsets (pre-1945), and a
 * fixed offset can't change with a tzdb update.
 */
val IST: ZoneOffset = ZoneOffset.ofHoursMinutes(5, 30)

internal const val MS_PER_MINUTE = 60_000L

/** The IST calendar day an instant falls on. */
fun istDate(instant: Instant): LocalDate = instant.atOffset(IST).toLocalDate()

/**
 * The IST calendar day for an instant, as the instant of midnight IST
 * (18:30 UTC the previous day). This is the value the server writes to
 * Attendance.date, and it is what makes UNIQUE(userId, date) mean "one mark
 * per IST day" (PRD §7.1).
 */
fun istDateOnly(instant: Instant): Instant = istDate(instant).atStartOfDay().toInstant(IST)

/**
 * The first instant of the IST calendar month containing [instant] — so
 * "this month" (the tracker calendar, monthly attendance) starts at midnight
 * IST on the 1st, not at 05:30 IST when the UTC month turns.
 */
fun istMonthStart(instant: Instant): Instant =
    istDate(instant).withDayOfMonth(1).atStartOfDay().toInstant(IST)

/**
 * The IST calendar day as a value for a date-only column: UTC midnight OF
 * THAT DAY. A DATE keeps only the UTC date part, so using [istDateOnly]
 * (IST midnight = 18:30 UTC the previous day) would store the previous day —
 * every attendance mark would land a day early.
 */
fun istCalendarDate(instant: Instant): Instant = istDate(instant).atStartOfDay().toInstant(ZoneOffset.UTC)

/**
 * "YYYY-MM-DD" in IST — the key format of the attendance ledger
 * (`attendedDates`, `trackingSince`), which compare correctly as strings.
 */
fun istDateString(instant: Instant): String = istDate(instant).toString()

/**
 * Resolves a batch's "HH:mm" IST wall-clock time to a real instant on the IST
 * day of [from], plus [dayOffset] days. Out-of-range parts roll over the way
 * `Date.UTC` does ("24:00" is the next midnight).
 *
 * The pitfall it exists for: a 05:15 IST batch is 23:45 UTC the PREVIOUS day,
 * so building it from the UTC date puts every early class a day out.
 *
 * Returns null for a malformed time (the TS produced an Invalid Date).
 */
fun batchInstant(time: String, dayOffset: Int = 0, from: Instant): Instant? {
    val clock = parseClockTime(time) ?: return null
    return istDate(from).atStartOfDay()
        .plusDays(dayOffset.toLong())
        .plusHours(clock.hour.toLong())
        .plusMinutes(clock.minute.toLong())
        .toInstant(IST)
}

/**
 * Minutes past midnight for an "HH:mm" (or "H:mm") batch time; null if
 * malformed (the TS returned NaN).
 */
fun minutesOfDay(time: String): Int? = parseClockTime(time)?.let { it.hour * 60 + it.minute }

/**
 * Orders batch times by the clock: 05:15 before 16:45. Never sort on `period`
 * — alphabetically EVENING comes before MORNING. A malformed time sorts last.
 *
 * Use as `batches.sortedWith(compareBy(nullsLast()) { minutesOfDay(it.time) })`
 * or `times.sortedWith(byClockTime)`.
 */
val byClockTime: Comparator<String> = compareBy(nullsLast()) { minutesOfDay(it) }

/** Hour of day (0–23) in IST. */
fun istHour(instant: Instant): Int = instant.atOffset(IST).hour

/**
 * Whole IST calendar days from [from] until [to] — 1 means "tomorrow" in IST,
 * regardless of the phone's or server's zone. 23:59 → 00:01 IST is 1 day.
 */
fun istDaysUntil(to: Instant, from: Instant): Long = ChronoUnit.DAYS.between(istDate(from), istDate(to))

/**
 * "06:30" → "6:30 AM" (uppercase, as the server formats batch times). A
 * malformed time is returned unchanged (the TS printed "NaN:NaN AM").
 */
fun formatBatchTime(time: String): String {
    val clock = parseClockTime(time) ?: return time
    val suffix = if (clock.hour >= 12) "PM" else "AM"
    val h12 = if (clock.hour % 12 == 0) 12 else clock.hour % 12
    return "$h12:${clock.minute.toString().padStart(2, '0')} $suffix"
}

/**
 * "Good morning · Thursday" — the prototype's Home greeting, in IST: before
 * 12:00 morning, before 17:00 afternoon, else evening.
 */
fun greetingFor(instant: Instant): String {
    val ist = instant.atOffset(IST)
    val part = when {
        ist.hour < 12 -> "Good morning"
        ist.hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }
    return "$part · ${WEEKDAY_LONG[sundayFirstIndex(ist.dayOfWeek)]}"
}

/**
 * "27–28 Oct · 10 AM to 6 PM" in IST. Derived from the stored instants so the
 * text can never disagree with them (a typed-in "Oct 18–19" did, once the race
 * dates moved). Same day: "27 Oct · …"; across months: "30 Sept – 2 Oct · …".
 */
fun formatIstWindow(start: Instant, end: Instant): String {
    val s = start.atOffset(IST)
    val e = end.atOffset(IST)
    fun month(m: Int) = MONTH_SHORT_EN_IN[m - 1]
    fun clock(h: Int, m: Int): String {
        val h12 = if (h % 12 == 0) 12 else h % 12
        val minutes = if (m != 0) ":${m.toString().padStart(2, '0')}" else ""
        return "$h12$minutes ${if (h < 12) "AM" else "PM"}"
    }
    val sameMonth = s.monthValue == e.monthValue && s.year == e.year
    val days = when {
        !sameMonth -> "${s.dayOfMonth} ${month(s.monthValue)} – ${e.dayOfMonth} ${month(e.monthValue)}"
        s.dayOfMonth == e.dayOfMonth -> "${s.dayOfMonth} ${month(s.monthValue)}"
        else -> "${s.dayOfMonth}–${e.dayOfMonth} ${month(s.monthValue)}"
    }
    return "$days · ${clock(s.hour, s.minute)} to ${clock(e.hour, e.minute)}"
}

// ── shared parsing and names ────────────────────────────────────────────────

internal data class ClockTime(val hour: Int, val minute: Int)

/**
 * "HH:mm" → hour and minute, read the way the TS did (`split(':').map(Number)`,
 * a missing part is 0, so "7" is 07:00 and "" is 00:00). Null when a part is
 * not a whole number.
 */
internal fun parseClockTime(time: String): ClockTime? {
    val parts = time.split(':')
    val hour = jsWholeNumber(parts[0]) ?: return null
    val minute = if (parts.size > 1) jsWholeNumber(parts[1]) ?: return null else 0
    return ClockTime(hour, minute)
}

/**
 * Month abbreviations exactly as `toLocaleString('en-IN', { month: 'short' })`
 * prints them on the RN app (ICU/CLDR en-IN) — note "Sept", not "Sep".
 */
internal val MONTH_SHORT_EN_IN = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sept", "Oct", "Nov", "Dec",
)

/** Full month names, as the Yoga tracker calendar titles them. */
internal val MONTH_LONG = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

/** Sunday-first, like JavaScript's `getUTCDay()`. */
internal val WEEKDAY_SHORT = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
internal val WEEKDAY_LONG = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

/** java.time's Monday=1…Sunday=7 → JavaScript's Sunday=0…Saturday=6. */
internal fun sundayFirstIndex(day: DayOfWeek): Int = day.value % 7
