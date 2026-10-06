package timeshealth.app.core.runtracker

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timeshealth.app.core.domain.BatchPlan
import timeshealth.app.core.domain.GeoFix
import timeshealth.app.core.domain.planBatch
import timeshealth.app.core.runtracker.db.RunTrackerDatabase
import timeshealth.app.core.runtracker.db.toPoint

/**
 * Applies a batch of GPS fixes to the recording run (PRD §8.6). The rules are
 * `planBatch` in :core:domain; this only feeds it from Room and writes back.
 *
 * Everything a batch changes (its accepted points, the added distance, the
 * fix counters, the re-anchor flag) is written in ONE transaction. A process
 * killed mid-batch leaves the run exactly as it was before the batch, never
 * with points whose distance was not counted or the other way round.
 *
 * Batches are applied one at a time: two overlapping batches would both
 * measure from the same "last point" and count that stretch twice.
 */
@Singleton
internal class RunBatchProcessor @Inject constructor(private val db: RunTrackerDatabase) {

    private val dao = db.runDao()
    private val lock = Mutex()

    /**
     * A fix that implied an impossible speed, held until the next one decides
     * whether it was a one-off error or the runner's real new position. In
     * memory only, as in the TS: after an app kill the run comes back paused
     * and resumes with a re-anchor, which discards any candidate anyway.
     */
    private var candidate: HeldFix? = null

    private data class HeldFix(val runId: String, val fix: GeoFix)

    /**
     * Applies [fixes] (oldest first) to the run that is RUNNING, if any.
     * Returns the plan that was applied, or null when nothing was: no run,
     * or it is paused (fixes still in flight when Pause was tapped are dropped).
     */
    suspend fun ingest(fixes: List<GeoFix>): BatchPlan? {
        if (fixes.isEmpty()) return null
        return lock.withLock {
            val applied = db.withTransaction {
                val run = dao.unfinished()
                if (run == null || run.state != RunState.RUNNING) return@withTransaction null
                val plan = planBatch(
                    anchor = dao.lastPoint(run.id)?.toAnchor(),
                    points = fixes,
                    // reanchor > 0: the start/resume moment whose first fresh
                    // fix starts a new segment; stale fixes before it (less the
                    // grace) are cached replays and are ignored.
                    reanchorAfterIn = run.reanchor.takeIf { it > 0 },
                    candidateIn = candidate?.takeIf { it.runId == run.id }?.fix,
                )
                if (plan.store.isNotEmpty()) dao.insertPoints(plan.store.map { it.toPoint(run.id) })
                dao.applyBatch(run.id, plan.addedM, plan.dropped, plan.total, plan.reanchorAfter ?: 0L)
                run.id to plan
            }
            // Only once committed: a rolled-back batch must not move the candidate either.
            if (applied != null) {
                val (runId, plan) = applied
                candidate = plan.candidate?.let { HeldFix(runId, it) }
            }
            applied?.second
        }
    }
}
