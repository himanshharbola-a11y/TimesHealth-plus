package timeshealth.app.core.runtracker

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import timeshealth.app.core.domain.Anchor

/** Pause accounting, the finish maths and the recovery rule (PRD §8.6), on the JVM. */
class RunAccountingTest {

    private companion object {
        const val T0 = 1_790_000_000_000L
        const val MIN = 60_000L
    }

    private fun summary(
        startedAt: Long = T0,
        endedAt: Long = T0 + 30 * MIN,
        pausedMs: Long = 0,
        distanceM: Double = 5_000.0,
        totalPoints: Int = 100,
        droppedPoints: Int = 0,
        route: List<Anchor> = emptyList(),
    ) = summarize("id", "owner", startedAt, endedAt, pausedMs, distanceM, totalPoints, droppedPoints, route)

    // ── Moving time ───────────────────────────────────────────────────────

    @Test
    fun `moving time excludes banked pauses`() {
        // 30 min elapsed, 10 of them paused at a signal.
        assertThat(movingSeconds(T0, 10 * MIN, null, T0 + 30 * MIN)).isEqualTo(20 * 60L)
    }

    @Test
    fun `a pause in progress stops the clock`() {
        // Ran 5 min, paused at 5 min; at 9 min the timer still shows 5:00.
        assertThat(movingSeconds(T0, 0, T0 + 5 * MIN, T0 + 9 * MIN)).isEqualTo(5 * 60L)
    }

    @Test
    fun `the clock does not jump on resume`() {
        // Paused 5→9 min, resumed (4 min banked): at 10 min it reads 6:00.
        assertThat(movingSeconds(T0, 4 * MIN, null, T0 + 10 * MIN)).isEqualTo(6 * 60L)
    }

    @Test
    fun `moving time rounds to the nearest second and is never negative`() {
        assertThat(movingSeconds(T0, 0, null, T0 + 1_499)).isEqualTo(1L)
        assertThat(movingSeconds(T0, 0, null, T0 + 1_500)).isEqualTo(2L)
        assertThat(movingSeconds(T0, 0, null, T0 - 5_000)).isEqualTo(0L)
    }

    @Test
    fun `ActiveRun exposes the same moving time and a live pace`() {
        val run = ActiveRun("id", "o", T0, 2_000.0, 2 * MIN, null, RunState.RUNNING, 0, 10)
        assertThat(run.movingSeconds(T0 + 12 * MIN)).isEqualTo(10 * 60L)
        assertThat(run.paceSecPerKm(T0 + 12 * MIN)).isEqualTo(300.0)
        assertThat(run.copy(distanceM = 0.0).paceSecPerKm(T0 + 12 * MIN)).isEqualTo(0.0)
    }

    // ── Pause accounting at finish ────────────────────────────────────────

    @Test
    fun `finishing while running adds no pause`() {
        assertThat(totalPausedMs(3 * MIN, null, T0 + 30 * MIN)).isEqualTo(3 * MIN)
    }

    @Test
    fun `finishing straight from a pause counts that pause too`() {
        // 3 min banked earlier, then paused at 25 min and finished at 30.
        assertThat(totalPausedMs(3 * MIN, T0 + 25 * MIN, T0 + 30 * MIN)).isEqualTo(8 * MIN)
    }

    @Test
    fun `finish duration is moving time, pauses excluded`() {
        val s = summary(endedAt = T0 + 30 * MIN, pausedMs = totalPausedMs(3 * MIN, T0 + 25 * MIN, T0 + 30 * MIN))
        assertThat(s.durationSeconds).isEqualTo(22 * 60)
        // Pace over moving time: 1320 s / 5 km.
        assertThat(s.avgPaceSecPerKm).isEqualTo(264)
    }

    @Test
    fun `duration is at least one second`() {
        assertThat(summary(endedAt = T0, distanceM = 0.0).durationSeconds).isEqualTo(1)
    }

    // ── Finish maths ──────────────────────────────────────────────────────

