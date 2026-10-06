package timeshealth.app.core.runtracker

import timeshealth.app.core.domain.TimedPoint

import android.Manifest
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timeshealth.app.core.domain.uuidV4
import timeshealth.app.core.runtracker.db.RunEntity
import timeshealth.app.core.runtracker.db.RunTrackerDatabase
import timeshealth.app.core.runtracker.service.TrackingControl

/** Wall-clock milliseconds; fix timestamps are wall-clock too. A seam for tests. */
internal fun interface WallClock {
    fun nowMs(): Long
}

/**
 * The run tracker (PRD §8.6, marked do-not-cut): "full GPS tracking, not a
 * stopwatch". What the UI and ViewModels use.
 *
 * Four requirements from §8.6 drive the design (see apps/mobile/src/lib/runTracker.ts):
 *
 *  1. Background and screen-off must keep recording → a foreground service of
 *     type "location" with an ongoing notification ([RunTrackerService]),
 *     started while the app is on screen. No background location permission.
 *  2. App killed mid-run → recoverable, not lost → every fix is written to
 *     Room as it arrives; nothing about a run lives only in memory. [recover]
 *     brings it back.
 *  3. Poor GPS → never silently record a wrong distance → bad fixes are dropped
 *     (`planBatch`) and a run that drops too many carries an accuracy warning.
 *  4. Long runs are a battery concern → GPS only while RUNNING, a 2 s interval
 *     with a 5 m distance filter, no polling loop anywhere.
 *
 * Owner scoping (shared phones): every run is filed under the profile id that
 * recorded it. [activeRun], [recover] and [unsyncedRuns] take that owner;
 * [resume], [finish] and [discard] act only on a run belonging to whoever
 * [RunOwnerProvider] says is signed in now. One person's run never shows for,
 * resumes GPS for, or uploads as the next person.
 *
 * State changes are serialised: a double tap, or the notification's Pause
 * racing the screen's, applies once and in order.
 */
