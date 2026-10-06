package timeshealth.app.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import timeshealth.app.core.model.HomeFeedResponse
import timeshealth.app.core.model.KnownFeedComponent
import timeshealth.app.core.model.SessionResponse
import timeshealth.app.ui.session.AccountGateway
import timeshealth.app.ui.state.Loadable
import timeshealth.app.ui.state.UiState

/** HomeScreenKt's greeting item: the day line and the first name. */
@Immutable
data class HomeGreeting(
    /** "Good morning · Thursday", in IST (from the server). */
    val dayLine: String,
    val firstName: String,
)

/** Everything Home draws. */
@Immutable
data class HomeUi(
    val greeting: HomeGreeting,
    /** In the admin's order; types this build doesn't know are already dropped. */
    val components: List<KnownFeedComponent>,
    /** A yoga member: paid sessions play, nothing shows a lock. */
    val entitledToYoga: Boolean,
    /** Holds a yoga membership AND a race: the design's gold promo variant. */
    val holdsBoth: Boolean,
)

/**
 * Home: the server-built feed (GET /home), laid out by the admin CMS. This
 * screen knows no rules about what goes where; it renders the components in
 * order and turns taps into destinations ([targetFor]).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val home: HomeGateway,
    account: AccountGateway,
) : ViewModel() {

    private val feed = Loadable(
        scope = viewModelScope,
        fetch = { refresh -> home.home(refresh) },
        refetchWhen = home.homeChanges,
    )

    private val session = Loadable(
        scope = viewModelScope,
        fetch = { refresh -> account.session(refresh) },
        refetchWhen = account.sessionChanges,
    )

    val state: StateFlow<UiState<HomeUi>> = combine(feed.state, session.state, ::merge)
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    /** Server time for countdowns. */
    fun nowMs(): Long = home.nowMs()

    fun retry() {
        feed.retry()
        session.retry()
    }

    /** Pull to refresh. */
    fun refresh() {
        feed.refresh()
        session.refresh()
    }

    private fun merge(feed: UiState<HomeFeedResponse>, session: UiState<SessionResponse>): UiState<HomeUi> = when {
        feed is UiState.Failed -> feed
        // The feed renders without the session (it only tunes locks and the promo colour).
        feed is UiState.Ready -> {
            val persona = (session as? UiState.Ready)?.data?.persona
            UiState.Ready(
                HomeUi(
                    greeting = HomeGreeting(feed.data.greeting, firstNameOf(feed.data.userName)),
                    components = feed.data.knownComponents,
                    entitledToYoga = persona?.hasYoga == true,
                    holdsBoth = persona?.hasYoga == true && persona.hasMarathon,
                ),
                refreshing = feed.refreshing || (session as? UiState.Ready)?.refreshing == true,
                refreshError = feed.refreshError,
            )
        }
        else -> UiState.Loading
    }
}

/**
 * The design greets by first name only, and never by an empty string:
 * `name?.trim().split(/\s+/)[0] || 'there'` (RN Home).
 */
fun firstNameOf(name: String?): String =
    name?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: "there"
