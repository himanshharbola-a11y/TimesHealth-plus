package timeshealth.app.core.runtracker

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timeshealth.app.core.domain.UUID_RE
import timeshealth.app.core.runtracker.db.RunEntity

@RunWith(RobolectricTestRunner::class)
class RunTrackerTest {

    private val h = Harness()
    private val tracker get() = h.tracker

    @After fun tearDown() = h.close()

    // ── start ──────────────────────────────────────────────────────────────

    @Test
    fun `start records a running run anchored at its start and starts GPS`() = runTest {
        val run = h.startAs("alice")
        assertThat(UUID_RE.matches(run.id)).isTrue()
        assertThat(run.state).isEqualTo(RunState.RUNNING)
        assertThat(run.owner).isEqualTo("alice")
        assertThat(run.startedAt).isEqualTo(T0)
        // A cached fix from before the run must not become its first point.
        assertThat(h.dao.byId(run.id)!!.reanchor).isEqualTo(T0)
        assertThat(h.tracking.starts).isEqualTo(1)
    }

    @Test
    fun `start hands back the unfinished run instead of starting a second`() = runTest {
        val first = h.startAs("alice")
        h.clock.now = T0 + MIN
        tracker.pause()

        h.clock.now = T0 + 2 * MIN
        val again = tracker.start("alice")
        assertThat(again.id).isEqualTo(first.id)
        assertThat(again.state).isEqualTo(RunState.PAUSED) // its real state, not a fresh start
        assertThat(again.movingSeconds(h.clock.now)).isEqualTo(60L)
        assertThat(h.tracking.starts).isEqualTo(1)
    }

    @Test
    fun `start without precise location writes nothing`() = runTest {
        h.tracking.permission = false
        assertThat(thrownBy { tracker.start("alice") }).isInstanceOf(LocationPermissionRequiredException::class.java)
        assertThat(h.dao.unfinished()).isNull()
        assertThat(h.tracking.starts).isEqualTo(0)
    }

