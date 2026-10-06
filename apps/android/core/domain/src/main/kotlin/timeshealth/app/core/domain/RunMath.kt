package timeshealth.app.core.domain

import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * The run tracker's distance rules (PRD §8.6) — pure, so they are tested
 * without a phone (RunMathTest). :core:runtracker applies the plan to Room.
 *
 * Port of apps/mobile/src/lib/runMath.ts. Keep the two in step: a change to a
 * threshold here must be a change there (and in the Swift mirror).
 */

/** Points less accurate than this are discarded. ~25 m is a weak urban fix. */
const val ACCURACY_GATE_M: Double = 25.0

/** Below this, movement is almost certainly GPS jitter while standing still. */
const val MIN_SEGMENT_M: Double = 4.0

/**
 * Faster than any runner (~43 km/h). Judged against the time between fixes, so
 * 150 m covered during a minute in an underpass still counts, while a fix
 * that teleports 500 m in three seconds does not.
 */
const val MAX_SPEED_MPS: Double = 12.0

/**
 * A fix this much older than the start/resume moment is a replay from cache.
 * The slack lets a genuinely fresh fix, stamped a beat before the request,
 * still count.
 */
const val STALE_FIX_GRACE_MS: Long = 2_000L

/** Anything with a position — what [haversineM] measures between. */
interface Coordinates {
    val lat: Double
    val lng: Double
}

/**
 * One location fix from the GPS (the TS `GeoPoint`, renamed so it can't be
 * confused with a :core:model API type).
 *
 * @property accuracy metres. Fixes above [ACCURACY_GATE_M] are dropped, never averaged in.
 * @property timestamp epoch milliseconds, as stamped by the location provider.
 */
data class GeoFix(
    override val lat: Double,
    override val lng: Double,
    val accuracy: Double,
    val altitude: Double? = null,
    val speed: Double? = null,
    val timestamp: Long,
) : Coordinates

/** The last stored point a new fix is measured from. [t] is epoch ms. */
data class Anchor(
    override val lat: Double,
    override val lng: Double,
    val t: Long,
) : Coordinates

/** The fix as an anchor: where the run is measured from once it is kept. */
fun GeoFix.toAnchor(): Anchor = Anchor(lat, lng, timestamp)

/** Great-circle distance in metres (mean Earth radius 6,371 km). */
fun haversineM(a: Coordinates, b: Coordinates): Double {
    val r = 6_371_000.0
    val dLat = (b.lat - a.lat) * Math.PI / 180
    val dLng = (b.lng - a.lng) * Math.PI / 180
    val sinLat = sin(dLat / 2)
    val sinLng = sin(dLng / 2)
    val s = sinLat * sinLat +
        cos(a.lat * Math.PI / 180) * cos(b.lat * Math.PI / 180) * sinLng * sinLng
    return 2 * r * asin(sqrt(s))
}

/**
 * What one batch of fixes does to a run.
 *
 * @property store fixes to append to the route, in order.
 * @property addedM metres to add to the run's distance.
 * @property dropped fixes refused (poor accuracy, impossible speed).
 * @property total fixes looked at.
 * @property reanchorAfter still waiting to re-anchor: the start/resume time
 *   (epoch ms) fixes must be newer than, or null once a fresh fix has anchored
 *   the segment. Persist it and pass it to the next batch.
 * @property candidate an impossible-speed fix held for the next batch to
 *   confirm or discard. Persist it and pass it to the next batch.
 */
data class BatchPlan(
    val store: List<GeoFix>,
    val addedM: Double,
    val dropped: Int,
    val total: Int,
    val reanchorAfter: Long?,
    val candidate: GeoFix?,
)

