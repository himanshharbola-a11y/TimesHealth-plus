package timeshealth.app.core.runtracker.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import timeshealth.app.core.domain.Anchor
import timeshealth.app.core.domain.Coordinates
import timeshealth.app.core.domain.GeoFix
import timeshealth.app.core.runtracker.ActiveRun
import timeshealth.app.core.runtracker.RunState

/*
 * The schema of apps/mobile/src/lib/runTracker.ts (SQLite v1–v3) with the
 * same table and column names, so the two stores read alike. Differences:
 * `run_points.id` (Room needs a primary key), and no `runs.moving_ms`, which
 * the TS created but never read or wrote.
 *
 * Every fix is written as it arrives and nothing about a run lives only in
 * memory: §8.6 "app killed mid-run → run must be recoverable, not lost".
 */

/**
 * One run. At most one row is unfinished (RUNNING or PAUSED) at a time:
 * starting a run finishes anyone else's that was left parked.
 *
 * @property owner profile id; `''` = legacy row with no owner, shown to the
 *   current user (TS v3). New rows always have an owner.
 * @property pausedMs banked pause time, excluding a pause in progress.
 * @property pausedAt start of the current pause; null unless PAUSED.
 * @property reanchor the start/resume time (epoch ms, 0 = none) until whose
 *   first fresh fix no distance is added, so ground covered while paused is
 *   not counted (`planBatch` `reanchorAfter`).
 * @property synced the server has it; its points have been pruned.
 */
@Entity(
    tableName = "runs",
    indices = [Index(value = ["owner", "state"], name = "idx_runs_owner_state")],
)
internal data class RunEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(defaultValue = "''") val owner: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    val state: RunState,
    @ColumnInfo(name = "paused_ms", defaultValue = "0") val pausedMs: Long = 0,
    @ColumnInfo(name = "paused_at") val pausedAt: Long? = null,
    @ColumnInfo(name = "distance_m", defaultValue = "0") val distanceM: Double = 0.0,
    @ColumnInfo(name = "total_points", defaultValue = "0") val totalPoints: Int = 0,
    @ColumnInfo(name = "dropped_points", defaultValue = "0") val droppedPoints: Int = 0,
    @ColumnInfo(defaultValue = "0") val reanchor: Long = 0,
    @ColumnInfo(defaultValue = "0") val synced: Boolean = false,
) {
    fun toActiveRun(): ActiveRun = ActiveRun(
        id = id,
        owner = owner,
        startedAt = startedAt,
        distanceM = distanceM,
        pausedMs = pausedMs,
        // Only a paused run has a pause in progress (TS getActiveRun).
        pausedAt = if (state == RunState.PAUSED) pausedAt else null,
        state = state,
        droppedPoints = droppedPoints,
        totalPoints = totalPoints,
    )
}

/**
 * One accepted fix on a run's route. Rejected fixes are only counted
 * (`runs.dropped_points`), never stored.
 *
 * The foreign key cascades, so deleting a run can never leave its points
 * behind; `id` exists because Room needs a key and two fixes may share `t`.
 */
@Entity(
    tableName = "run_points",
    foreignKeys = [
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["run_id", "t"], name = "idx_points_run")],
)
internal data class RunPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "run_id") val runId: String,
    val t: Long,
    val lat: Double,
    val lng: Double,
    val accuracy: Double,
)

internal fun GeoFix.toPoint(runId: String) =
    RunPointEntity(runId = runId, t = timestamp, lat = lat, lng = lng, accuracy = accuracy)

/** The last stored point, which the next fix is measured from. */
internal data class AnchorRow(val lat: Double, val lng: Double, val t: Long) {
    fun toAnchor() = Anchor(lat, lng, t)
}

/** A route point, in order, for the polyline. */
internal data class RoutePoint(override val lat: Double, override val lng: Double) : Coordinates
