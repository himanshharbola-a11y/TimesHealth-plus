package timeshealth.app.core.domain

/*
 * Per-kilometre splits and the pace chart for the run summary (Strava's
 * "Splits" and pace graph), from the recorded route with its timestamps.
 *
 * Only time spent MOVING counts. A gap between two fixes longer than
 * [MAX_GAP_MS] is a pause (GPS stops while paused) or lost signal: neither its
 * time nor its distance is counted, the same way the tracker re-anchors after
 * a pause instead of drawing a straight line across it. So splits add up to
 * the moving time, and a pause never makes a kilometre look slow.
 *
 * A segment faster than [MAX_SPEED_MPS] is a GPS jump, which the tracker
 * never counted as distance: it is left out here too, so the splits always
 * agree with the run's distance.
 */

/** A route point with when it was recorded (epoch ms). */
data class TimedPoint(override val lat: Double, override val lng: Double, val t: Long) : Coordinates

/**
 * One split. [index] is 1-based. The last one is [partial] when the run didn't
 * end on a whole kilometre: its pace is still per km.
 */
data class Split(val index: Int, val distanceM: Double, val seconds: Double, val partial: Boolean) {
    /** Seconds per km (0 for an empty split). */
    val paceSecPerKm: Double get() = if (distanceM > 0) seconds / (distanceM / 1000.0) else 0.0
}

/** Longer than this between two fixes is a pause or lost signal, not running. */
const val MAX_GAP_MS: Long = 20_000L

/** A partial last split shorter than this is noise (the last few steps): left out. */
private const val MIN_PARTIAL_M = 50.0

/** Splits of [splitM] metres (1000 for km, 1609.344 for miles). */
fun computeSplits(points: List<TimedPoint>, splitM: Double = 1000.0): List<Split> {
    val splits = mutableListOf<Split>()
    var inSplitM = 0.0
    var inSplitS = 0.0
    forEachMovingSegment(points) { d, dtS ->
        var remainingM = d
        var remainingS = dtS
        // A segment may cross one (or, with sparse fixes, several) boundaries.
        while (inSplitM + remainingM >= splitM) {
            val takeM = splitM - inSplitM
            val takeS = if (remainingM > 0) remainingS * (takeM / remainingM) else 0.0
            splits += Split(splits.size + 1, splitM, inSplitS + takeS, partial = false)
            remainingM -= takeM
            remainingS -= takeS
            inSplitM = 0.0
            inSplitS = 0.0
        }
        inSplitM += remainingM
        inSplitS += remainingS
    }
    if (inSplitM >= MIN_PARTIAL_M) splits += Split(splits.size + 1, inSplitM, inSplitS, partial = true)
    return splits
}

/** A point of the pace chart: [distanceKm] along the run, pace over the last bucket. */
data class PacePoint(val distanceKm: Double, val paceSecPerKm: Double)

/**
 * The pace chart: average pace over each [bucketM] of moving distance (100 m
 * evens out GPS jitter without hiding a hill).
 */
fun paceSeries(points: List<TimedPoint>, bucketM: Double = 100.0): List<PacePoint> {
    val out = mutableListOf<PacePoint>()
    var totalM = 0.0
    var bucketDist = 0.0
    var bucketS = 0.0
    forEachMovingSegment(points) { d, dtS ->
        totalM += d
        bucketDist += d
        bucketS += dtS
        if (bucketDist >= bucketM) {
            out += PacePoint(totalM / 1000.0, bucketS / (bucketDist / 1000.0))
            bucketDist = 0.0
            bucketS = 0.0
        }
    }
    return out
}

/** Calls [segment] with each moving segment's metres and seconds, skipping pauses and gaps. */
private inline fun forEachMovingSegment(points: List<TimedPoint>, segment: (meters: Double, seconds: Double) -> Unit) {
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val dt = b.t - a.t
        if (dt <= 0 || dt > MAX_GAP_MS) continue
        val d = haversineM(a, b)
        if (d / (dt / 1000.0) > MAX_SPEED_MPS) continue
        segment(d, dt / 1000.0)
    }
}
