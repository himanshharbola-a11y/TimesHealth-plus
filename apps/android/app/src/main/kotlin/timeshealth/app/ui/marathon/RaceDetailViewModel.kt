package timeshealth.app.ui.marathon

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timeshealth.app.core.model.RaceDetailResponse
import timeshealth.app.core.model.RaceParticipant
import timeshealth.app.core.model.UpdateParticipantRequest
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.ui.state.Loadable
import timeshealth.app.ui.state.UiState

/** A race page (PRD §8.3): register, or everything about an entry. */
@HiltViewModel
class RaceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val gateway: MarathonGateway,
) : ViewModel() {

    val eventId: String = checkNotNull(savedStateHandle.get<String>("eventId"))

    /** The distance chip carried over from the Marathon tab, if any. */
    val initialDistance: String? = savedStateHandle.get<String>("distance")

    /** Opened from the profile's "Race participant details": land in the editor. */
    val editRequested: Boolean = savedStateHandle.get<String>("edit") == "participant"

    private val _participantSaving = MutableStateFlow(false)
    val participantSaving: StateFlow<Boolean> = _participantSaving.asStateFlow()

    private val _participantError = MutableStateFlow<String?>(null)
    val participantError: StateFlow<String?> = _participantError.asStateFlow()

    private val detail = Loadable(
        scope = viewModelScope,
        fetch = { refresh -> gateway.raceDetail(eventId, refresh) },
        refetchWhen = gateway.raceDetailChanges(eventId),
    )

    val state: StateFlow<UiState<RaceDetailResponse>> = detail.state

    /** A one-off message for a dialog (the free upgrade claim). */
    private val _message = MutableStateFlow<Pair<String, String>?>(null)
    val message: StateFlow<Pair<String, String>?> = _message.asStateFlow()

    private val _claiming = MutableStateFlow(false)
    val claiming: StateFlow<Boolean> = _claiming.asStateFlow()

    fun retry() = detail.retry()

    fun refresh() = detail.refresh()

    fun consumeMessage() {
        _message.value = null
    }

    fun clearParticipantError() {
        _participantError.value = null
    }

    /**
     * Saves the T-shirt size and emergency contact (§8.3). A field is sent when it has a value,
     * or to clear one that had a value ([had]); the server answers bad numbers and started races
     * in its own words.
     */
    fun saveParticipant(size: String?, contactName: String, contactPhone: String, had: RaceParticipant?, done: () -> Unit) {
        if (_participantSaving.value) return
        val phone = contactPhone.trim()
        if (phone.isNotEmpty() && phone.count(Char::isDigit) < 10) {
            _participantError.value = "Enter a 10-digit mobile number."
            return
        }
        val request = UpdateParticipantRequest(
            eventId = eventId,
            tshirtSize = size,
            emergencyContactName = contactName.trim().takeIf { it.isNotEmpty() || had?.emergencyContactName != null },
            emergencyContactPhone = phone.takeIf { it.isNotEmpty() || had?.emergencyContactPhone != null },
        )
        _participantError.value = null
        _participantSaving.value = true
        viewModelScope.launch {
            try {
                gateway.updateParticipant(request)
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiRequestException) {
                _participantError.value = if (!e.isNetworkFailure && e.error.message.isNotBlank()) e.error.message else SAVE_FAILED
            } catch (e: Exception) {
                _participantError.value = SAVE_FAILED
            } finally {
                _participantSaving.value = false
            }
        }
    }

    /** Claims the Refer & Win free Premium upgrade. The server re-checks everything. */
    fun claimUpgrade(eventName: String) {
        if (_claiming.value) return
        _claiming.value = true
        viewModelScope.launch {
            _message.value = try {
                gateway.claimUpgrade(eventId)
                detail.refresh()
                "You’re Premium VIP" to "Your $eventName entry is now Premium VIP. Enjoy race morning!"
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiRequestException) {
                "Couldn’t claim the upgrade" to (CLAIM_ERRORS[e.code] ?: if (e.isNetworkFailure) e.message.orEmpty() else GENERIC)
            } catch (e: Exception) {
                "Couldn’t claim the upgrade" to GENERIC
            } finally {
                _claiming.value = false
            }
        }
    }

    companion object {
        private const val GENERIC = "Something went wrong on our side. Please try again."
        private const val SAVE_FAILED = "Couldn’t save. Check your connection and try again."
        val CLAIM_ERRORS = mapOf(
            "NO_FREE_UPGRADE" to "There’s no earned upgrade left to claim on this account.",
            "ALREADY_PREMIUM" to "This entry is already Premium VIP.",
            "REGISTRATION_CLOSED" to "This race has already started, so the upgrade can no longer be applied.",
            "NOT_REGISTERED" to "We couldn’t find your registration for this race.",
        )
    }
}
