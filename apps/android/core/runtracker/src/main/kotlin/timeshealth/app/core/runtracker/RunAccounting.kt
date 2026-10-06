package timeshealth.app.core.runtracker

import timeshealth.app.core.domain.Coordinates

/*
 * The time and summary maths of a run (PRD §8.6), pure so it is tested on the
 * JVM (RunAccountingTest). Distance rules are in :core:domain (`planBatch`).
 * Port of apps/mobile/src/lib/runTracker.ts; rounding is Java Math.round,
 * which matches JavaScript's (core/domain/README.md, dialect note 4).
 */

/**
 * Over this share of refused fixes, the distance is flagged as unreliable
 * rather than trusted (§8.6: flag rather than fabricate).
 */
internal const val ACCURACY_WARNING_RATIO = 0.25

/** Rough MET-based estimate, kcal per km. An estimate, and labelled as one. */
internal const val KCAL_PER_KM = 62

/** Moving seconds at [nowMs]: elapsed minus banked pauses minus any pause in progress. */
internal fun movingSeconds(startedAt: Long, pausedMs: Long, pausedAt: Long?, nowMs: Long): Long {
    val pausedNow = if (pausedAt != null) nowMs - pausedAt else 0L
    return maxOf(0L, Math.round((nowMs - startedAt - pausedMs - pausedNow) / 1000.0))
}

/**
 * All paused time at [nowMs], including a pause still in progress. Finishing
 * straight from a pause: that last pause isn't running time either.
 */
internal fun totalPausedMs(pausedMs: Long, pausedAt: Long?, nowMs: Long): Long =
    pausedMs + if (pausedAt != null) nowMs - pausedAt else 0L

/**
 * When a run whose GPS died with the app process is treated as having paused:
 * its last recorded fix. Never before the start, and never before the last
 * resume ([reanchor]): the gap before a resume was already banked as a pause,
 * and banking it twice would eat real running time.
 */
internal fun recoveryPausedAt(startedAt: Long, lastPointT: Long?, reanchor: Long): Long =
    maxOf(startedAt, lastPointT ?: startedAt, reanchor)

/**
 * The finished run's summary, as both the live finish and a late sync see it.
 *
 * - Moving time: pace over a run with a ten-minute pause at a signal must not
 *   include the ten minutes. At least one second, so pace is never a divide by 0.
 * - Distance is rounded to 2 decimals for upload and pace; [FinishedRun.distanceM]
 *   keeps the raw figure for the 10 m rule.
 * - Accuracy warning: if a meaningful share of fixes was discarded, the
 *   distance is not trustworthy and the UI must say so (§8.6).
 */
internal fun summarize(
    id: String,
    owner: String,
    startedAt: Long,
    endedAt: Long,
    pausedMs: Long,
    distanceM: Double,
    totalPoints: Int,
    droppedPoints: Int,
    route: List<Coordinates>,
): FinishedRun {
    val durationSeconds = maxOf(1L, Math.round((endedAt - startedAt - pausedMs) / 1000.0)).toInt()
    val distanceKm = Math.round(distanceM / 1000.0 * 100.0) / 100.0
    return FinishedRun(
        id = id,
        owner = owner,
        startedAt = startedAt,
        endedAt = endedAt,
        distanceM = distanceM,
        distanceKm = distanceKm,
        durationSeconds = durationSeconds,
        avgPaceSecPerKm = if (distanceKm > 0) Math.round(durationSeconds / distanceKm).toInt() else 0,
        caloriesBurned = Math.round(distanceKm * KCAL_PER_KM).toInt(),
        routePolyline = if (route.size > 1) encodePolyline(route) else null,
        hasAccuracyWarning = totalPoints > 0 &&
            droppedPoints.toDouble() / totalPoints > ACCURACY_WARNING_RATIO,
    )
}
