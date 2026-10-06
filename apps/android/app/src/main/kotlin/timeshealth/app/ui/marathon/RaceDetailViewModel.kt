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
        val CLAIM_ERRORS = mapOf(
            "NO_FREE_UPGRADE" to "There’s no earned upgrade left to claim on this account.",
            "ALREADY_PREMIUM" to "This entry is already Premium VIP.",
            "REGISTRATION_CLOSED" to "This race has already started, so the upgrade can no longer be applied.",
            "NOT_REGISTERED" to "We couldn’t find your registration for this race.",
        )
    }
}
