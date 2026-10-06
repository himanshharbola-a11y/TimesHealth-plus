package timeshealth.app.ui

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import timeshealth.app.MainDispatcherRule
import timeshealth.app.core.integrations.video.PlayableStream
import timeshealth.app.core.integrations.video.UnsupportedVideoProviderException
import timeshealth.app.core.model.ApiError
import timeshealth.app.core.model.LiveClassCard
import timeshealth.app.core.model.LiveClassJoinResponse
import timeshealth.app.core.model.LiveClassState
import timeshealth.app.core.model.VideoSource
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.ui.live.LiveClassGateway
import timeshealth.app.ui.live.LiveClassViewModel
import timeshealth.app.ui.live.LivePhase

@OptIn(ExperimentalCoroutinesApi::class)
class LiveClassViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val start = Instant.parse("2026-10-08T01:00:00Z").toEpochMilli()
    private val hour = 3_600_000L

    /** A gateway whose clock is the test's virtual time, starting at [nowAtZero]. */
    private inner class FakeGateway(val scope: TestScope, val nowAtZero: Long) : LiveClassGateway {
        var card: LiveClassCard? = card()
        var joinError: Throwable? = null
        var resolveError: Throwable? = null
        var joins = 0
        override val reminded = MutableStateFlow<Set<String>>(emptySet())

        override suspend fun card(id: String, refresh: Boolean) = card
        override suspend fun join(id: String): LiveClassJoinResponse {
            joins++
            joinError?.let { throw it }
            return LiveClassJoinResponse(
                liveClassId = id, video = VideoSource("url", "https://cdn.test/a.m3u8"),
                startsAt = Instant.ofEpochMilli(start).toString(), endsAt = Instant.ofEpochMilli(start + hour).toString(),
                serverTime = Instant.ofEpochMilli(nowMs()).toString(), positionMs = 0, attendanceRecorded = true,
            )
        }
        override suspend fun resolve(join: LiveClassJoinResponse, startsAtMs: Long): PlayableStream {
            resolveError?.let { throw it }
            return PlayableStream(join.video.ref, premiereStartEpochMs = startsAtMs)
        }
        override fun remind(card: LiveClassCard, startsAtMs: Long) { reminded.value = reminded.value + card.id }
        override fun cancelReminder(id: String) { reminded.value = reminded.value - id }
        override fun nowMs(): Long = nowAtZero + scope.testScheduler.currentTime
    }

    private fun card(state: LiveClassState = LiveClassState.SCHEDULED) = LiveClassCard(
        id = "lc1", title = "Sunrise flow", startsAt = Instant.ofEpochMilli(start).toString(),
        endsAt = Instant.ofEpochMilli(start + hour).toString(), durationMinutes = 60, isFree = false, state = state, canJoin = true,
    )

    private fun vm(gateway: LiveClassGateway) = LiveClassViewModel(SavedStateHandle(mapOf("id" to "lc1")), gateway)

    @Test
    fun `before the wait room it waits, then joins by itself, counts down, plays and ends`() = runTest {
        val gateway = FakeGateway(this, nowAtZero = start - 2 * hour)
        val vm = vm(gateway)
        runCurrent()
        assertThat(vm.state.value.phase).isEqualTo(LivePhase.NotOpen(start - hour, start))
        assertThat(gateway.joins).isEqualTo(0)

        advanceTimeBy(hour + 1) // the wait room opens
        assertThat(vm.state.value.phase).isInstanceOf(LivePhase.WaitRoom::class.java)
        assertThat(gateway.joins).isEqualTo(1)

        advanceTimeBy(hour) // the class starts
        val playing = vm.state.value.phase as LivePhase.Playing
        assertThat(playing.startsAtMs).isEqualTo(start)

        advanceTimeBy(hour) // and ends
        assertThat(vm.state.value.phase).isEqualTo(LivePhase.Ended)
    }

    @Test
    fun `joining mid-class plays straight away`() = runTest {
        val vm = vm(FakeGateway(this, nowAtZero = start + 10 * 60_000))
        runCurrent()
        assertThat(vm.state.value.phase).isInstanceOf(LivePhase.Playing::class.java)
    }

    @Test
    fun `server refusals become the right screens`() = runTest {
        fun refusal(code: String) = ApiRequestException(if (code == "NOT_ENTITLED") 403 else 409, ApiError(code, "x"))
        for ((code, expected) in listOf(
            "NOT_ENTITLED" to LivePhase.Locked,
            "CLASS_ENDED" to LivePhase.Ended,
            "CLASS_CANCELLED" to LivePhase.Cancelled,
        )) {
            val gateway = FakeGateway(this, nowAtZero = start).apply { joinError = refusal(code) }
            val vm = vm(gateway)
            runCurrent()
            assertThat(vm.state.value.phase).isEqualTo(expected)
        }
    }

    @Test
    fun `a cancelled class is said so without joining`() = runTest {
        val gateway = FakeGateway(this, nowAtZero = start).apply { card = card(LiveClassState.CANCELLED) }
        val vm = vm(gateway)
        runCurrent()
        assertThat(vm.state.value.phase).isEqualTo(LivePhase.Cancelled)
        assertThat(gateway.joins).isEqualTo(0)
    }

    @Test
    fun `a video provider this build can't play is explained, and retry joins again`() = runTest {
        val gateway = FakeGateway(this, nowAtZero = start).apply { resolveError = UnsupportedVideoProviderException("slike") }
        val vm = vm(gateway)
        runCurrent()
        val failed = vm.state.value.phase as LivePhase.Failed
        assertThat(failed.error.message).isEqualTo(LiveClassViewModel.UNSUPPORTED_VIDEO)

        gateway.resolveError = null
        vm.retry()
        runCurrent()
        assertThat(vm.state.value.phase).isInstanceOf(LivePhase.Playing::class.java)
        assertThat(gateway.joins).isEqualTo(2)
    }

    @Test
    fun `remind me toggles the reminder`() = runTest {
        val gateway = FakeGateway(this, nowAtZero = start - 2 * hour)
        val vm = vm(gateway)
        runCurrent()
        vm.toggleReminder()
        runCurrent()
        assertThat(vm.state.value.reminderSet).isTrue()
        vm.toggleReminder()
        runCurrent()
        assertThat(vm.state.value.reminderSet).isFalse()
    }

    @Test
    fun `the wait room clock`() {
        assertThat(timeshealth.app.ui.live.clockCountdown(872)).isEqualTo("14:32")
        assertThat(timeshealth.app.ui.live.clockCountdown(3849)).isEqualTo("1:04:09")
        assertThat(timeshealth.app.ui.live.clockCountdown(-5)).isEqualTo("0:00")
    }
}
