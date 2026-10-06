package timeshealth.app.core.runtracker

/**
 * Below this many metres (raw, unrounded) a finished run is an accidental
 * start-stop, not a run: it is deleted rather than saved, so history doesn't
 * fill with "0.00 km" entries (RunTrackerPanel `MIN_SAVED_M`).
 *
 * Compared against [FinishedRun.distanceM], never the rounded km: 6 m rounds
 * to 0.01 km, which would pass a "> 0 km" check.
 */
const val MIN_SAVED_M: Double = 10.0

/** Where a run is in its life. Stored by name in Room (`runs.state`). */
enum class RunState { RUNNING, PAUSED, FINISHED }

/**
 * The unfinished run the UI shows: RUNNING or PAUSED, never FINISHED.
 *
 * Emitted live by [RunTracker.activeRun] whenever the database changes, which
 * includes every batch of fixes the service writes with the screen off.
 *
 * @property owner the profile id the run belongs to. `""` only for a legacy
 *   run with no owner, which is shown to whoever is signed in (TS schema v3).
 * @property pausedMs time spent paused, NOT counting a pause in progress.
 * @property pausedAt when the current pause began (epoch ms); null while running.
 */
data class ActiveRun(
    val id: String,
    val owner: String,
    val startedAt: Long,
    val distanceM: Double,
    val pausedMs: Long,
    val pausedAt: Long?,
    val state: RunState,
    val droppedPoints: Int,
    val totalPoints: Int,
) {
    val isPaused: Boolean get() = state == RunState.PAUSED

    /** Unrounded kilometres, for the live display. */
    val distanceKm: Double get() = distanceM / 1000.0

    /**
     * Time actually spent running: what the timer shows and pace is based on.
     * The clock doesn't jump forward by the pause on resume, and a pause in
     * progress stops it. The UI ticks this once a second itself; nothing in
     * the database changes every second.
     */
    fun movingSeconds(nowMs: Long): Long = movingSeconds(startedAt, pausedMs, pausedAt, nowMs)

    /** Average pace so far, seconds per km; 0 until there is any distance. */
    fun paceSecPerKm(nowMs: Long): Double =
        if (distanceKm > 0) movingSeconds(nowMs) / distanceKm else 0.0

    /**
     * The live "Weak GPS signal" pill: over a quarter of fixes refused, once
     * there are enough fixes to judge. Never while paused: no GPS is read then,
     * so a warning would mean nothing (RunTrackerPanel `weakSignal`).
     */
    val hasWeakSignal: Boolean
        get() = state == RunState.RUNNING && totalPoints > 8 &&
            droppedPoints.toDouble() / totalPoints > ACCURACY_WARNING_RATIO
}

/**
 * A finished run with everything the app needs for the summary screen and the
 * upload (`UploadRunRequest`). Maths in [summarize].
 *
 * @property distanceM raw metres, unrounded. Use it, not [distanceKm], for the
 *   [MIN_SAVED_M] rule: the "too short to save" check must not see 6 m as 0.01 km.
 * @property distanceKm rounded to 2 decimals, as uploaded.
 * @property durationSeconds moving time: pauses excluded, at least 1.
 * @property caloriesBurned a rough estimate; present it as one (docs/05 §6).
 * @property routePolyline Google encoded polyline, or null with fewer than two
 *   points. Null too once the run has synced: the points are pruned then.
 * @property hasAccuracyWarning over a quarter of fixes were refused, so the
 *   distance may be short. Shown, never hidden (§8.6 "do not silently record a
 *   wrong distance").
 */
data class FinishedRun(
    val id: String,
    val owner: String,
    val startedAt: Long,
    val endedAt: Long,
    val distanceM: Double,
    val distanceKm: Double,
    val durationSeconds: Int,
    val avgPaceSecPerKm: Int,
    val caloriesBurned: Int,
    val routePolyline: String?,
    val hasAccuracyWarning: Boolean,
) {
    /**
     * Under [MIN_SAVED_M]: [RunTracker.finish] has already deleted it. Show
     * "Too short to save" and don't upload.
     */
    val isTooShort: Boolean get() = distanceM < MIN_SAVED_M
}

/**
 * [RunTracker.start] or [RunTracker.resume] was called without precise
 * location. Nothing was changed. Request [RunTracker.LOCATION_PERMISSIONS],
 * and route to Settings when the OS won't ask again (§8.6: "permission denied
 * → explain and route to settings").
 */
class LocationPermissionRequiredException :
    SecurityException("Precise location (ACCESS_FINE_LOCATION) is required to track a run")
