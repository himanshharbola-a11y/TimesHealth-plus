package timeshealth.app.core.runtracker

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The batch processor against a real (in-memory) Room store: `planBatch`'s
 * decisions must land in the database, points and counters together.
 */
@RunWith(RobolectricTestRunner::class)
class RunBatchProcessorTest {

    private val h = Harness()

    @After fun tearDown() = h.close()

    private suspend fun row(id: String) = h.dao.byId(id)!!

    @Test
    fun `accepted fixes are stored and their distance accumulates`() = runTest {
        val run = h.startAs("alice")
        h.runTo(0.0, 10.0, 25.0, fromMs = T0 + SEC)

        val r = row(run.id)
        // The first fresh fix anchors the run and adds nothing; then 10 + 15 m.
        assertThat(r.distanceM).isWithin(0.01).of(25.0)
        assertThat(r.totalPoints).isEqualTo(3)
        assertThat(r.droppedPoints).isEqualTo(0)
        assertThat(r.reanchor).isEqualTo(0L) // anchored: no longer waiting
        assertThat(h.dao.pointCount(run.id)).isEqualTo(3)
    }

    @Test
    fun `distance accumulates across batches from the last stored point`() = runTest {
        val run = h.startAs("alice")
        h.runTo(0.0, 10.0, fromMs = T0 + SEC)
        h.runTo(20.0, 30.0, fromMs = T0 + 7 * SEC)
        assertThat(row(run.id).distanceM).isWithin(0.01).of(30.0)
        assertThat(row(run.id).totalPoints).isEqualTo(4)
    }

    @Test
    fun `inaccurate fixes are dropped and counted, never averaged in`() = runTest {
        val run = h.startAs("alice")
        h.processor.ingest(
            listOf(
                fix(0.0, T0 + SEC),
                fix(10.0, T0 + 4 * SEC, accuracy = 40.0),
                fix(20.0, T0 + 7 * SEC),
            ),
        )
        val r = row(run.id)
        assertThat(r.droppedPoints).isEqualTo(1)
        assertThat(r.totalPoints).isEqualTo(3)
        assertThat(r.distanceM).isWithin(0.01).of(20.0)
        assertThat(h.dao.pointCount(run.id)).isEqualTo(2)
    }

    @Test
    fun `standing-still jitter adds nothing and keeps the anchor`() = runTest {
        val run = h.startAs("alice")
        h.runTo(0.0, 2.0, 3.5, 1.0, fromMs = T0 + SEC)
        assertThat(row(run.id).distanceM).isEqualTo(0.0)
        assertThat(h.dao.pointCount(run.id)).isEqualTo(1)
        assertThat(row(run.id).droppedPoints).isEqualTo(0)
    }

    @Test
    fun `a cached fix from before the start is ignored, a fresh one within the grace anchors`() = runTest {
        h.clock.now = T0 + 60 * SEC
        val run = h.startAs("alice")
        // 3 s before Start: a replay from cache (grace is 2 s).
        h.processor.ingest(listOf(fix(500.0, T0 + 57 * SEC)))
        assertThat(h.dao.pointCount(run.id)).isEqualTo(0)
        assertThat(row(run.id).reanchor).isEqualTo(T0 + 60 * SEC) // still waiting
        assertThat(row(run.id).totalPoints).isEqualTo(1)

        // 1 s before Start: fresh enough. Anchors, adds nothing.
        h.processor.ingest(listOf(fix(0.0, T0 + 59 * SEC)))
        assertThat(h.dao.pointCount(run.id)).isEqualTo(1)
        assertThat(row(run.id).reanchor).isEqualTo(0L)
        assertThat(row(run.id).distanceM).isEqualTo(0.0)
    }

    @Test
    fun `after resume, ground covered while paused is not counted`() = runTest {
        val run = h.startAs("alice")
        h.runTo(0.0, 10.0, 20.0, fromMs = T0 + SEC)

        h.clock.now = T0 + MIN
        h.tracker.pause()
        // Fixes still in flight after Pause are ignored.
        h.processor.ingest(listOf(fix(30.0, T0 + MIN + SEC)))
        assertThat(h.dao.pointCount(run.id)).isEqualTo(3)

        h.clock.now = T0 + 5 * MIN
        h.tracker.resume()
        assertThat(row(run.id).reanchor).isEqualTo(T0 + 5 * MIN)
        // A stale pre-resume replay, then the runner's real position 300 m on.
        h.processor.ingest(listOf(fix(25.0, T0 + MIN), fix(320.0, T0 + 5 * MIN + SEC), fix(330.0, T0 + 5 * MIN + 4 * SEC)))

        // 20 m before the pause + 10 m after it; the 300 m walked while paused is not run.
        assertThat(row(run.id).distanceM).isWithin(0.01).of(30.0)
        assertThat(row(run.id).reanchor).isEqualTo(0L)
    }

    @Test
    fun `a one-off GPS jump is dropped, and the run carries on from the old anchor`() = runTest {
        val run = h.startAs("alice")
        h.runTo(0.0, 10.0, fromMs = T0 + SEC)
        // 500 m in 2 s is no runner; the next fix agrees with the old track.
        h.processor.ingest(listOf(fix(510.0, T0 + 6 * SEC), fix(20.0, T0 + 9 * SEC)))
        val r = row(run.id)
        assertThat(r.droppedPoints).isEqualTo(1)
        assertThat(r.distanceM).isWithin(0.01).of(20.0)
    }

    @Test
    fun `a jump confirmed by the next batch re-anchors there, adding only the confirmed step`() = runTest {
        val run = h.startAs("alice")
        h.runTo(0.0, 10.0, fromMs = T0 + SEC)
        // Signal regained after a gap: the jump arrives alone...
        h.processor.ingest(listOf(fix(510.0, T0 + 6 * SEC)))
        assertThat(row(run.id).distanceM).isWithin(0.01).of(10.0)
        // ...and the NEXT batch agrees with it (10 m in 2 s): the held fix is
        // kept across batches and the run re-anchors on it.
        h.processor.ingest(listOf(fix(520.0, T0 + 8 * SEC)))

        val r = row(run.id)
        assertThat(r.distanceM).isWithin(0.01).of(20.0) // the 500 m gap is not counted
        // planBatch counts both fixes as refused against the old anchor, even
        // though the confirmation puts both on the route.
        assertThat(r.droppedPoints).isEqualTo(2)
        assertThat(h.dao.pointCount(run.id)).isEqualTo(4) // 0, 10, 510, 520
        // The next step measures from the new position.
        h.processor.ingest(listOf(fix(530.0, T0 + 11 * SEC)))
        assertThat(row(run.id).distanceM).isWithin(0.01).of(30.0)
    }

    @Test
    fun `nothing is recorded without a running run`() = runTest {
        assertThat(h.processor.ingest(listOf(fix(0.0, T0)))).isNull()

        val run = h.startAs("alice")
        h.tracker.pause()
        assertThat(h.processor.ingest(listOf(fix(0.0, T0 + SEC)))).isNull()
        assertThat(row(run.id).totalPoints).isEqualTo(0)
    }
}
