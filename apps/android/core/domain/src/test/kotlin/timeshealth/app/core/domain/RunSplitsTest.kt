package timeshealth.app.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RunSplitsTest {

    /** Points due north from (28.6, 77.2), [stepM] apart, [stepMs] apart in time. */
    private fun straightRun(count: Int, stepM: Double, stepMs: Long, startT: Long = 0): List<TimedPoint> {
        // A hair over 1 m per "metre": exact boundaries would sit on float noise.
        val degPerM = 1.0005 / 111_195.0
        return (0 until count).map { i -> TimedPoint(28.6 + i * stepM * degPerM, 77.2, startT + i * stepMs) }
    }

    @Test
    fun `steady 5 min per km gives 300 s splits and a partial last one`() {
        // 10 m every 3 s = 5:00/km, 2.5 km.
        val run = straightRun(count = 251, stepM = 10.0, stepMs = 3_000)
        val splits = computeSplits(run)
        assertThat(splits).hasSize(3)
        splits.take(2).forEach {
            assertThat(it.partial).isFalse()
            assertThat(it.seconds).isWithin(1.0).of(300.0)
            assertThat(it.paceSecPerKm).isWithin(1.0).of(300.0)
        }
        assertThat(splits[2].partial).isTrue()
        assertThat(splits[2].distanceM).isWithin(2.0).of(500.0)
        assertThat(splits[2].paceSecPerKm).isWithin(1.0).of(300.0)
    }

    @Test
    fun `a pause never makes a kilometre look slow`() {
        val first = straightRun(count = 51, stepM = 10.0, stepMs = 3_000) // 500 m in 150 s
        // Resume 5 minutes later, where the runner stopped.
        val last = first.last()
        val second = straightRun(count = 51, stepM = 10.0, stepMs = 3_000, startT = last.t + 300_000)
            .map { it.copy(lat = it.lat + (last.lat - 28.6)) }
        val splits = computeSplits(first + second)
        assertThat(splits.first().partial).isFalse()
        assertThat(splits.first().seconds).isWithin(2.0).of(300.0)
    }

    @Test
    fun `nothing or noise gives no splits`() {
        assertThat(computeSplits(emptyList())).isEmpty()
        assertThat(computeSplits(straightRun(count = 4, stepM = 10.0, stepMs = 3_000))).isEmpty()
    }

    @Test
    fun `mile splits`() {
        val run = straightRun(count = 400, stepM = 10.0, stepMs = 3_000) // 3.99 km
        val miles = computeSplits(run, splitM = 1609.344)
        assertThat(miles.count { !it.partial }).isEqualTo(2)
    }

    @Test
    fun `pace series buckets moving distance`() {
        val series = paceSeries(straightRun(count = 101, stepM = 10.0, stepMs = 3_000)) // 1 km
        assertThat(series).hasSize(10)
        series.forEach { assertThat(it.paceSecPerKm).isWithin(1.0).of(300.0) }
        assertThat(series.last().distanceKm).isWithin(0.01).of(1.0)
    }

    @Test
    fun `a GPS jump the tracker ignored is ignored here too`() {
        val run = straightRun(count = 51, stepM = 10.0, stepMs = 3_000) // 500 m
        // 400 m in 2 s before the run really starts: a glitch, not running.
        val jump = TimedPoint(run.first().lat - 400.0 / 111_195.0, 77.2, -2_000)
        val splits = computeSplits(listOf(jump) + run)
        assertThat(splits).hasSize(1)
        assertThat(splits.single().distanceM).isWithin(2.0).of(500.0)
        assertThat(paceSeries(listOf(jump) + run).minOf { it.paceSecPerKm }).isWithin(5.0).of(300.0)
    }
}
