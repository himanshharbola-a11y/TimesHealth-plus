package timeshealth.app.core.domain

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Countdowns tick locally from the server's clock, never the device's.
 *
 * PRD §6.1 promises "Starts in 4 min". Device clocks are wrong often enough
 * that trusting them would show the wrong number, and polling the server every
 * second would hammer it at exactly the moment 100k subscribers open the app
 * for the 6:00 AM batch. So the app measures the device's error once per
 * response and corrects every reading by it.
 *
 * One instance per process (a Hilt singleton): the TS kept the skew in module
 * state. [deviceClock] is injectable so tests are deterministic.
 */
class ServerClock(private val deviceClock: Clock = Clock.systemUTC()) {

    /** Server time minus device time, in ms. 0 until the first sync. */
    @Volatile
    var skewMs: Long = 0L
        private set

    /**
     * Call once per /home or /session (or /config) response with its
     * `serverTime`. Returns false, keeping the previous skew, if the value
     * can't be parsed — the TS would have set the skew to NaN and broken
     * every countdown until the next sync.
     */
    fun syncServerTime(serverTimeIso: String): Boolean {
        val server = parseIsoInstant(serverTimeIso) ?: return false
        syncServerTime(server.toEpochMilli())
        return true
    }

    /** As [syncServerTime], from epoch milliseconds. */
    fun syncServerTime(serverTimeMs: Long) {
        skewMs = serverTimeMs - deviceClock.millis()
    }

    /** The server-corrected time, epoch ms. Use this, not `System.currentTimeMillis()`. */
    fun nowMs(): Long = deviceClock.millis() + skewMs

    /** The server-corrected time. */
    fun now(): Instant = Instant.ofEpochMilli(nowMs())
}

/**
 * Whole seconds remaining until [targetMs], never negative — the value a
 * countdown shows (TS `useCountdown`). Rounded half-up like `Math.round`, so
 * 1.5 s left reads "2s" rather than flicking to "1s" early.
 */
fun secondsUntil(targetMs: Long, nowMs: Long): Long = maxOf(0L, Math.round((targetMs - nowMs) / 1000.0))

/**
 * Parses the API's ISO-8601 instants ("2026-10-06T00:30:00.000Z", or with an
 * offset such as "+05:30"). A bare date ("2026-10-06") is UTC midnight, as in
 * JavaScript. Anything else is null — where the TS got an Invalid Date (NaN),
 * which silently failed every comparison.
 */
fun parseIsoInstant(iso: String): Instant? {
    val s = iso.trimJs()
    return try {
        OffsetDateTime.parse(s).toInstant()
    } catch (_: DateTimeParseException) {
        try {
            LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