    @Test
    fun `distance rounds to two decimals for upload while distanceM stays raw`() {
        val s = summary(distanceM = 5_126.0)
        assertThat(s.distanceKm).isEqualTo(5.13)
        assertThat(s.distanceM).isEqualTo(5_126.0)
        assertThat(summary(distanceM = 5_124.9).distanceKm).isEqualTo(5.12)
    }

    @Test
    fun `pace and calories follow the rounded km`() {
        val s = summary(endedAt = T0 + 1_800_000, distanceM = 5_000.0)
        assertThat(s.avgPaceSecPerKm).isEqualTo(360) // 1800 s / 5 km
        assertThat(s.caloriesBurned).isEqualTo(310) // 5 km × 62
    }

    @Test
    fun `no distance means pace 0, not infinity`() {
        val s = summary(distanceM = 4.0)
        assertThat(s.distanceKm).isEqualTo(0.0)
        assertThat(s.avgPaceSecPerKm).isEqualTo(0)
        assertThat(s.caloriesBurned).isEqualTo(0)
    }

    @Test
    fun `accuracy warning only above a quarter of fixes dropped`() {
        assertThat(summary(totalPoints = 100, droppedPoints = 25).hasAccuracyWarning).isFalse()
        assertThat(summary(totalPoints = 100, droppedPoints = 26).hasAccuracyWarning).isTrue()
        assertThat(summary(totalPoints = 0, droppedPoints = 0).hasAccuracyWarning).isFalse()
    }

    @Test
    fun `route polyline needs at least two points`() {
        assertThat(summary(route = emptyList()).routePolyline).isNull()
        assertThat(summary(route = listOf(Anchor(38.5, -120.2, 0))).routePolyline).isNull()
        assertThat(
            summary(route = listOf(Anchor(38.5, -120.2, 0), Anchor(40.7, -120.95, 1), Anchor(43.252, -126.453, 2)))
                .routePolyline,
        ).isEqualTo("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
    }

    // ── The 10 m rule ─────────────────────────────────────────────────────

    @Test
    fun `under 10 m raw is too short even though it rounds to 0_01 km`() {
        val s = summary(distanceM = 9.99)
        assertThat(s.distanceKm).isEqualTo(0.01)
        assertThat(s.isTooShort).isTrue()
    }

    @Test
    fun `10 m and over is a run`() {
        assertThat(summary(distanceM = 10.0).isTooShort).isFalse()
        assertThat(summary(distanceM = 10.0).distanceKm).isEqualTo(0.01)
    }

    // ── Recovery ──────────────────────────────────────────────────────────

    @Test
    fun `recovery parks at the last fix`() {
        assertThat(recoveryPausedAt(T0, T0 + 7 * MIN, T0)).isEqualTo(T0 + 7 * MIN)
    }

    @Test
    fun `recovery never parks before the start`() {
        assertThat(recoveryPausedAt(T0, null, 0)).isEqualTo(T0)
        assertThat(recoveryPausedAt(T0, T0 - 5_000, 0)).isEqualTo(T0)
    }

    @Test
    fun `recovery never parks before the last resume`() {
        // Last fix at 7 min, paused, resumed at 12 min, killed before a new fix:
        // 7→12 is already banked as a pause; parking at 7 would bank it twice.
        assertThat(recoveryPausedAt(T0, T0 + 7 * MIN, T0 + 12 * MIN)).isEqualTo(T0 + 12 * MIN)
    }

    // ── Live weak-signal pill ─────────────────────────────────────────────

    @Test
    fun `weak signal needs more than 8 fixes, over a quarter dropped, and running`() {
        val run = ActiveRun("id", "o", T0, 0.0, 0, null, RunState.RUNNING, droppedPoints = 3, totalPoints = 8)
        assertThat(run.hasWeakSignal).isFalse() // only 8 fixes
        assertThat(run.copy(totalPoints = 9).hasWeakSignal).isTrue() // 3/9 > 0.25
        assertThat(run.copy(totalPoints = 12).hasWeakSignal).isFalse() // 3/12 = 0.25
        assertThat(run.copy(totalPoints = 9, state = RunState.PAUSED, pausedAt = T0).hasWeakSignal).isFalse()
    }
}
