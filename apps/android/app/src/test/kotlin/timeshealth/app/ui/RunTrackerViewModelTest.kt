package timeshealth.app.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import timeshealth.app.MainDispatcherRule
import timeshealth.app.core.domain.TimedPoint
import timeshealth.app.core.integrations.analytics.AnalyticsEvent
import timeshealth.app.core.runtracker.ActiveRun
import timeshealth.app.core.runtracker.FinishedRun
import timeshealth.app.core.runtracker.RunState
import timeshealth.app.ui.run.RunGateway
import timeshealth.app.ui.run.RunTrackerViewModel
import timeshealth.app.ui.run.RunUi
import timeshealth.app.ui.run.distanceText
import timeshealth.app.ui.run.paceText
import timeshealth.app.ui.run.runName

@OptIn(ExperimentalCoroutinesApi::class)
class RunTrackerViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private class FakeRuns(var owner: String? = "user_1") : RunGateway {
        val active = MutableStateFlow<ActiveRun?>(null)
        var permission = true
        var finishResult: FinishedRun? = null
        val uploads = mutableListOf<String>()
        val events = mutableListOf<String>()
        val route = (0..50).map { TimedPoint(28.6 + it * 0.00009, 77.2, it * 3_000L) } // ~500 m at 5:00/km

        override suspend fun owner() = owner
        override fun activeRun(owner: String) = active
        override fun liveRoute(runId: String) = flowOf(route)
        override suspend fun recover(owner: String) = active.value
        override fun hasLocationPermission() = permission
        override suspend fun start(owner: String): ActiveRun =
            ActiveRun("r1", owner, 0, 0.0, 0, null, RunState.RUNNING, 0, 0).also { active.value = it }
        override suspend fun pause() { active.value = active.value?.copy(state = RunState.PAUSED, pausedAt = 1) }
        override suspend fun resume() { active.value = active.value?.copy(state = RunState.RUNNING, pausedAt = null) }
        override suspend fun finish(): FinishedRun? = finishResult.also { active.value = null }
        override suspend fun route(runId: String) = route
        override fun enqueueUpload(owner: String) { uploads += owner }
        override suspend fun imperial() = false
        override fun nowMs() = 150_000L
        override fun track(event: AnalyticsEvent) { events += event.name }
    }

    private fun finished(distanceM: Double) = FinishedRun(
        id = "r1", owner = "user_1", startedAt = 0, endedAt = 150_000, distanceM = distanceM, distanceKm = distanceM / 1000,
        durationSeconds = 150, avgPaceSecPerKm = 300, caloriesBurned = 30, routePolyline = null, hasAccuracyWarning = false,
    )

    @Test
    fun `without precise location, start asks for it and retries after it is granted`() = runTest {
        val runs = FakeRuns().apply { permission = false }
        val vm = RunTrackerViewModel(runs)
        advanceUntilIdle()
        assertThat(vm.ui.value).isEqualTo(RunUi.Ready)

        vm.start()
        assertThat(vm.needsPermission.value).isTrue()

        runs.permission = true
        vm.permissionResult(granted = true)
        advanceUntilIdle()
        assertThat(vm.ui.value).isInstanceOf(RunUi.Active::class.java)
        assertThat(runs.events).contains("run_started")
    }

    @Test
    fun `start, pause, finish shows the summary with splits and queues the upload`() = runTest {
        val runs = FakeRuns().apply { finishResult = finished(500.0) }
        val vm = RunTrackerViewModel(runs)
        advanceUntilIdle()
        vm.start()
        advanceUntilIdle()
        vm.pause()
        advanceUntilIdle()
        assertThat((vm.ui.value as RunUi.Active).run.isPaused).isTrue()

        vm.finish()
        advanceUntilIdle()
        val summary = vm.ui.value as RunUi.Summary
        assertThat(summary.route).isNotEmpty()
        assertThat(summary.splits).hasSize(1) // a partial ~500 m split
        assertThat(runs.uploads).containsExactly("user_1")
        assertThat(runs.events).contains("run_finished")

        vm.done()
        assertThat(vm.ui.value).isEqualTo(RunUi.Ready)
    }

    @Test
    fun `an accidental start-stop is too short to save and isn't uploaded`() = runTest {
        val runs = FakeRuns().apply { finishResult = finished(4.0) }
        val vm = RunTrackerViewModel(runs)
        advanceUntilIdle()
        vm.start()
        advanceUntilIdle()
        vm.finish()
        advanceUntilIdle()
        assertThat(vm.ui.value).isEqualTo(RunUi.TooShort)
        assertThat(runs.uploads).isEmpty()
    }

    @Test
    fun `signed out, there is nobody to file a run under`() = runTest {
        val vm = RunTrackerViewModel(FakeRuns(owner = null))
        advanceUntilIdle()
        assertThat(vm.ui.value).isEqualTo(RunUi.NoOwner)
    }

    @Test
    fun `display formats`() {
        assertThat(paceText(342.0, imperial = false)).isEqualTo("5:42")
        assertThat(paceText(0.0, imperial = false)).isEqualTo("--:--")
        assertThat(paceText(300.0, imperial = true)).isEqualTo("8:03")
        assertThat(distanceText(5234.0, imperial = false)).isEqualTo("5.23")
        assertThat(distanceText(1609.344, imperial = true)).isEqualTo("1.00")
        // 06:30 IST
        assertThat(runName(java.time.Instant.parse("2026-10-08T01:00:00Z").toEpochMilli())).isEqualTo("Morning Run")
        // 19:30 IST
        assertThat(runName(java.time.Instant.parse("2026-10-08T14:00:00Z").toEpochMilli())).isEqualTo("Evening Run")
    }
}
