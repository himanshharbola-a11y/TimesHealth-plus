package timeshealth.app.ui.gate

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import timeshealth.app.FakeAccountGateway
import timeshealth.app.FakeSessionGateway
import timeshealth.app.MainDispatcherRule
import timeshealth.app.buildInfo
import timeshealth.app.appConfig
import timeshealth.app.core.data.session.SessionStatus
import timeshealth.app.core.data.session.SignInMethod
import timeshealth.app.core.model.SessionResponse
import timeshealth.app.httpError
import timeshealth.app.networkError
import timeshealth.app.offlinePass
import timeshealth.app.sessionResponse

@OptIn(ExperimentalCoroutinesApi::class)
class GateViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val session = FakeSessionGateway()
    private val account = FakeAccountGateway()
    private val signedIn = SessionStatus.SignedIn(SignInMethod.PERSONA)

    private fun gate(version: String = "1.0.0") = GateViewModel(session, account, buildInfo(version = version))

    private val GateViewModel.phase get() = state.value.phase

    // ── Launch switches ─────────────────────────────────────────────────────

    @Test
    fun `a version below the minimum must update, and never asks who the user is`() = runTest {
        account.configAnswers += appConfig(minVersion = "1.2.0")
        session.emit(signedIn)
        account.sessionAnswers += sessionResponse()

        val vm = gate(version = "1.1.9")
        advanceUntilIdle()

        assertThat(vm.phase).isEqualTo(GatePhase.UpdateRequired("1.1.9"))
        assertThat(account.sessionCalls).isEmpty()
    }

    @Test
    fun `the same or a newer version passes the update gate`() = runTest {
        account.configAnswers += appConfig(minVersion = "1.2.0")
        session.emit(SessionStatus.SignedOut)

        val vm = gate(version = "1.2.0")
        advanceUntilIdle()

        assertThat(vm.phase).isEqualTo(GatePhase.Done(GateDestination.LOGIN))
    }

    @Test
    fun `maintenance shows the server's message, or the default when it sends none`() = runTest {
        account.configAnswers += appConfig(maintenance = true, message = "Upgrading the class schedule")
        session.emit(signedIn)
        val vm = gate()
        advanceUntilIdle()
        assertThat(vm.phase).isEqualTo(GatePhase.Maintenance("Upgrading the class schedule"))

        val account2 = FakeAccountGateway().apply { configAnswers += appConfig(maintenance = true) }
        val vm2 = GateViewModel(session, account2, buildInfo())
        advanceUntilIdle()
        assertThat(vm2.phase).isEqualTo(GatePhase.Maintenance(GateViewModel.MAINTENANCE_DEFAULT))
        assertThat(account.sessionCalls).isEmpty()
    }

    @Test
    fun `retry during maintenance refetches the config fresh and carries on once it is over`() = runTest {
        account.configAnswers += appConfig(maintenance = true)
        account.configAnswers += appConfig(maintenance = false)
        account.sessionAnswers += sessionResponse()
        session.emit(signedIn)
        val vm = gate()
        advanceUntilIdle()
        assertThat(vm.phase).isInstanceOf(GatePhase.Maintenance::class.java)

        vm.retry()
        assertThat(vm.phase).isEqualTo(GatePhase.Maintenance(GateViewModel.MAINTENANCE_DEFAULT, retrying = true))
        advanceUntilIdle()

        assertThat(account.configCalls).containsExactly(false, true).inOrder()
        assertThat(vm.phase).isEqualTo(GatePhase.Done(GateDestination.TABS))
    }

    @Test
    fun `the config failing does not lock anyone out (fail open)`() = runTest {
        account.configAnswers += networkError()
        account.sessionAnswers += sessionResponse()
        session.emit(signedIn)

        val vm = gate()
        advanceUntilIdle()

        assertThat(vm.phase).isEqualTo(GatePhase.Done(GateDestination.TABS))
    }

    // ── Session ─────────────────────────────────────────────────────────────

    @Test
    fun `waits for the saved login to be restored before asking for the session`() = runTest {
        account.configAnswers += appConfig()
        account.sessionAnswers += sessionResponse()

        val vm = gate()
        advanceUntilIdle()
        assertThat(vm.phase).isEqualTo(GatePhase.Starting)
        assertThat(account.sessionCalls).isEmpty()

        session.emit(signedIn)
        advanceUntilIdle()
        assertThat(account.sessionCalls).containsExactly(false)
        assertThat(vm.phase).isEqualTo(GatePhase.Done(GateDestination.TABS))
    }

    @Test
    fun `signed out goes to login`() = runTest {
        account.configAnswers += appConfig()
        val vm = gate()
        session.emit(SessionStatus.SignedOut)
        advanceUntilIdle()

        assertThat(vm.phase).isEqualTo(GatePhase.Done(GateDestination.LOGIN))
        assertThat(account.sessionCalls).isEmpty()
    }

    @Test
    fun `a new or free account goes through onboarding, a known one straight to the tabs`() = runTest {
        account.configAnswers += appConfig()
        account.sessionAnswers += sessionResponse(needsOnboarding = true)
        session.emit(signedIn)
        val vm = gate()
        advanceUntilIdle()
        assertThat(vm.phase).isEqualTo(GatePhase.Done(GateDestination.ONBOARDING))

        val known = FakeAccountGateway().apply {
            configAnswers += appConfig()
            sessionAnswers += sessionResponse(needsOnboarding = false)
        }
        val vm2 = GateViewModel(session, known, buildInfo())
        advanceUntilIdle()
        assertThat(vm2.phase).isEqualTo(GatePhase.Done(GateDestination.TABS))
    }

    @Test
    fun `an unreachable API says so, and Try again refetches the session fresh`() = runTest {
        account.configAnswers += appConfig()
        account.sessionAnswers += networkError()
        account.sessionAnswers += sessionResponse()
        session.emit(signedIn)
        val vm = gate()
        advanceUntilIdle()
        assertThat(vm.phase).isEqualTo(GatePhase.Unreachable())

        vm.retry()
        advanceUntilIdle()

        assertThat(account.sessionCalls).containsExactly(false, true).inOrder()
        assertThat(vm.phase).isEqualTo(GatePhase.Done(GateDestination.TABS))
    }

    @Test
    fun `while a retry is in flight the button shows it, and a second tap does nothing`() = runTest {
        val pending = CompletableDeferred<SessionResponse>()
        account.configAnswers += appConfig()
        account.sessionAnswers += httpError(503)
        account.sessionAnswers += pending
        session.emit(signedIn)
        val vm = gate()
        advanceUntilIdle()

        vm.retry()
        runCurrent()
        assertThat(vm.phase).isEqualTo(GatePhase.Unreachable(retrying = true))
        vm.retry()
        runCurrent()
        assertThat(account.sessionCalls).hasSize(2)

        pending.complete(sessionResponse(needsOnboarding = true))
        advanceUntilIdle()
        assertThat(vm.phase).isEqualTo(GatePhase.Done(GateDestination.ONBOARDING))
    }

    @Test
    fun `a session that ends while the gate waits sends the user to login`() = runTest {
        account.configAnswers += appConfig()
        account.sessionAnswers += networkError()
        session.emit(signedIn)
        val vm = gate()
        advanceUntilIdle()
        assertThat(vm.phase).isEqualTo(GatePhase.Unreachable())

        session.emit(SessionStatus.SignedOut)
        advanceUntilIdle()
        assertThat(vm.phase).isEqualTo(GatePhase.Done(GateDestination.LOGIN))
    }

    // ── Saved race passes (§8.3) ────────────────────────────────────────────

    @Test
    fun `the splash offers saved passes only once the launch has been slow for 3 s`() = runTest {
        account.configAnswers += appConfig()
        account.passes = listOf(offlinePass("evt_hyd", "Hyderabad Half Marathon"))
        val vm = gate() // status stays Loading: a slow launch
        runCurrent()

        assertThat(vm.state.value.passes).containsExactly(PassShortcut("evt_hyd", "Hyderabad Half Marathon"))
        assertThat(vm.state.value.showPassesOnSplash).isFalse()

        advanceTimeBy(GateViewModel.SLOW_AFTER.inWholeMilliseconds - 1)
        runCurrent()
        assertThat(vm.state.value.showPassesOnSplash).isFalse()

        advanceTimeBy(2)
        runCurrent()
        assertThat(vm.state.value.showPassesOnSplash).isTrue()
    }

    @Test
    fun `the unreachable message carries the saved passes, without waiting`() = runTest {
        account.configAnswers += appConfig()
        account.sessionAnswers += networkError()
        account.passes = listOf(offlinePass("evt_blr", "Bengaluru 10K"))
        session.emit(signedIn)
        val vm = gate()
        runCurrent()

        assertThat(vm.phase).isEqualTo(GatePhase.Unreachable())
        assertThat(vm.state.value.passes.map { it.eventId }).containsExactly("evt_blr")
    }

    @Test
    fun `no saved passes, nothing to offer`() = runTest {
        account.configAnswers += appConfig()
        val vm = gate()
        advanceUntilIdle()
        assertThat(vm.state.value.slow).isTrue()
        assertThat(vm.state.value.showPassesOnSplash).isFalse()
    }

    // ── Version comparison (index.tsx isOlder) ──────────────────────────────

    @Test
    fun `isOlderVersion compares major, minor, patch numerically`() {
        assertThat(isOlderVersion("1.0.0", "1.0.1")).isTrue()
        assertThat(isOlderVersion("1.9.0", "1.10.0")).isTrue()
        assertThat(isOlderVersion("2.0.0", "1.99.99")).isFalse()
        assertThat(isOlderVersion("1.2.3", "1.2.3")).isFalse()
        // Missing parts are 0; extra parts are ignored.
        assertThat(isOlderVersion("1.2", "1.2.0")).isFalse()
        assertThat(isOlderVersion("1.2", "1.2.1")).isTrue()
        assertThat(isOlderVersion("1.2.3.9", "1.2.3")).isFalse()
        // parseInt semantics: leading digits count, junk is 0.
        assertThat(isOlderVersion("1.2.3-beta", "1.2.3")).isFalse()
        assertThat(isOlderVersion("1.x.0", "1.0.0")).isFalse()
        assertThat(isOlderVersion("0.0.0", "")).isFalse()
    }
}
