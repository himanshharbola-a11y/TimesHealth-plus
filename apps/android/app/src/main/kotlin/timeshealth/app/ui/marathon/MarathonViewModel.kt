package timeshealth.app.ui.marathon

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timeshealth.app.core.model.MarathonEvent
import timeshealth.app.core.model.MarathonListResponse
import timeshealth.app.core.model.RaceLifecycleStatus
import timeshealth.app.core.model.RaceTier
import timeshealth.app.core.model.ReferralState
import timeshealth.app.ui.state.Loadable
import timeshealth.app.ui.state.UiState

/** The Races tab's sections (PRD §8.2/§8.3), in page order. */
@Immutable
data class RacesUi(
    /** The user's nearest race still ahead: the big registered box. */
    val myNext: MarathonEvent?,
    /** Their other races ahead, nearest first. */
    val myLater: List<MarathonEvent>,
    /** Races they've run (result state). */
    val past: List<MarathonEvent>,
    /** With no race ahead, the nearest open edition, sold large at the top (§8.5). */
    val hero: MarathonEvent?,
    /** Every other edition they haven't entered. */
    val rest: List<MarathonEvent>,
    /** Entered at least one race (changes the section title). */
    val hasEntries: Boolean,
    /** Refer & Win, for a registrant whose race is still ahead. */
    val referral: ReferralState? = null,
    /** The race a free Refer & Win upgrade would apply to: an upcoming CLASSIC entry. */
    val claimTargetId: String? = null,
)

/**
 * Splits the server's list (already ordered nearest/date first) into the tab's
 * sections: the user's races first (ahead, then run), and a finished race is
 * never presented as an "upcoming edition".
 */
fun racesUi(events: List<MarathonEvent>): RacesUi {
    val mine = events.filter { it.registration != null }
    val upcoming = mine.filter { it.registration?.status != RaceLifecycleStatus.COMPLETED }
    val past = mine.filter { it.registration?.status == RaceLifecycleStatus.COMPLETED }
    val others = events.filter { it.registration == null }
    val myNext = upcoming.firstOrNull()
    val hero = if (myNext == null) others.firstOrNull() else null
    return RacesUi(
        myNext = myNext,
        myLater = upcoming.drop(1),
        past = past,
        hero = hero,
        rest = if (hero != null) others.drop(1) else others,
        hasEntries = mine.isNotEmpty(),
        claimTargetId = upcoming.firstOrNull {
            it.registration?.status == RaceLifecycleStatus.UPCOMING && it.registration?.tier == RaceTier.CLASSIC
        }?.id,
    )
}

/** Cheapest Classic entry across the distances: the "From ₹" price (0 when none is priced). */
fun fromPaise(event: MarathonEvent): Long =
    event.distanceOptions.mapNotNull { it.pricePaise[RaceTier.CLASSIC] }.filter { it > 0 }.minOrNull() ?: 0L

@HiltViewModel
class MarathonViewModel @Inject constructor(private val gateway: MarathonGateway) : ViewModel() {

    private val events = Loadable(
        scope = viewModelScope,
        fetch = { refresh -> gateway.events(refresh) },
        refetchWhen = gateway.eventsChanges,
    )

    private val referral = MutableStateFlow<ReferralState?>(null)

    val state: StateFlow<UiState<RacesUi>> = combine(events.state, referral) { list, ref -> list.toUi(ref) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    fun retry() = events.retry()

    fun refresh() = events.refresh()

    private var referralRequested = false

    private fun UiState<MarathonListResponse>.toUi(ref: ReferralState?): UiState<RacesUi> = when (this) {
        UiState.Loading -> UiState.Loading
        is UiState.Failed -> this
        is UiState.Ready -> {
            val ui = racesUi(data.events)
            // Refer & Win is only for a registrant whose race is still ahead.
            if (ui.myNext?.registration?.status == RaceLifecycleStatus.UPCOMING && !referralRequested) loadReferral()
            UiState.Ready(
                ui.copy(referral = ref.takeIf { ui.myNext?.registration?.status == RaceLifecycleStatus.UPCOMING }),
                refreshing = refreshing,
                refreshError = refreshError,
            )
        }
    }

    private fun loadReferral() {
        referralRequested = true
        viewModelScope.launch {
            referral.value = try {
                gateway.referral()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Refer & Win is a bonus card: its absence never blocks the races.
                null
            }
        }
    }
}
