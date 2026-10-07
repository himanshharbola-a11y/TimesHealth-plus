package timeshealth.app.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import timeshealth.app.MainDispatcherRule
import timeshealth.app.core.model.ApiError
import timeshealth.app.core.model.DietLeadRequest
import timeshealth.app.core.model.DietLeadResponse
import timeshealth.app.core.model.SessionResponse
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.networkError
import timeshealth.app.sessionResponse
import timeshealth.app.ui.diet.DietGateway
import timeshealth.app.ui.diet.DietViewModel
import timeshealth.app.ui.reel.reelStream
import timeshealth.app.ui.yoga.RepositoryYogaSessionsGateway

@OptIn(ExperimentalCoroutinesApi::class)
class DietReelTest {

    @get:Rule val main = MainDispatcherRule()

    private class FakeDiet(var answer: Any = DietLeadResponse("lead_1", "We’ll call you tomorrow.")) : DietGateway {
        val sent = mutableListOf<DietLeadRequest>()
        var session: SessionResponse? = sessionResponse(name = "Priya Sharma").let { it.copy(profile = it.profile.copy(phone = "+919876543210")) }
        override suspend fun session() = session
        override suspend fun submit(lead: DietLeadRequest): DietLeadResponse {
            sent += lead
            return (answer as? Throwable)?.let { throw it } ?: answer as DietLeadResponse
        }
    }

    @Test
    fun `the form prefills from the profile without overwriting what was typed`() = runTest {
        val gateway = FakeDiet()
        val vm = DietViewModel(gateway)
        vm.setName("Asha")
        vm.opened()
        runCurrent()
        assertThat(vm.form.value.name).isEqualTo("Asha")
        assertThat(vm.form.value.phone).isEqualTo("9876543210")
    }

    @Test
    fun `missing details are pointed out before anything is sent`() = runTest {
        val gateway = FakeDiet().apply { session = null }
        val vm = DietViewModel(gateway)
        vm.opened()
        runCurrent()
        vm.setPhone("123")
        vm.submit {}
        assertThat(vm.form.value.nameError).isNotNull()
        assertThat(vm.form.value.phoneError).contains("10-digit")
        assertThat(gateway.sent).isEmpty()
    }

    @Test
    fun `a sent lead confirms, a repeat says you're already on the list, a failure keeps the form`() = runTest {
        val gateway = FakeDiet()
        val vm = DietViewModel(gateway)
        vm.opened()
        runCurrent()
        vm.setConcern("PCOS / PCOD")
        var closed = 0
        vm.submit { closed++ }
        runCurrent()
        assertThat(gateway.sent.single()).isEqualTo(DietLeadRequest("Priya Sharma", "+919876543210", "PCOS / PCOD", null, "Morning"))
        assertThat(vm.done.value?.title).isEqualTo("You’re on the list!")
        assertThat(vm.done.value?.message).isEqualTo("We’ll call you tomorrow.")
        assertThat(closed).isEqualTo(1)

        vm.dismissDone()
        gateway.answer = ApiRequestException(429, ApiError("RATE_LIMITED", "We already have your request — expect a call soon."))
        vm.submit { closed++ }
        runCurrent()
        assertThat(vm.done.value?.title).isEqualTo("You’re already on the list")
        assertThat(closed).isEqualTo(2)

        vm.dismissDone()
        gateway.answer = networkError()
        vm.submit { closed++ }
        runCurrent()
        assertThat(vm.done.value).isNull()
        assertThat(vm.form.value.formError).contains("connection")
        assertThat(vm.form.value.sending).isFalse()
        assertThat(closed).isEqualTo(2)
    }

    @Test
    fun `reels play https only, and placeholder media only as a debug sample`() {
        assertThat(reelStream("https://cdn.test/r.m3u8", debug = false)?.url).isEqualTo("https://cdn.test/r.m3u8")
        assertThat(reelStream("http://cdn.test/r.m3u8", debug = true)).isNull()
        assertThat(reelStream("javascript:alert(1)", debug = true)).isNull()
        assertThat(reelStream("", debug = true)).isNull()
        assertThat(reelStream("https://media.timeshealthplus.invalid/r/1.m3u8", debug = true)?.url).isEqualTo(RepositoryYogaSessionsGateway.DEV_SAMPLE_STREAM)
        assertThat(reelStream("https://media.timeshealthplus.invalid/r/1.m3u8", debug = false)).isNull()
    }
}
