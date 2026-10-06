package timeshealth.app.core.runtracker.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/*
 * "Visible to owner" everywhere below is `owner = '' OR owner = :owner`: a
 * run belongs to the profile that recorded it, and a legacy ownerless row is
 * treated as the current user's (TS schema v3). Callers never pass a blank
 * owner; RunTracker turns that into "nothing visible".
 *
 * Writes are targeted UPDATEs (`distance_m = distance_m + ?`) rather than
 * read-modify-write of a whole row, as in the TS, so a write can never undo
 * another that landed between its read and its write.
 */
@Dao
internal interface RunDao {

    // ── The unfinished run ──────────────────────────────────────────────────

    /** Anyone's unfinished run: what the GPS service records into. */
    @Query("SELECT * FROM runs WHERE state != 'FINISHED' ORDER BY started_at DESC LIMIT 1")
    suspend fun unfinished(): RunEntity?

    @Query("SELECT * FROM runs WHERE state != 'FINISHED' ORDER BY started_at DESC LIMIT 1")
    fun observeUnfinished(): Flow<RunEntity?>

    @Query(
        "SELECT * FROM runs WHERE state != 'FINISHED' AND (owner = '' OR owner = :owner) " +
            "ORDER BY started_at DESC LIMIT 1",
    )
    suspend fun unfinishedFor(owner: String): RunEntity?

    @Query(
        "SELECT * FROM runs WHERE state != 'FINISHED' AND (owner = '' OR owner = :owner) " +
            "ORDER BY started_at DESC LIMIT 1",
    )
    fun observeUnfinishedFor(owner: String): Flow<RunEntity?>

    /** Other people's unfinished runs, parked when they signed out mid-run. */
    @Query("SELECT * FROM runs WHERE state != 'FINISHED' AND owner != '' AND owner != :owner")
    suspend fun unfinishedOfOthers(owner: String): List<RunEntity>

    @Query("SELECT * FROM runs WHERE id = :id")
    suspend fun byId(id: String): RunEntity?

    @Insert
    suspend fun insertRun(run: RunEntity)

    // ── State changes ──────────────────────────────────────────────────────

    @Query("UPDATE runs SET state = 'PAUSED', paused_at = :pausedAt WHERE id = :id AND state = 'RUNNING'")
    suspend fun pause(id: String, pausedAt: Long): Int

    /** Sign-out: park whatever is recording. */
    @Query("UPDATE runs SET state = 'PAUSED', paused_at = :pausedAt WHERE state = 'RUNNING'")
    suspend fun pauseAllRunning(pausedAt: Long): Int

    /** Bank [pausedForMs] and re-anchor on the first fix after [now]. */
    @Query(
        "UPDATE runs SET state = 'RUNNING', paused_ms = paused_ms + :pausedForMs, " +
            "paused_at = NULL, reanchor = :now WHERE id = :id AND state != 'FINISHED'",
    )
    suspend fun resume(id: String, pausedForMs: Long, now: Long): Int

    @Query(
        "UPDATE runs SET state = 'FINISHED', ended_at = :endedAt, paused_ms = :pausedMs, " +
            "paused_at = NULL WHERE id = :id AND state != 'FINISHED'",
    )
    suspend fun finish(id: String, endedAt: Long, pausedMs: Long): Int

    /**
     * One batch of fixes, applied in the same transaction as its points.
     * [reanchor] is the plan's `reanchorAfter`, 0 once a fresh fix anchored.
     */
    @Query(
        "UPDATE runs SET distance_m = distance_m + :addedM, " +
            "dropped_points = dropped_points + :dropped, total_points = total_points + :total, " +
            "reanchor = :reanchor WHERE id = :id",
    )
    suspend fun applyBatch(id: String, addedM: Double, dropped: Int, total: Int, reanchor: Long)

    @Query("DELETE FROM runs WHERE id = :id")
    suspend fun deleteRun(id: String): Int

    // ── Points ─────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertPoints(points: List<RunPointEntity>)

    @Query("SELECT lat, lng, t FROM run_points WHERE run_id = :runId ORDER BY t DESC, id DESC LIMIT 1")
    suspend fun lastPoint(runId: String): AnchorRow?

    @Query("SELECT lat, lng FROM run_points WHERE run_id = :runId ORDER BY t ASC, id ASC")
    suspend fun route(runId: String): List<RoutePoint>

    /** The route with timestamps, live: re-emits as the service writes fixes (map, splits). */
    @Query("SELECT lat, lng, t FROM run_points WHERE run_id = :runId ORDER BY t ASC, id ASC")
    fun observeTimedRoute(runId: String): Flow<List<AnchorRow>>

    @Query("SELECT lat, lng, t FROM run_points WHERE run_id = :runId ORDER BY t ASC, id ASC")
    suspend fun timedRoute(runId: String): List<AnchorRow>

    @Query("SELECT COUNT(*) FROM run_points WHERE run_id = :runId")
    suspend fun pointCount(runId: String): Int

    @Query("DELETE FROM run_points WHERE run_id = :runId")
    suspend fun deletePoints(runId: String)

    // ── Sync ───────────────────────────────────────────────────────────────

    /** Recorded locally, not yet accepted by the server; oldest first. */
    @Query(
        "SELECT * FROM runs WHERE state = 'FINISHED' AND synced = 0 " +
            "AND (owner = '' OR owner = :owner) ORDER BY started_at ASC",
    )
    suspend fun unsyncedFor(owner: String): List<RunEntity>

    @Query("UPDATE runs SET synced = 1 WHERE id = :id AND state = 'FINISHED'")
    suspend fun markSynced(id: String): Int

    /** Null once the run no longer exists. */
    @Query("SELECT synced FROM runs WHERE id = :id")
    fun observeSynced(id: String): Flow<Boolean?>
}