/**
 * Decides what a batch of fixes does to a run (PRD §8.6).
 *
 * - A fix worse than [ACCURACY_GATE_M] is dropped, never averaged in (§8.6).
 * - Movement under [MIN_SEGMENT_M] is jitter: skipped, the anchor stays put.
 * - `reanchorAfter` (the start or resume time) or no anchor yet: the next
 *   fresh fix starts a new segment and adds no distance — ground covered while
 *   paused was not run. Fixes timestamped before that moment (less
 *   [STALE_FIX_GRACE_MS]) are ignored: restarting location updates often
 *   replays a cached pre-pause fix first, and anchoring on it let the paused
 *   stretch count as a slow "run".
 * - A fix implying more than [MAX_SPEED_MPS] is never counted, but it is held
 *   as a candidate. If the next fix agrees with it, the runner really is there
 *   (signal regained after a gap) and the run re-anchors on it; otherwise it
 *   was a one-off error. Without this, one jump pinned the run to the old
 *   point and every later fix was "too far" — the rest of the run was lost.
 *
 * Speed is distance over the seconds between fixes, floored at one second, so
 * two fixes with the same timestamp are not an infinite speed.
 */
fun planBatch(
    anchor: Anchor?,
    points: List<GeoFix>,
    reanchorAfterIn: Long?,
    candidateIn: GeoFix?,
): BatchPlan {
    val store = ArrayList<GeoFix>()
    var prev: Anchor? = anchor
    var reanchorAfter = reanchorAfterIn
    var candidate = candidateIn
    var addedM = 0.0
    var dropped = 0
    var total = 0

    fun keep(p: GeoFix) {
        store.add(p)
        prev = p.toAnchor()
    }
    fun speed(from: Anchor, p: GeoFix, d: Double): Double =
        d / max(1.0, (p.timestamp - from.t) / 1000.0)

    for (p in points) {
        total += 1

        if (p.accuracy > ACCURACY_GATE_M) {
            dropped += 1
            continue
        }

        val from = prev
        val waitingSince = reanchorAfter
        if (waitingSince != null || from == null) {
            // A replayed fix from before the start/resume says nothing about now.
            if (waitingSince != null && p.timestamp < waitingSince - STALE_FIX_GRACE_MS) continue
            keep(p)
            reanchorAfter = null
            candidate = null
            continue
        }

        val d = haversineM(from, p)
        if (d < MIN_SEGMENT_M) continue

        if (speed(from, p, d) <= MAX_SPEED_MPS) {
            addedM += d
            candidate = null
            keep(p)
            continue
        }

        dropped += 1
        val held = candidate
        if (held != null) {
            val dc = haversineM(held, p)
            if (speed(held.toAnchor(), p, dc) <= MAX_SPEED_MPS) {
                // Two fixes agree on the new position: re-anchor there and carry on.
                keep(held)
                if (dc >= MIN_SEGMENT_M) {
                    addedM += dc
                    keep(p)
                }
                candidate = null
                continue
            }
        }
        candidate = p
    }

    return BatchPlan(store, addedM, dropped, total, reanchorAfter, candidate)
}

/**
 * The shape of a run id the server accepts (any version, either case). Match
 * with [Regex.matches] — a whole-string match.
 */
val UUID_RE: Regex = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    RegexOption.IGNORE_CASE,
)

/**
 * RFC 4122 v4 — the server only accepts a UUID run id (it makes uploads
 * idempotent). On the RN app Hermes had no crypto.randomUUID, and the old
 * fallback ("<ms>-<hex>") was rejected with a 400, so no run from a phone ever
 * synced. On the JVM [UUID.randomUUID] is always there (SecureRandom-backed,
 * lowercase), the equivalent of the TS native path.
 */
fun uuidV4(): String = UUID.randomUUID().toString()

/**
 * The TS fallback generator, for a caller (or test) that supplies its own
 * randomness. A run id needs uniqueness, not secrecy: 122 random bits is
 * plenty. Version nibble is 4, variant bits are 10xx.
 */
fun uuidV4(random: Random): String {
    val h = IntArray(32) { random.nextInt(16) }
    h[12] = 4 // version
    h[16] = (h[16] and 0x3) or 0x8 // variant 10xx
    val s = h.joinToString("") { it.toString(16) }
    return "${s.substring(0, 8)}-${s.substring(8, 12)}-${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20)}"
}
