package timeshealth.app.ui.state

import androidx.compose.runtime.Immutable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import timeshealth.app.core.data.cache.CachedResource
import timeshealth.app.core.network.ApiRequestException

/*
 * ─── How a data-driven screen is built (conventions for every screen) ────────
 *
 * 1. The ViewModel (`@HiltViewModel`) owns the data and exposes ONE
 *    `StateFlow<…UiState>` for the screen. For a screen that is "one response,
 *    rendered", that is a [UiState]<T>, usually from a [Loadable]:
 *
 *        @HiltViewModel
 *        class RaceResultsViewModel @Inject constructor(
 *            savedStateHandle: SavedStateHandle,
 *            marathon: MarathonGateway,            // an app-side seam, see below
 *        ) : ViewModel() {
 *            private val route = savedStateHandle.toRoute<Route.RaceResults>()
 *            private val results = Loadable(viewModelScope, { refresh -> marathon.race(route.eventId, refresh) })
 *            val state: StateFlow<UiState<RaceDetailResponse>> = results.state
 *            fun retry() = results.retry()
 *        }
 *
 *    For a `CachedResource` from core:data, `resource.loadable(viewModelScope)`
 *    also refetches whenever the cache invalidates it (after a purchase, on
 *    return from >60 s in the background).
 *
 * 2. The screen composable is split in two:
 *    - `RaceResultsRoute(viewModel = hiltViewModel(), onBack = …)` collects with
 *      `collectAsStateWithLifecycle()` and passes plain values + lambdas down;
 *    - `RaceResultsScreen(state, onRetry, onBack)` is stateless, previewable
 *      and testable. It renders through [UiStateContent] (LoadingState /
 *      ErrorState / content), so no screen ever spins forever on a failure or
 *      shows an error with no way out.
 *
 * 3. Navigation stays out of ViewModels and screens: a screen takes callbacks
 *    (`onOpenBib: (eventId) -> Unit`), and AppNavHost wires them to
 *    `navController.navigate(Route.Bib(eventId))`. Never pass a NavController
 *    down.
 *
 * 4. ViewModels depend on small app-side interfaces ("gateways") bound in
 *    wiring/UiWiring.kt to the core:data repositories, not on the repositories
 *    themselves: those are final classes with internal constructors, so a
 *    ViewModel that takes one can't be unit-tested with a fake. See
 *    ui/session/SessionGateway.kt for the pattern (interface + Hilt adapter +
 *    fake in the test).
 *
 * 5. Error text comes from [UiError] ([toUiError]): one wording for network /
 *    timeout / 404 / 429 / 5xx across the app (RN QueryState.tsx).
 */

/** What a screen that shows one loaded thing is showing. */
@Immutable
sealed interface UiState<out T> {

    /** Nothing to show yet: first load, or a retry after an error. */
    data object Loading : UiState<Nothing>

    /**
     * The data. [refreshing] is true only during a USER-started refresh (pull
     * to refresh), never for a background refetch, so no spinner flickers in
     * when the cache refreshes behind the screen. [refreshError] is set when a
     * refresh failed: the old data stays on screen (never replace what the
     * user is reading with an error page); show it as a snackbar if at all.
     */
    data class Ready<out T>(
        val data: T,
        val refreshing: Boolean = false,
        val refreshError: UiError? = null,
    ) : UiState<T>

    /** The load failed and there is nothing to show: render ErrorState with a retry. */
    data class Failed(val error: UiError) : UiState<Nothing>
}

/** The data if [this] is [UiState.Ready], else null. */
val <T> UiState<T>.dataOrNull: T? get() = (this as? UiState.Ready<T>)?.data

/**
 * A user-facing error: the words to show and what kind of failure it was.
 * Screens branch on [kind] (e.g. offer the saved race pass on [Kind.Network]),
 * never on exception types.
 */
@Immutable
data class UiError(val message: String, val kind: Kind, val status: Int? = null, val code: String? = null) {
    enum class Kind { Network, Timeout, NotFound, RateLimited, Server, Unknown }

