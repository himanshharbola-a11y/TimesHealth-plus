package timeshealth.app.core.domain

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * Display formats from apps/mobile/src/lib/time.ts. The strings are what the
 * RN app actually rendered on Android (ICU en-IN), not what its comments
 * sketched: "Thu, 15 Oct, 6:00 am" with a lowercase am/pm and "Sept".
 *
 * Built by hand rather than with DateTimeFormatter: JVM and Android locale
 * data differ by version ("Sep"/"Sept", "AM"/"am"), and a pure module must
 * give the same string everywhere.
 */

/**
 * The hero countdown (PRD §6.1 "Starts in 4 min"): "Starts in 1h 5m" from an
 * hour, "Starts in 4 min" from a minute, "Starts in 42s" below that, and
 * "Starting now" at or past the start.
 */
fun formatCountdown(seconds: Long): String {
    if (seconds <= 0) return "Starting now"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    if (h > 0) return "Starts in ${h}h ${m}m"
    if (m > 0) return "Starts in $m min"
    return "Starts in ${seconds}s"
}

/**
 * "Thu, 15 Oct, 6:00 am" — a session's date and time.
 *
 * In IST by default: the RN app used the phone's zone, which put a class at a
 * different hour from its "6:00 AM" batch label for anyone not on IST.
 */
fun formatSessionDate(instant: Instant, zone: ZoneId = IST): String {
    val t = instant.atZone(zone)
    return "${WEEKDAY_SHORT[sundayFirstIndex(t.dayOfWeek)]}, ${t.dayOfMonth} " +
        "${MONTH_SHORT_EN_IN[t.monthValue - 1]}, ${clock12Lower(t.hour, t.minute)}"
}

/** "6:00 am", "12:05 am", "7:30 pm" — en-IN's lowercase day period. */
fun formatTimeOfDay(instant: Instant, zone: ZoneId = IST): String {
    val t = instant.atZone(zone)
    return clock12Lower(t.hour, t.minute)
}

/**
 * "Today · 7:30 pm", "Tomorrow · 5:15 am", otherwise "Wed · 6:00 am".
 *
 * "Today" is decided by calendar day in [zone] against the SERVER clock
 * ([nowMs] = `ServerClock.nowMs()`), so a class just after midnight IST reads
 * "Tomorrow" at 23:59 even though it is minutes away.
 */
fun formatDayAndTime(instant: Instant, nowMs: Long, zone: ZoneId = IST): String {
    val day = instant.atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val label = when (ChronoUnit.DAYS.between(today, day)) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> WEEKDAY_SHORT[sundayFirstIndex(day.dayOfWeek)]
    }
    return "$label · ${formatTimeOfDay(instant, zone)}"
}

/**
 * A run's elapsed time (PRD §8.6): "MM:SS" under an hour, "H:MM:SS" from an
 * hour, so a short run doesn't carry a meaningless "0:" prefix. Floor
 * division and truncating remainder, as JavaScript computed it.
 */
fun formatDuration(seconds: Long): String {
    val h = Math.floorDiv(seconds, 3600L)
    val m = Math.floorDiv(seconds % 3600, 60L)
    val s = seconds % 60
    fun pad(n: Long) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${pad(m)}:${pad(s)}" else "${pad(m)}:${pad(s)}"
}

/**
 * Pace as 5'58" /km (PRD §8.6). Not finite or not positive (no distance yet)
 * shows --'--" /km.
 *
 * Round the total first: splitting into minutes and seconds and then
 * rounding the seconds turned 13:59.6 into 13'60".
 */
fun formatPace(secPerKm: Double): String {
    if (!secPerKm.isFinite() || secPerKm <= 0) return "--'--\" /km"
    return "${minutesSeconds(secPerKm)} /km"
}

/** 839.6 → 14'00" (whole-second rounding first, half-up like `Math.round`). */
internal fun minutesSeconds(seconds: Double): String {
    val total = Math.round(seconds)
    return "${Math.floorDiv(total, 60L)}'${(total % 60).toString().padStart(2, '0')}\""
}

/** 24h clock → "6:00 am" / "12:05 am" / "12:00 pm". */
internal fun clock12Lower(hour: Int, minute: Int): String {
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    return "$h12:${minute.toString().padStart(2, '0')} ${if (hour < 12) "am" else "pm"}"
}
