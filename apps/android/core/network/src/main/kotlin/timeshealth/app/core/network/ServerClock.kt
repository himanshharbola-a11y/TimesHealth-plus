package timeshealth.app.core.network

import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the difference between the server's clock and the device's, so countdowns ("Starts in
 * 4 min", §6.1) tick from server time. Device clocks are wrong often enough to show the wrong
 * number, and polling the server every second would hammer it exactly when everyone opens the
 * app for the 6:00 AM batch. So every response that carries `serverTime` (GET /config,
 * /session, /home) re-measures the skew, and readings are corrected by it.
 *
 * Fed automatically by the call adapter; see [NetworkFactory]. One instance per process
 * ([Singleton]): a second instance would never be synced and would silently show device time.
 *
 * @param deviceMillis the device clock, replaceable so tests are deterministic.
 */
@Singleton
class ServerClock(private val deviceMillis: () -> Long) {

    /** The production clock: device time from [System.currentTimeMillis]. */
    @Inject
    constructor() : this(System::currentTimeMillis)

    /** Server time minus device time, in ms. 0 until the first sync. */
    @Volatile
    var skewMs: Long = 0L
        private set

    /** The server-corrected time, in epoch ms. Use this, not `System.currentTimeMillis()`. */
    fun now(): Long = deviceMillis() + skewMs

    /**
     * Re-measures the skew from a response's ISO-8601 `serverTime`. Returns false, keeping the
     * previous skew, if the value can't be parsed: one malformed response must not break every
     * countdown until the next sync.
     */
    fun sync(serverTimeIso: String): Boolean {
        val serverMillis = parseEpochMillis(serverTimeIso) ?: return false
        skewMs = serverMillis - deviceMillis()
        return true
    }

    // The server sends JS toISOString() ("...Z"); an explicit offset is accepted too.
    private fun parseEpochMillis(iso: String): Long? =
        runCatching { Instant.parse(iso) }
            .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
            .getOrNull()
            ?.toEpochMilli()
}
