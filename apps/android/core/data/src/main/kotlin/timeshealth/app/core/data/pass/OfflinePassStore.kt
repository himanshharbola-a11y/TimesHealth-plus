package timeshealth.app.core.data.pass

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import timeshealth.app.core.data.quietly
import timeshealth.app.core.data.security.SecureStore
import timeshealth.app.core.model.ApiJson
import timeshealth.app.core.model.DigitalBib
import timeshealth.app.core.model.MarathonEvent
import timeshealth.app.core.model.RaceExpoInfo
import timeshealth.app.core.model.RaceTier
import timeshealth.app.core.network.ServerClock

/**
 * A race pass kept on the device for the stadium gate (PRD §8.3): only what the pass displays,
 * plus its 7-day offline signature. Never a session token.
 */
@Serializable
data class OfflinePass(
    val eventId: String,
    val bibNumber: String,
    val participantName: String,
    val category: String,
    val tier: RaceTier,
    val eventName: String,
    /** The signed offline token (`ref.userId.expires.sig`) the gate scanner verifies. */
    val offlinePayload: String,
    /** ISO-8601, when this copy was saved. */
    val savedAt: String,
    /**
     * Race-morning facts shown beside the pass, flag-off and the expo card, so they too survive
     * a no-signal start. Null for passes saved without them.
     */
    val flagOffTime: String? = null,
    val expo: RaceExpoInfo? = null,
) {
    /** When the gate stops accepting [offlinePayload], epoch ms; 0 when it can't be read. */
    val expiresAtMs: Long get() = offlineSignatureExpiryMs(offlinePayload)
}

/**
 * Race passes kept on the device for the stadium gate. Port of apps/mobile/src/lib/offlinePass.ts.
 *
 * A 20,000-runner venue is exactly where mobile data fails, so the pass has to open from a cold
 * start with no signal. Stored encrypted ([SecureStore]) because it carries a signed token, and
 * wiped on sign-out so the next person on the phone never sees it.
 *
 * Layout: one entry per pass (`th_pass_<eventId>`) plus an index of event ids in save order, as
 * in RN. Writes and wipes run one at a time ([serial]) so a wipe never interleaves with a save
 * and leaves a pass outside the index.
 *
 * Time comes from the network module's [ServerClock]: at a cold start with no signal it is the
 * device clock (nothing has synced yet), and once a response has synced it, a phone with a wrong
 * clock still expires passes when the gate does.
 */
