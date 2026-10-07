package timeshealth.app.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import timeshealth.app.MainDispatcherRule
import timeshealth.app.core.model.HealthGoal
import timeshealth.app.core.model.OnboardingStepRequest
import timeshealth.app.core.model.UserProfile
import timeshealth.app.networkError
import timeshealth.app.ui.onboarding.OnboardingGateway
import timeshealth.app.ui.onboarding.OnboardingViewModel
import timeshealth.app.ui.onboarding.RETRY_DELAY_MS

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingTest {

    @get:Rule val main = MainDispatcherRule()

    private class FakeOnboarding : OnboardingGateway {
        val saved = mutableListOf<OnboardingStepRequest>()
        var failures = 0
        var skipped = 0
        override suspend fun profile(): UserProfile? = null
        override suspend fun saveStep(body: OnboardingStepRequest) {
            if (failures > 0) {
                failures--
                throw networkError()
            }
            saved += body
        }
        override suspend fun skip() {
            skipped++
        }
    }

    @Test
    fun `steps save in the background and move on, a bad contact stays put`() = runTest {
        val gateway = FakeOnboarding()
        val vm = OnboardingViewModel(gateway)
        vm.setName("Asha")
        vm.advance {}
        runCurrent()
        assertThat(vm.ui.value.step).isEqualTo(2)
        assertThat(gateway.saved.single()).isEqualTo(OnboardingStepRequest(step = 1, name = "Asha"))

        vm.setPhone("123")
        vm.advance {}
        assertThat(vm.ui.value.step).isEqualTo(2)
        assertThat(vm.ui.value.phoneError).isNotNull()

        vm.setPhone("98765 43210")
        vm.advance {}
        runCurrent()
        assertThat(vm.ui.value.step).isEqualTo(3)
        assertThat(gateway.saved.last().phone).isEqualTo("+919876543210")

        vm.setGoal("CONSISTENCY")
        vm.advance {}
        runCurrent()
        assertThat(gateway.saved.last().healthGoal).isEqualTo(HealthGoal.CONSISTENCY)
        var finished = 0
        vm.advance { finished++ }
        runCurrent()
        assertThat(finished).isEqualTo(1)
        assertThat(gateway.saved.last().step).isEqualTo(4)
    }

    @Test
    fun `a dropped save is retried once without holding the user`() = runTest {
        val gateway = FakeOnboarding().apply { failures = 1 }
        val vm = OnboardingViewModel(gateway)
        vm.setName("Asha")
        vm.advance {}
        runCurrent()
        assertThat(vm.ui.value.step).isEqualTo(2)
        assertThat(vm.ui.value.notice).contains("trying again")
        advanceTimeBy(RETRY_DELAY_MS + 1)
        assertThat(gateway.saved).hasSize(1)
        assertThat(vm.ui.value.notice).isNull()
    }

    @Test
    fun `skipping the last step finishes onboarding`() = runTest {
        val gateway = FakeOnboarding()
        val vm = OnboardingViewModel(gateway)
        repeat(3) { vm.skip {} }
        var finished = 0
        vm.skip { finished++ }
        runCurrent()
        assertThat(gateway.skipped).isEqualTo(1)
        assertThat(finished).isEqualTo(1)
    }
}
