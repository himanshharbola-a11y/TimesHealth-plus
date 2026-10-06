package timeshealth.app.ui.yoga

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timeshealth.app.core.model.YogaSession
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.ui.session.AccountGateway
import timeshealth.app.ui.state.UiError
import timeshealth.app.ui.state.UiState

/** One session as its detail page and player show it. */
@Immutable
data class SessionUi(val session: YogaSession, val locked: Boolean, val saved: Boolean, val completed: Boolean, val entitled: Boolean)

/** A stale or bad link: the catalogue loaded but has no such session. */
internal val SESSION_GONE = UiError("This session isn’t available any more.", UiError.Kind.NotFound)

internal fun UiState<LibraryUi>.sessionUi(id: String): UiState<SessionUi> = when (this) {
    UiState.Loading -> UiState.Loading
    is UiState.Failed -> this
    is UiState.Ready -> data.session(id)?.let { s ->
        UiState.Ready(SessionUi(s, data.locked(s), s.id in data.saved, s.id in data.completed, data.entitled), refreshing = refreshing)
    } ?: UiState.Failed(SESSION_GONE)
}

/** Session detail (design YogaSessionDetailView): everything about a practice; a locked one offers the subscription. */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    gateway: YogaSessionsGateway,
    account: AccountGateway,
) : ViewModel() {
    val id: String = checkNotNull(handle.get<String>("id")) { "Route.SessionDetail needs an id" }
    private val library = Library(viewModelScope, gateway, account)

    val state: StateFlow<UiState<SessionUi>> = library.state.map { it.sessionUi(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)
    val message: StateFlow<String?> = library.message

    fun retry() = library.retry()
    fun consumeMessage() = library.consumeMessage()
    fun setSaved(saved: Boolean) = library.setSaved(id, saved)
    fun setCompleted(completed: Boolean) = library.setCompleted(id, completed)
}

/** The library (design YogaCategoryExplorerView): tracks, then the sessions in the chosen one. */
@HiltViewModel
class YogaExplorerViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    gateway: YogaSessionsGateway,
    account: AccountGateway,
) : ViewModel() {
    private val library = Library(viewModelScope, gateway, account)
    private val selected = handle.getStateFlow<String?>(KEY_SELECTED, handle.get<String>("categoryId"))

    val state: StateFlow<UiState<LibraryUi>> = library.state
    val message: StateFlow<String?> = library.message

    /** The chosen track: the one asked for, else the first (a bad id falls back too). */
    val activeCategory: StateFlow<String?> = combine(library.state, selected) { s, sel ->
        val categories = (s as? UiState.Ready)?.data?.catalog?.categories.orEmpty()
        categories.firstOrNull { it.id == sel }?.id ?: categories.firstOrNull()?.id
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun select(categoryId: String) {
        handle[KEY_SELECTED] = categoryId
    }

    fun retry() = library.retry()
    fun refresh() = library.refresh()
    fun consumeMessage() = library.consumeMessage()
    fun setSaved(id: String, saved: Boolean) = library.setSaved(id, saved)

    private companion object {
        const val KEY_SELECTED = "selectedCategory"
    }
}

/** Where the player's video is. */
sealed interface StreamState {
    data object Loading : StreamState
    data class Ready(val stream: SessionStream) : StreamState

    /** The session has no video yet (404, or only a provider not plugged in). */
    data object Unavailable : StreamState

    /** Couldn't load it; [retry][VideoPlayerViewModel.retryStream] asks again. */
    data object Failed : StreamState
}

/**
 * The in-app player for recordings (§6.3 "plays in-app"). Never a dead tap: a
 * known non-member goes to the paywall without asking for the video, and a 403
 * (the membership lapsed after the catalogue loaded) refreshes the membership
 * and goes there too.
 */
@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val gateway: YogaSessionsGateway,
    account: AccountGateway,
) : ViewModel() {
    val id: String = checkNotNull(handle.get<String>("id")) { "Route.VideoPlayer needs an id" }
    private val library = Library(viewModelScope, gateway, account)

    val state: StateFlow<UiState<SessionUi>> = library.state.map { it.sessionUi(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    private val _stream = MutableStateFlow<StreamState>(StreamState.Loading)
    val stream: StateFlow<StreamState> = _stream.asStateFlow()

    /** True once the user should be on the paywall instead. */
    private val _toPaywall = MutableStateFlow(false)
    val toPaywall: StateFlow<Boolean> = _toPaywall.asStateFlow()

    private var requested = false

    init {
        viewModelScope.launch {
            state.collect { s ->
                val ui = (s as? UiState.Ready)?.data ?: return@collect
                when {
                    ui.locked -> _toPaywall.value = true
                    !requested -> {
                        requested = true
                        loadStream()
                    }
                }
            }
        }
    }

    fun retry() = library.retry()

    /** Ask for a fresh stream (every answer is newly signed; the player keeps the one it has until then). */
    fun retryStream() {
        if (_stream.value == StreamState.Loading) return
        loadStream()
    }

    private fun loadStream() {
        _stream.value = StreamState.Loading
        viewModelScope.launch {
            _stream.value = try {
                gateway.stream(id)?.let { StreamState.Ready(it) } ?: StreamState.Unavailable
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiRequestException) {
                when (e.status) {
                    403 -> {
                        gateway.entitlementsChanged()
                        _toPaywall.value = true
                        StreamState.Loading
                    }
                    404 -> StreamState.Unavailable
                    else -> StreamState.Failed
                }
            } catch (e: Exception) {
                StreamState.Failed
            }
        }
    }

    fun paywallShown() {
        _toPaywall.value = false
    }

    /**
     * "Leave · Mark complete": SETS completed (never toggles, so a list that hasn't loaded
     * can't un-complete a session), then leaves. Watching never counts as attendance (§7.1).
     */
    fun leave(done: () -> Unit) {
        val ui = (state.value as? UiState.Ready)?.data
        if (ui == null || ui.completed) return done()
        viewModelScope.launch {
            // Leaving waits a moment for the mark to land, never long on a slow network
            // (then it is dropped with the screen, and the session stays not completed).
            withTimeoutOrNull(LEAVE_WAIT_MS) {
                try {
                    gateway.setCompleted(id, true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Unit
                }
            }
            done()
        }
    }

    private companion object {
        const val LEAVE_WAIT_MS = 2_500L
    }
}
