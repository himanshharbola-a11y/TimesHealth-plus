package timeshealth.app.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timeshealth.app.core.domain.greetingFor
import timeshealth.app.core.model.SessionResponse
import timeshealth.app.ui.session.AccountGateway
import timeshealth.app.ui.state.Loadable
import timeshealth.app.ui.state.UiState

/** HomeScreenKt's greeting item: the day line and the first name. */
@Immutable
data class HomeGreeting(
    /** "Good morning · Thursday", in IST (core:domain greetingFor). */
    val dayLine: String,
    val firstName: String,
)

/**
 * PLACEHOLDER Home: proves the signed-in path end to end (Gate → session →
 * tabs) by greeting the user from GET /session. The real Home (hero, rails,
 * promo strip; GET /home) replaces this; keep the greeting's derivation.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    account: AccountGateway,
) : ViewModel() {

    private val session = Loadable(
        scope = viewModelScope,
        fetch = { refresh -> account.session(refresh) },
        refetchWhen = account.sessionChanges,
    )

    val state: StateFlow<UiState<HomeGreeting>> = session.state
        .map { it.toGreeting() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    fun retry() = session.retry()

    private fun UiState<SessionResponse>.toGreeting(): UiState<HomeGreeting> = when (this) {
        UiState.Loading -> UiState.Loading
        is UiState.Failed -> this
        is UiState.Ready -> UiState.Ready(
            HomeGreeting(dayLine = greetingFor(Instant.now()), firstName = firstNameOf(data.profile.name)),
            refreshing = refreshing,
            refreshError = refreshError,
        )
    }
}

/**
 * The design greets by first name only, and never by an empty string:
 * `name?.trim().split(/\s+/)[0] || 'there'` (RN Home).
 */
fun firstNameOf(name: String?): String =
    name?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: "there"
