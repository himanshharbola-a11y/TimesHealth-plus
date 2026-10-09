package timeshealth.app.ui

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import timeshealth.app.MainDispatcherRule
import timeshealth.app.core.model.ApiError
import timeshealth.app.core.model.RaceParticipant
import timeshealth.app.core.model.UpdateParticipantRequest
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.networkError
import timeshealth.app.ui.marathon.MarathonGateway
import timeshealth.app.ui.marathon.RaceDetailViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class ParticipantEditTest {

    @get:Rule val main = MainDispatcherRule()

    private class FakeMarathon : MarathonGateway {
        val updates = mutableListOf<UpdateParticipantRequest>()
        var error: Exception? = null
        override suspend fun events(refresh: Boolean) = error("unused")
        override val eventsChanges = emptyFlow<Unit>()
        override suspend fun raceDetail(eventId: String, refresh: Boolean) = error("not loaded in this test")
        override fun raceDetailChanges(eventId: String) = emptyFlow<Unit>()
        override suspend fun referral(refresh: Boolean) = error("unused")
        override suspend fun bibToken(eventId: String) = error("unused")
        override suspend fun offlinePass(eventId: String) = null
        override suspend fun claimUpgrade(eventId: String) = error("unused")
        override suspend fun updateParticipant(request: UpdateParticipantRequest) {
            error?.let { throw it }
            updates += request
        }
        override suspend fun signals() = timeshealth.app.core.domain.UserSignals()
        override fun nowMs() = 0L
    }

    private fun vm(gateway: MarathonGateway, edit: String? = null) =
        RaceDetailViewModel(SavedStateHandle(mapOf("eventId" to "delhi_half", "edit" to edit)), gateway)

    @Test
    fun `the profile link asks for the editor`() {
        assertThat(vm(FakeMarathon(), edit = "participant").editRequested).isTrue()
        assertThat(vm(FakeMarathon()).editRequested).isFalse()
    }

    @Test
    fun `saves what was set, clears what was emptied, and leaves untouched fields alone`() = runTest {
        val gateway = FakeMarathon()
        val vm = vm(gateway)
        var closed = 0

        vm.saveParticipant("L", " Ravi ", "9876543210", had = null) { closed++ }
        runCurrent()
        assertThat(gateway.updates.last()).isEqualTo(UpdateParticipantRequest("delhi_half", "L", "Ravi", "9876543210"))
        assertThat(closed).isEqualTo(1)

        // The contact was there and is now emptied: sent as "" so the server clears it.
        vm.saveParticipant("L", "", "", had = RaceParticipant("L", "Ravi", "+919876543210")) { closed++ }
        runCurrent()
        assertThat(gateway.updates.last()).isEqualTo(UpdateParticipantRequest("delhi_half", "L", "", ""))

        // Never set and still empty: not sent at all.
        vm.saveParticipant(null, "", "", had = null) { closed++ }
        runCurrent()
        assertThat(gateway.updates.last()).isEqualTo(UpdateParticipantRequest("delhi_half"))
    }

    @Test
    fun `a short number and server refusals keep the editor open with the reason`() = runTest {
        val gateway = FakeMarathon()
        val vm = vm(gateway)
        var closed = 0
        vm.saveParticipant("M", "Ravi", "98765", had = null) { closed++ }
        assertThat(vm.participantError.value).isEqualTo("Enter a 10-digit mobile number.")
        assertThat(gateway.updates).isEmpty()

        gateway.error = ApiRequestException(409, ApiError("RACE_STARTED", "Details can’t be changed once the race has started."))
        vm.saveParticipant("M", "Ravi", "9876543210", had = null) { closed++ }
        runCurrent()
        assertThat(vm.participantError.value).isEqualTo("Details can’t be changed once the race has started.")

        gateway.error = networkError()
        vm.saveParticipant("M", "Ravi", "9876543210", had = null) { closed++ }
        runCurrent()
        assertThat(vm.participantError.value).contains("connection")
        assertThat(closed).isEqualTo(0)
        assertThat(vm.participantSaving.value).isFalse()
    }
}