    @Test
    fun `start refuses a blank owner, which would show the run to anyone`() = runTest {
        assertThat(thrownBy { tracker.start("") }).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `if the OS refuses the service the new run is removed again`() = runTest {
        h.tracking.refuseStart = IllegalStateException("not allowed from background")
        assertThat(thrownBy { h.startAs("alice") }).isInstanceOf(IllegalStateException::class.java)
        assertThat(h.dao.unfinished()).isNull()
    }

    // ── pause / resume ────────────────────────────────────────────────────

    @Test
    fun `pause stops GPS and resume banks the pause and re-anchors`() = runTest {
        val run = h.startAs("alice")
        h.clock.now = T0 + 5 * MIN
        val paused = tracker.pause()!!
        assertThat(paused.state).isEqualTo(RunState.PAUSED)
        assertThat(paused.pausedAt).isEqualTo(T0 + 5 * MIN)
        assertThat(h.tracking.isActive).isFalse()

        h.clock.now = T0 + 9 * MIN
        val resumed = tracker.resume()!!
        assertThat(resumed.state).isEqualTo(RunState.RUNNING)
        assertThat(resumed.pausedMs).isEqualTo(4 * MIN)
        assertThat(resumed.pausedAt).isNull()
        assertThat(h.dao.byId(run.id)!!.reanchor).isEqualTo(T0 + 9 * MIN)
        assertThat(h.tracking.isActive).isTrue()
        // The clock carries on from 5:00, it doesn't jump by the pause.
        assertThat(resumed.movingSeconds(T0 + 10 * MIN)).isEqualTo(6 * 60L)
    }

    @Test
    fun `pausing twice keeps the first pause time`() = runTest {
        h.startAs("alice")
        h.clock.now = T0 + 5 * MIN
        tracker.pause()
        h.clock.now = T0 + 7 * MIN
        assertThat(tracker.pause()!!.pausedAt).isEqualTo(T0 + 5 * MIN)
    }

    @Test
    fun `resume without permission (revoked meanwhile) leaves the run paused`() = runTest {
        val run = h.startAs("alice")
        h.clock.now = T0 + MIN
        tracker.pause()
        h.tracking.permission = false
        h.clock.now = T0 + 2 * MIN
        assertThat(thrownBy { tracker.resume() }).isInstanceOf(LocationPermissionRequiredException::class.java)
        val row = h.dao.byId(run.id)!!
        assertThat(row.state).isEqualTo(RunState.PAUSED)
        assertThat(row.pausedAt).isEqualTo(T0 + MIN)
        assertThat(row.pausedMs).isEqualTo(0L)
    }

    // ── finish ────────────────────────────────────────────────────────────

    @Test
    fun `finish reports moving time with pauses excluded`() = runTest {
        h.startAs("alice")
        h.runTo(0.0, 10.0, 20.0, 30.0, fromMs = T0 + SEC)
        h.clock.now = T0 + 10 * MIN
        tracker.pause()
        h.clock.now = T0 + 13 * MIN
        tracker.resume()
        h.clock.now = T0 + 20 * MIN

        val done = tracker.finish()!!
        assertThat(done.durationSeconds).isEqualTo(17 * 60) // 20 min less the 3 min pause
        assertThat(done.distanceM).isWithin(0.01).of(30.0)
        assertThat(done.distanceKm).isEqualTo(0.03)
        assertThat(done.endedAt).isEqualTo(T0 + 20 * MIN)
        assertThat(done.routePolyline).isNotNull()
        assertThat(done.isTooShort).isFalse()
        val row = h.dao.byId(done.id)!!
        assertThat(row.state).isEqualTo(RunState.FINISHED)
        assertThat(row.pausedMs).isEqualTo(3 * MIN)
        assertThat(row.pausedAt).isNull()
        assertThat(h.tracking.isActive).isFalse()
    }

    @Test
    fun `finishing straight from a pause does not count the pause as running`() = runTest {
        h.startAs("alice")
        h.runTo(0.0, 10.0, 20.0, fromMs = T0 + SEC)
        h.clock.now = T0 + 10 * MIN
        tracker.pause()
        h.clock.now = T0 + 25 * MIN

        val done = tracker.finish()!!
        assertThat(done.durationSeconds).isEqualTo(10 * 60)
        assertThat(h.dao.byId(done.id)!!.pausedMs).isEqualTo(15 * MIN)
    }

    @Test
    fun `under 10 m raw is deleted as too short, not saved`() = runTest {
        val run = h.startAs("alice")
        h.runTo(0.0, 9.0, fromMs = T0 + SEC)
        h.clock.now = T0 + MIN

        val done = tracker.finish()!!
        assertThat(done.distanceM).isLessThan(MIN_SAVED_M)
        assertThat(done.distanceKm).isEqualTo(0.01) // what a rounded-km check would have seen
        assertThat(done.isTooShort).isTrue()
        assertThat(h.dao.byId(run.id)).isNull()
        assertThat(h.dao.pointCount(run.id)).isEqualTo(0)
        assertThat(tracker.unsyncedRuns("alice")).isEmpty()
    }

    @Test
    fun `just over 10 m is saved`() = runTest {
        h.startAs("alice")
        h.runTo(0.0, 10.5, fromMs = T0 + SEC)
        h.clock.now = T0 + MIN
        assertThat(tracker.finish()!!.isTooShort).isFalse()
        assertThat(tracker.unsyncedRuns("alice")).hasSize(1)
    }

    @Test
    fun `a stale screen finishing a run already finished elsewhere gets null`() = runTest {
        h.startAs("alice")
        h.runTo(0.0, 20.0, fromMs = T0 + SEC)
        assertThat(tracker.finish()).isNotNull()
        assertThat(tracker.finish()).isNull()
        assertThat(tracker.unsyncedRuns("alice")).hasSize(1)
    }

    // ── discard ───────────────────────────────────────────────────────────

    @Test
    fun `discard deletes the live run and its points`() = runTest {
        val run = h.startAs("alice")
        h.runTo(0.0, 20.0, fromMs = T0 + SEC)
        assertThat(tracker.discard(run.id)).isTrue()
        assertThat(h.dao.byId(run.id)).isNull()
        assertThat(h.dao.pointCount(run.id)).isEqualTo(0)
        assertThat(h.tracking.isActive).isFalse()
    }

    @Test
    fun `discard never deletes a run that was already finished`() = runTest {
        val run = h.startAs("alice")
        h.runTo(0.0, 20.0, fromMs = T0 + SEC)
        tracker.finish() // from the other tracker screen
        assertThat(tracker.discard(run.id)).isFalse()
        assertThat(tracker.unsyncedRuns("alice").map { it.id }).containsExactly(run.id)
    }

    @Test
    fun `discarding a stale id leaves the live run, and its GPS, alone`() = runTest {
        val old = h.startAs("alice")
        tracker.discard(old.id)
        h.clock.now = T0 + MIN
        val live = tracker.start("alice")

        assertThat(tracker.discard(old.id)).isFalse()
        assertThat(h.dao.byId(live.id)).isNotNull()
        assertThat(h.tracking.isActive).isTrue()
    }

    // ── recover ───────────────────────────────────────────────────────────

    @Test
    fun `a run killed mid-run is parked as paused at its last fix`() = runTest {
        val run = h.startAs("alice")
        h.runTo(0.0, 10.0, 20.0, fromMs = T0 + SEC) // last fix at T0 + 7 s
        h.tracking.processKilled()

        h.clock.now = T0 + 30 * MIN // reopened half an hour later
        val recovered = tracker.recover("alice")!!
        assertThat(recovered.state).isEqualTo(RunState.PAUSED)
        assertThat(recovered.pausedAt).isEqualTo(T0 + 7 * SEC)
        // The dead half hour is not running time.
        assertThat(recovered.movingSeconds(h.clock.now)).isEqualTo(7L)
        assertThat(h.dao.byId(run.id)!!.state).isEqualTo(RunState.PAUSED)
    }

    @Test
    fun `recovery never parks before the last resume`() = runTest {
        h.startAs("alice")
        h.runTo(0.0, 10.0, fromMs = T0 + SEC) // last fix T0 + 4 s
        h.clock.now = T0 + MIN
        tracker.pause()
        h.clock.now = T0 + 5 * MIN
        tracker.resume() // killed before any new fix arrives
        h.tracking.processKilled()

        h.clock.now = T0 + 20 * MIN
        val recovered = tracker.recover("alice")!!
        assertThat(recovered.pausedAt).isEqualTo(T0 + 5 * MIN)
        // 1 min run before the pause + nothing since the resume; the 1→5 min
        // pause is banked once, not again from the last fix at 4 s.
        assertThat(recovered.movingSeconds(h.clock.now)).isEqualTo(60L)
    }

    @Test
    fun `a run killed before any fix is parked at its start`() = runTest {
        h.startAs("alice")
        h.tracking.processKilled()
        h.clock.now = T0 + 10 * MIN
        assertThat(tracker.recover("alice")!!.pausedAt).isEqualTo(T0)
    }

    @Test
    fun `a run still recording is left live`() = runTest {
        h.startAs("alice")
        h.clock.now = T0 + 10 * MIN
        val run = tracker.recover("alice")!!
        assertThat(run.state).isEqualTo(RunState.RUNNING)
        assertThat(run.pausedAt).isNull()
    }

    @Test
    fun `finishing a killed run without recover still excludes the dead stretch`() = runTest {
        h.startAs("alice")
        h.runTo(0.0, 10.0, 20.0, fromMs = T0 + SEC) // last fix T0 + 7 s
        h.tracking.processKilled()
        h.clock.now = T0 + 30 * MIN
        assertThat(tracker.finish()!!.durationSeconds).isEqualTo(7)
    }

    // ── live stream ───────────────────────────────────────────────────────

    @Test
    fun `activeRun follows the database with no polling`() = runTest {
        val run = h.startAs("alice")
        assertThat(tracker.activeRun("alice").first()!!.id).isEqualTo(run.id)

        h.runTo(0.0, 15.0, fromMs = T0 + SEC)
        assertThat(tracker.activeRun("alice").first { (it?.distanceM ?: 0.0) > 0 }!!.distanceM)
            .isWithin(0.01).of(15.0)

        tracker.finish()
        assertThat(tracker.activeRun("alice").first()).isNull()
    }

    // ── owner scoping (shared phones) ─────────────────────────────────────

    @Test
    fun `one person's run never shows for another`() = runTest {
        h.startAs("alice")
        assertThat(tracker.activeRun("bob").first()).isNull()
        assertThat(tracker.recover("bob")).isNull()
        assertThat(tracker.activeRun("").first()).isNull()
    }

    @Test
    fun `resume, finish and discard act only for the signed-in owner`() = runTest {
        val alices = h.startAs("alice")
        tracker.pause()
        h.owners.owner = "bob"

        assertThat(tracker.resume()).isNull()
        assertThat(tracker.finish()).isNull()
        assertThat(tracker.discard(alices.id)).isFalse()
        assertThat(h.dao.byId(alices.id)!!.state).isEqualTo(RunState.PAUSED)

        h.owners.owner = null // signed out
        assertThat(tracker.finish()).isNull()
    }

    @Test
    fun `sign-out parks the run and stops GPS, and the owner picks it up again`() = runTest {
        val run = h.startAs("alice")
        h.runTo(0.0, 20.0, fromMs = T0 + SEC)
        h.clock.now = T0 + 2 * MIN
        tracker.suspendForSignOut()
        h.owners.owner = null

        val row = h.dao.byId(run.id)!!
        assertThat(row.state).isEqualTo(RunState.PAUSED)
        assertThat(row.pausedAt).isEqualTo(T0 + 2 * MIN)
        assertThat(h.tracking.isActive).isFalse()

        h.owners.owner = "alice"
        assertThat(tracker.recover("alice")!!.id).isEqualTo(run.id)
        h.clock.now = T0 + 3 * MIN
        assertThat(tracker.resume()!!.pausedMs).isEqualTo(MIN)
    }

    @Test
    fun `a new user's start finishes the last user's parked run as theirs`() = runTest {
        val alices = h.startAs("alice")
        h.runTo(0.0, 10.0, 20.0, fromMs = T0 + SEC)
        h.clock.now = T0 + 2 * MIN
        tracker.suspendForSignOut()

        h.clock.now = T0 + 10 * MIN
        val bobs = h.startAs("bob")

        val parked = h.dao.byId(alices.id)!!
        assertThat(parked.state).isEqualTo(RunState.FINISHED)
        assertThat(parked.owner).isEqualTo("alice")
        assertThat(parked.endedAt).isEqualTo(T0 + 2 * MIN) // when it stopped, not when Bob started
        assertThat(tracker.unsyncedRuns("alice").map { it.id }).containsExactly(alices.id)
        assertThat(tracker.unsyncedRuns("bob")).isEmpty()
        assertThat(tracker.activeRun("bob").first()!!.id).isEqualTo(bobs.id)
    }

    @Test
    fun `a parked accidental start under 10 m is dropped, not filed as a run`() = runTest {
        val alices = h.startAs("alice")
        tracker.suspendForSignOut()
        h.clock.now = T0 + MIN
        h.startAs("bob")
        assertThat(h.dao.byId(alices.id)).isNull()
    }

    @Test
    fun `a legacy run with no owner is treated as the current user's`() = runTest {
        h.dao.insertRun(RunEntity(id = "legacy", owner = "", startedAt = T0, state = RunState.PAUSED, pausedAt = T0 + MIN))
        assertThat(tracker.activeRun("bob").first()!!.id).isEqualTo("legacy")
        h.owners.owner = "bob"
        assertThat(tracker.start("bob").id).isEqualTo("legacy")
    }

    // ── sync bookkeeping ──────────────────────────────────────────────────

    @Test
    fun `unsynced runs are the owner's finished ones, with their route`() = runTest {
        h.startAs("alice")
        h.runTo(0.0, 10.0, 20.0, fromMs = T0 + SEC)
        h.clock.now = T0 + 10 * MIN
        val done = tracker.finish()!!

        val pending = tracker.unsyncedRuns("alice")
        assertThat(pending).containsExactly(done)
        assertThat(pending.single().routePolyline).isNotNull()
    }

    @Test
    fun `markSynced prunes the run's points and drops it from the queue`() = runTest {
        h.startAs("alice")
        h.runTo(0.0, 10.0, 20.0, fromMs = T0 + SEC)
        h.clock.now = T0 + 10 * MIN
        val done = tracker.finish()!!
        assertThat(h.dao.pointCount(done.id)).isEqualTo(3)
        assertThat(tracker.isSynced(done.id).first()).isFalse()

        tracker.markSynced(done.id)
        assertThat(h.dao.pointCount(done.id)).isEqualTo(0)
        assertThat(h.dao.byId(done.id)!!.synced).isTrue()
        assertThat(tracker.unsyncedRuns("alice")).isEmpty()
        assertThat(tracker.isSynced(done.id).first()).isTrue()
    }

    @Test
    fun `markSynced never prunes a run that is still recording`() = runTest {
        val run = h.startAs("alice")
        h.runTo(0.0, 10.0, fromMs = T0 + SEC)
        tracker.markSynced(run.id)
        assertThat(h.dao.pointCount(run.id)).isEqualTo(2)
        assertThat(h.dao.byId(run.id)!!.synced).isFalse()
    }
}