@Singleton
class OfflinePassStore @Inject constructor(
    private val store: SecureStore,
    private val clock: ServerClock,
) {

    /**
     * Bumped by every wipe. A save carries the generation from when its fetch STARTED
     * ([generation]), so a race-page response that lands after sign-out can't write the previous
     * user's pass back.
     */
    private val generation = AtomicInteger(0)

    private val serial = Mutex()

    /** Read this BEFORE fetching the race page, and pass it to [save] / [remove]. */
    fun generation(): Int = generation.get()

    /**
     * Keeps [bib] for [eventId], with the event's flag-off time and expo card. Dropped silently
     * when [gen] is from before the last [clear].
     */
    suspend fun save(eventId: String, bib: DigitalBib, gen: Int, event: MarathonEvent? = null): Unit = serial.withLock {
        if (gen != generation.get()) return@withLock
        val pass = OfflinePass(
            eventId = eventId,
            bibNumber = bib.bibNumber,
            participantName = bib.participantName,
            category = bib.category,
            tier = bib.tier,
            eventName = bib.eventName,
            offlinePayload = bib.offlinePayload,
            savedAt = ISO_MILLIS.format(Instant.ofEpochMilli(clock.now())),
            flagOffTime = event?.flagOffTime,
            // Clipped so a pass stays small: RN kept each one inside SecureStore's per-value
            // budget, and the expo card is the only free text the server could make long.
            expo = event?.expo?.let {
                it.copy(
                    venue = clip(it.venue, 160),
                    instructions = clip(it.instructions, 400),
                    requiredDocuments = it.requiredDocuments.take(6),
                )
            },
        )
        // The index first (RN wrote it second): if the pass write then fails, the index lists a
        // pass that isn't there, which is harmless. The other way round, a failed index write
        // would leave a pass on disk that clear() can't find at sign-out.
        val ids = readIndex()
        if (eventId !in ids) writeIndex(ids + eventId)
        store.put(keyFor(eventId), ApiJson.encodeToString(OfflinePass.serializer(), pass))
    }

    /** The server says there is no bib for this event (any more): forget ours. */
    suspend fun remove(eventId: String, gen: Int): Unit = serial.withLock {
        if (gen != generation.get()) return@withLock
        val ids = readIndex()
        if (eventId !in ids) return@withLock
        store.remove(keyFor(eventId))
        writeIndex(ids - eventId)
    }

    /**
     * The pass for [eventId] if it is still inside its signature window. Only passes listed in the
     * index count: the index is what sign-out wipes, so nothing outside it may ever be shown.
     */
    suspend fun load(eventId: String): OfflinePass? =
        serial.withLock { if (eventId in readIndex()) read(eventId) else null }

    /** Every pass still inside its signature window, in the order they were saved. */
    suspend fun list(): List<OfflinePass> = serial.withLock { readIndex().mapNotNull { read(it) } }

    /** Forgets every pass. Sign-out and identity change. */
    suspend fun clear() {
        // Bumped before queueing, so a save already waiting for the lock is dropped too.
        generation.incrementAndGet()
        serial.withLock {
            // One write for the passes and the index together: nothing is left half-wiped.
            val keys = readIndex().map(::keyFor) + INDEX_KEY
            store.remove(*keys.toTypedArray())
        }
    }

    private suspend fun readIndex(): List<String> =
        quietly {
            store.get(INDEX_KEY)?.let { ApiJson.decodeFromString(INDEX_SERIALIZER, it) }
        } ?: emptyList()

    private suspend fun writeIndex(ids: List<String>) =
        store.put(INDEX_KEY, ApiJson.encodeToString(INDEX_SERIALIZER, ids))

    private suspend fun read(eventId: String): OfflinePass? {
        val pass = quietly {
            store.get(keyFor(eventId))?.let { ApiJson.decodeFromString(OfflinePass.serializer(), it) }
        } ?: return null
        // Two ids can share a sanitised key; never show one race's pass for another.
        if (pass.eventId != eventId) return null
        // Past its signature the gate would reject it: don't offer it.
        return pass.takeIf { it.expiresAtMs > clock.now() }
    }

    internal companion object {
        const val INDEX_KEY = "th_offline_passes"
        private val INDEX_SERIALIZER = ListSerializer(String.serializer())
        private val UNSAFE_KEY_CHARS = Regex("[^A-Za-z0-9._-]")

        /** JavaScript's `toISOString()`: always UTC with milliseconds. */
        private val ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun keyFor(eventId: String) = "th_pass_${eventId.replace(UNSAFE_KEY_CHARS, "_")}"
    }
}

/**
 * Shortens [s] to at most [max] UTF-16 units, ending in "…". Never cuts a surrogate pair in half
 * (RN's `slice` could, leaving a broken character before the ellipsis).
 */
internal fun clip(s: String, max: Int): String {
    if (s.length <= max) return s
    var end = max - 1
    if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
    return s.substring(0, end) + "…"
}

/**
 * Expiry of an offline signature `ref.userId.expires.sig` in epoch ms: the third part is epoch
 * seconds. Anything unreadable is 0, i.e. already expired, as in RN.
 */
internal fun offlineSignatureExpiryMs(payload: String): Long {
    val part = payload.split('.').getOrNull(2) ?: return 0
    val seconds = part.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() } ?: return 0
    return if (seconds.isFinite()) (seconds * 1000).toLong() else 0
}
