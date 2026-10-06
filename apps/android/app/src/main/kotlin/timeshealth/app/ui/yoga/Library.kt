package timeshealth.app.ui.yoga

import androidx.compose.runtime.Immutable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timeshealth.app.core.model.MySessionsResponse
import timeshealth.app.core.model.SessionResponse
import timeshealth.app.core.model.YogaCatalogResponse
import timeshealth.app.core.model.YogaSession
import timeshealth.app.ui.session.AccountGateway
import timeshealth.app.ui.state.Loadable
import timeshealth.app.ui.state.UiState

/** The recorded-session library as the user sees it. */
@Immutable
data class LibraryUi(
    val catalog: YogaCatalogResponse,
    /** An active yoga member: every session plays. */
    val entitled: Boolean,
    val saved: Set<String> = emptySet(),
    val completed: Set<String> = emptySet(),
    /** Today's live batches, for the library's strip (0 until known). */
    val dailyBatches: Int = 0,
) {
    /** §6.3: a free user sees a paid session in full but plays only after subscribing. */
    fun locked(s: YogaSession): Boolean = !s.isFree && !entitled

    fun session(id: String): YogaSession? = catalog.sessions.firstOrNull { it.id == id }
}

/**
 * The catalogue, the membership and the saved/completed lists, loaded together
 * for the library, session detail and player ViewModels. Saved/completed and
 * the batch count are extras: their failure never blanks a screen.
 */
class Library(
    private val scope: CoroutineScope,
    private val gateway: YogaSessionsGateway,
    account: AccountGateway,
) {
    private val session = Loadable(scope, { r -> account.session(r) }, account.sessionChanges)
    private val catalog = Loadable(scope, { r -> gateway.catalog(r) }, gateway.catalogChanges)
    private val batches = MutableStateFlow(0)

    val state: StateFlow<UiState<LibraryUi>> =
        combine(session.state, catalog.state, gateway.mySessionsData, batches, ::merge)
            .stateIn(scope, SharingStarted.Eagerly, UiState.Loading)

    /** A one-off message (a save that didn't go through). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        loadExtras(refresh = false)
    }

    fun retry() {
        session.retry()
        catalog.retry()
        loadExtras(refresh = true)
    }

    fun refresh() {
        session.refresh()
        catalog.refresh()
        loadExtras(refresh = true)
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** Save or unsave: the state the user wants, shown at once (the repository is optimistic). */
    fun setSaved(id: String, saved: Boolean) {
        scope.launch {
            try {
                gateway.setSaved(id, saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Couldn’t update your saved sessions. Try again."
            }
        }
    }

    /** "Mark complete" is its own list: it never touches the attendance ledger (§7.1). */
    fun setCompleted(id: String, completed: Boolean) {
        scope.launch {
            try {
                gateway.setCompleted(id, completed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Couldn’t update your progress. Try again."
            }
        }
    }

    private fun loadExtras(refresh: Boolean) {
        scope.launch { quietly { gateway.mySessions(refresh) } }
        scope.launch { quietly { gateway.today(refresh) }?.let { batches.value = it.batches.size } }
    }

    private fun merge(
        session: UiState<SessionResponse>,
        catalog: UiState<YogaCatalogResponse>,
        mine: MySessionsResponse?,
        batches: Int,
    ): UiState<LibraryUi> = when {
        catalog is UiState.Failed -> catalog
        session is UiState.Failed -> session
        session is UiState.Ready && catalog is UiState.Ready -> UiState.Ready(
            LibraryUi(
                catalog = catalog.data,
                entitled = session.data.persona.hasYoga,
                saved = mine?.savedSessionIds.orEmpty().toSet(),
                completed = mine?.completedSessionIds.orEmpty().toSet(),
                dailyBatches = batches,
            ),
            refreshing = catalog.refreshing,
        )
        else -> UiState.Loading
    }

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