@Singleton
class RunTracker @Inject internal constructor(
    private val db: RunTrackerDatabase,
    private val tracking: TrackingControl,
    private val owners: RunOwnerProvider,
    private val clock: WallClock,
) {
    private val dao = db.runDao()
    private val ops = Mutex()

    /**
     * The [owner]'s unfinished run, live: it re-emits whenever the service
     * writes a batch (screen off included), on pause/resume, and emits null
     * once the run is finished or discarded, here or from another screen. A
     * screen showing a run must treat null, or a different id, as "this run
     * ended elsewhere" and go back to Ready (the RN "stale panel" case), except
     * while its own [finish] is in flight.
     *
     * Moving time is not in the stream (nothing in the database changes every
     * second): tick a 1 s clock in the UI and call [ActiveRun.movingSeconds].
     * Call [recover] once before trusting a RUNNING state after a cold start.
     */
    fun activeRun(owner: String): Flow<ActiveRun?> =
        if (owner.isBlank()) {
            flowOf(null)
        } else {
            dao.observeUnfinishedFor(owner).map { it?.toActiveRun() }.distinctUntilChanged()
        }

    /**
     * [runId]'s recorded route with timestamps, live, for the map while running. Points are
     * kept until the finished run has synced, so read [route] for the summary straight after
     * [finish].
     */
    fun liveRoute(runId: String): Flow<List<TimedPoint>> =
        dao.observeTimedRoute(runId).map { rows -> rows.map { TimedPoint(it.lat, it.lng, it.t) } }

    /** [runId]'s route with timestamps now (empty once a finished run has synced and been pruned). */
    suspend fun route(runId: String): List<TimedPoint> = dao.timedRoute(runId).map { TimedPoint(it.lat, it.lng, it.t) }

    /** Precise location is granted. Check before [start]/[resume]; request [LOCATION_PERMISSIONS]. */
    fun hasLocationPermission(): Boolean = tracking.hasLocationPermission()

    /**
     * Starts a run for [owner] (the signed-in profile id) and its GPS service.
     *
     * If [owner] already has an unfinished run, returns that instead of
     * starting a second one; take its real state (it may be PAUSED) and time
     * rather than assuming a fresh start.
     *
     * Someone else's unfinished run left parked on this phone is finished as
     * THEIRS (at the moment it stopped recording), so it still syncs when they
     * sign back in. If it is under [MIN_SAVED_M] it was never a run: deleted.
     *
     * Call from a user tap, with the app on screen: Android only lets a
     * foreground service get location when it is started from the foreground.
     *
     * @throws IllegalArgumentException if [owner] is blank: a run filed under
     *   nobody would show for anyone on the phone.
     * @throws LocationPermissionRequiredException without precise location.
     *   Nothing is written.
     * @throws IllegalStateException (ForegroundServiceStartNotAllowedException)
     *   if the OS refuses to start the service; the new run is removed again.
     */
    suspend fun start(owner: String): ActiveRun {
        require(owner.isNotBlank()) { "start() needs the signed-in profile id" }
        return ops.withLock {
            val existing = db.withTransaction { dao.unfinishedFor(owner)?.let { parkIfOrphaned(it) } }
            if (existing != null) return@withLock existing.toActiveRun()
            if (!tracking.hasLocationPermission()) throw LocationPermissionRequiredException()

            val now = clock.nowMs()
            val run = RunEntity(
                id = uuidV4(),
                owner = owner,
                startedAt = now,
                state = RunState.RUNNING,
                // A cached fix from before the run (say, from home before
                // driving to the park) must not become its first point.
                reanchor = now,
            )
            db.withTransaction {
                for (other in dao.unfinishedOfOthers(owner)) finishParked(other, now)
                dao.insertRun(run)
            }
            try {
                tracking.start()
            } catch (e: RuntimeException) {
                db.withTransaction { deleteRunAndPoints(run.id) }
                throw e
            }
            run.toActiveRun()
        }
    }

    /**
     * Pauses the recording run: GPS stops (battery), and time from now until
     * [resume] is not running time. Not owner-scoped, so the notification's
     * Pause always works; pausing is always safe. Returns the run, or null.
     */
    suspend fun pause(): ActiveRun? = ops.withLock {
        val run = db.withTransaction {
            val found = dao.unfinished() ?: return@withTransaction null
            if (found.state == RunState.RUNNING && tracking.isActive) {
                dao.pause(found.id, clock.nowMs())
            } else {
                parkIfOrphaned(found)
            }
            dao.byId(found.id)
        }
        tracking.stop()
        run?.toActiveRun()
    }

    /**
     * Resumes the signed-in user's paused run: banks the pause and restarts
     * GPS. The run re-anchors on the first fix newer than now, so wherever the
     * runner walked while paused is not added to it. Returns the run, or null
     * when the signed-in user has no unfinished run.
     *
     * Same foreground and permission rules as [start]: after a revoke (which
     * kills the app; the run comes back paused) this throws
     * [LocationPermissionRequiredException] and the run stays paused.
     */
    suspend fun resume(): ActiveRun? = ops.withLock {
        val owner = signedInOwner() ?: return@withLock null
        val found = dao.unfinishedFor(owner) ?: return@withLock null
        if (found.state == RunState.RUNNING && tracking.isActive) return@withLock found.toActiveRun()
        if (!tracking.hasLocationPermission()) throw LocationPermissionRequiredException()

        val now = clock.nowMs()
        db.withTransaction {
            val run = parkIfOrphaned(found)
            val pausedFor = if (run.pausedAt != null) now - run.pausedAt else 0L
            dao.resume(run.id, pausedFor, now)
        }
        try {
            tracking.start()
        } catch (e: RuntimeException) {
            // Back to paused from now: the time accounting is as if Resume was never tapped.
            dao.pause(found.id, now)
            throw e
        }
        dao.byId(found.id)?.toActiveRun()
    }

    /**
     * Finishes the signed-in user's unfinished run (running or paused) and
     * returns its summary, or null if there is none (finished or discarded
     * elsewhere: a stale screen should go back to Ready).
     *
     * A run under [MIN_SAVED_M] raw metres is an accidental start-stop: it is
     * deleted in the same transaction and the result has
     * [FinishedRun.isTooShort]; show "Too short to save" and don't upload.
     * Otherwise it is saved unsynced: enqueue [RunSync.enqueue] to upload it.
     */
    suspend fun finish(): FinishedRun? = ops.withLock {
        val owner = signedInOwner() ?: return@withLock null
        val summary = db.withTransaction {
            val found = dao.unfinishedFor(owner) ?: return@withTransaction null
            val run = parkIfOrphaned(found)
            val endedAt = clock.nowMs()
            // Finishing straight from a pause: that last pause isn't running time either.
            val pausedMs = totalPausedMs(run.pausedMs, run.pausedAt, endedAt)
            val summary = summarize(
                id = run.id,
                owner = run.owner,
                startedAt = run.startedAt,
                endedAt = endedAt,
                pausedMs = pausedMs,
                distanceM = run.distanceM,
                totalPoints = run.totalPoints,
                droppedPoints = run.droppedPoints,
                route = dao.route(run.id),
            )
            if (summary.isTooShort) deleteRunAndPoints(run.id) else dao.finish(run.id, endedAt, pausedMs)
            summary
        }
        if (summary != null) tracking.stop()
        summary
    }

    /**
     * Deletes run [runId], but ONLY while it is still the signed-in user's
     * unfinished run. Never an already-finished run, which may be unsynced
     * (finished meanwhile from another screen): that is the user's data.
     * Returns whether anything was deleted.
     */
    suspend fun discard(runId: String): Boolean = ops.withLock {
        val owner = signedInOwner() ?: return@withLock false
        val deleted = db.withTransaction {
            if (dao.unfinishedFor(owner)?.id != runId) return@withTransaction false
            deleteRunAndPoints(runId)
            true
        }
        // Only for the run actually discarded: a stale id must not stop a live run's GPS.
        if (deleted) tracking.stop()
        deleted
    }

    /**
     * Call when the tracker opens (§8.6: "app killed mid-run → run must be
     * recoverable"). Returns [owner]'s unfinished run, or null.
     *
     * If the app process died, the GPS service died with it, so a run still
     * marked RUNNING is NOT recording, whatever its row says. Showing it as
     * live would freeze the distance while the clock kept counting. Instead
     * the dead stretch is banked as a pause from the last recorded fix (never
     * before the start or the last resume), and the runner taps Resume, which
     * restarts GPS and re-anchors on their real position.
     */
    suspend fun recover(owner: String): ActiveRun? {
        if (owner.isBlank()) return null
        return ops.withLock {
            db.withTransaction { dao.unfinishedFor(owner)?.let { parkIfOrphaned(it) } }?.toActiveRun()
        }
    }

    /**
     * [owner]'s finished runs the server doesn't have yet, oldest first, with
     * their routes (a late sync uploads the route too, not just a live finish).
     */
    suspend fun unsyncedRuns(owner: String): List<FinishedRun> {
        if (owner.isBlank()) return emptyList()
        return db.withTransaction {
            dao.unsyncedFor(owner).map { r ->
                summarize(
                    id = r.id,
                    owner = r.owner,
                    startedAt = r.startedAt,
                    endedAt = r.endedAt ?: r.startedAt,
                    pausedMs = r.pausedMs,
                    distanceM = r.distanceM,
                    totalPoints = r.totalPoints,
                    droppedPoints = r.droppedPoints,
                    route = dao.route(r.id),
                )
            }
        }
    }

    /**
     * The server has run [id]. Its raw fixes (thousands on a long run) are
     * dead weight on the phone from here on, so they are deleted with it.
     * Only applies to a finished run.
     */
    suspend fun markSynced(id: String) {
        db.withTransaction {
            if (dao.markSynced(id) > 0) dao.deletePoints(id)
        }
    }

    /** Whether run [id] has reached the server: "Saved to your run history" vs "saved on this phone". */
    fun isSynced(id: String): Flow<Boolean> =
        dao.observeSynced(id).map { it == true }.distinctUntilChanged()

    /**
     * Sign-out on a shared phone: GPS must not keep tracking for someone who
     * has left. Their unfinished run is parked as paused, stays on the phone
     * under their name, and syncs when they sign back in.
     */
    suspend fun suspendForSignOut() {
        ops.withLock {
            db.withTransaction {
                val run = dao.unfinished() ?: return@withTransaction
                if (run.state != RunState.RUNNING) return@withTransaction
                if (tracking.isActive) dao.pause(run.id, clock.nowMs()) else parkIfOrphaned(run)
            }
            tracking.stop()
        }
    }

    // ── Internals (call inside a transaction) ──────────────────────────────

    private suspend fun signedInOwner(): String? = owners.currentOwner()?.takeIf { it.isNotBlank() }

    /**
     * A run marked RUNNING with no GPS recording in this process was killed
     * with the app: park it as paused from its last fix (see [recover]).
     */
    private suspend fun parkIfOrphaned(run: RunEntity): RunEntity {
        if (run.state != RunState.RUNNING || tracking.isActive) return run
        val pausedAt = recoveryPausedAt(run.startedAt, dao.lastPoint(run.id)?.t, run.reanchor)
        dao.pause(run.id, pausedAt)
        return run.copy(state = RunState.PAUSED, pausedAt = pausedAt)
    }

    /** Finishes another person's parked run as theirs, ending when it stopped recording. */
    private suspend fun finishParked(other: RunEntity, now: Long) {
        val run = parkIfOrphaned(other)
        val endedAt = run.pausedAt ?: now
        if (run.distanceM < MIN_SAVED_M) {
            deleteRunAndPoints(run.id)
        } else {
            // Ended at the pause: the pause in progress is outside the run, not inside it.
            dao.finish(run.id, endedAt, run.pausedMs)
        }
    }

    private suspend fun deleteRunAndPoints(id: String) {
        dao.deletePoints(id)
        dao.deleteRun(id)
    }

    companion object {
        /**
         * Request both together: Android 12+ won't grant FINE otherwise, and
         * the user may still pick "Approximate". Approximate is not enough;
         * every fix would fail the 25 m accuracy gate, so [hasLocationPermission]
         * stays false until precise is granted.
         */
        val LOCATION_PERMISSIONS: Array<String> = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