    companion object {
        const val GENERIC = "We couldn’t load this. Check your connection and try again."
        const val NOT_FOUND = "This isn’t available any more."
        const val RATE_LIMITED = "Too many requests just now. Wait a moment and try again."
        const val SERVER = "Something went wrong on our side. Please try again."
    }
}

/**
 * The words for a failure, as apps/mobile/src/components/QueryState.tsx
 * `messageFor`: offline / timeout keep the network layer's own message (it is
 * already user-facing: "No connection. Check your network and try again."),
 * 404 / 429 / 5xx get fixed copy, anything else the generic line.
 */
fun Throwable.toUiError(): UiError {
    val e = this as? ApiRequestException ?: return UiError(UiError.GENERIC, UiError.Kind.Unknown)
    return when {
        e.status == 0 -> UiError(
            message = e.message ?: UiError.GENERIC,
            kind = if (e.code == "TIMEOUT") UiError.Kind.Timeout else UiError.Kind.Network,
            status = 0,
            code = e.code,
        )
        e.status == 404 -> UiError(UiError.NOT_FOUND, UiError.Kind.NotFound, e.status, e.code)
        e.status == 429 -> UiError(UiError.RATE_LIMITED, UiError.Kind.RateLimited, e.status, e.code)
        e.status >= 500 -> UiError(UiError.SERVER, UiError.Kind.Server, e.status, e.code)
        else -> UiError(UiError.GENERIC, UiError.Kind.Unknown, e.status, e.code)
    }
}

/**
 * One loaded resource as a [StateFlow] of [UiState]: the ViewModel half of the
 * RN `useQuery` + QueryState pattern.
 *
 * - Starts loading at once ([UiState.Loading] → [UiState.Ready] / [UiState.Failed]).
 * - [refetchWhen] emitting (a cache invalidation) refetches silently, keeping
 *   the current data on screen.
 * - [retry] after a failure goes back to Loading and fetches fresh.
 * - [refresh] (pull to refresh) keeps the data with `refreshing = true`.
 * - A failed refetch/refresh keeps the data and sets `refreshError`.
 *
 * Only one fetch runs at a time; a new one cancels the last.
 *
 * @param fetch loads the value; `refresh = true` must bypass any cache.
 */
class Loadable<T : Any>(
    private val scope: CoroutineScope,
    private val fetch: suspend (refresh: Boolean) -> T,
    refetchWhen: Flow<Unit> = emptyFlow(),
) {
    private val _state = MutableStateFlow<UiState<T>>(UiState.Loading)
    val state: StateFlow<UiState<T>> = _state.asStateFlow()

    private var job: Job? = null

    init {
        load(Mode.Initial)
        scope.launch { refetchWhen.collect { load(Mode.Background) } }
    }

    /** After a failure: back to Loading, then a fresh fetch. */
    fun retry() = load(Mode.Retry)

    /** Pull to refresh: keeps the data on screen while a fresh fetch runs. */
    fun refresh() = load(Mode.Refresh)

    private enum class Mode { Initial, Background, Retry, Refresh }

    private fun load(mode: Mode) {
        job?.cancel()
        // The visible change happens at once (a tap on Try again shows at once),
        // the fetch runs after.
        val before = _state.value as? UiState.Ready<T>
        when {
            // Nothing on screen yet (first load, or a retry after Failed).
            before == null -> _state.value = UiState.Loading
            // The user asked: show it's happening, over the data.
            mode == Mode.Refresh || mode == Mode.Retry ->
                _state.value = before.copy(refreshing = true, refreshError = null)
            // A background refetch stays silent.
            else -> Unit
        }
        job = scope.launch {
            val next: UiState<T> = try {
                UiState.Ready(fetch(mode == Mode.Retry || mode == Mode.Refresh))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                before?.copy(refreshing = false, refreshError = e.toUiError()) ?: UiState.Failed(e.toUiError())
            }
            _state.value = next
        }
    }
}

/**
 * [Loadable] for a core:data [CachedResource]: served from the cache while
 * fresh, refetched whenever the cache invalidates it.
 */
fun <T : Any> CachedResource<T>.loadable(scope: CoroutineScope): Loadable<T> =
    Loadable(scope, fetch = { refresh -> get(refresh) }, refetchWhen = changes)
