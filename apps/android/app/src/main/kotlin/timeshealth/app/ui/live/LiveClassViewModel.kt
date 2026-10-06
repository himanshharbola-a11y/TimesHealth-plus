package timeshealth.app.ui.live

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timeshealth.app.core.domain.WAIT_ROOM_MS
import timeshealth.app.core.domain.parseIsoInstant
import timeshealth.app.core.integrations.video.PlayableStream
import timeshealth.app.core.integrations.video.UnsupportedVideoProviderException
import timeshealth.app.core.model.LiveClassCard
import timeshealth.app.core.model.LiveClassState
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.ui.state.UiError
import timeshealth.app.ui.state.toUiError

/** Where a live class is for this viewer. */
@Immutable
sealed interface LivePhase {
    data object Loading : LivePhase

    /** Before the wait room opens. The screen offers "Remind me"; it joins by itself at [opensAtMs]. */
    data class NotOpen(val opensAtMs: Long, val startsAtMs: Long) : LivePhase

    /** Joined; counting down to the start, when playback begins for everyone at once. */
    data class WaitRoom(val stream: PlayableStream, val startsAtMs: Long, val endsAtMs: Long) : LivePhase

    /** Playing, at (now − start) into the stream: everyone is at the same point. */
    data class Playing(val stream: PlayableStream, val startsAtMs: Long, val endsAtMs: Long) : LivePhase

    /** A members-only class and the viewer isn't a member: the paywall. */
    data object Locked : LivePhase

    data object Ended : LivePhase

    data object Cancelled : LivePhase

    data class Failed(val error: UiError) : LivePhase
}

@Immutable
data class LiveClassUi(
    val card: LiveClassCard? = null,
    val phase: LivePhase = LivePhase.Loading,
    val reminderSet: Boolean = false,
)

/**
 * A live class ("premiere"): a pre-recorded class streamed at a set time.
 *
 * The phases advance on their own from the server clock (no polling): before
 * the wait room → join automatically when it opens → count down → play at
 * the shared position → ended. Joining is the server's join call, which checks
 * membership and records attendance for members; the stream comes only from it.
 */
@HiltViewModel
class LiveClassViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val gateway: LiveClassGateway,
) : ViewModel() {

    val id: String = checkNotNull(savedStateHandle.get<String>("id")) { "Route.LiveClass needs an id" }

    private val _state = MutableStateFlow(LiveClassUi())

    val state: StateFlow<LiveClassUi> = combine(_state, gateway.reminded) { s, reminded -> s.copy(reminderSet = id in reminded) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LiveClassUi())

    private var flow: Job? = null

    init {
        start()
    }

    fun retry() = start()

    /** The server clock, for the countdowns. */
    fun nowMs(): Long = gateway.nowMs()

    /** "Remind me" / "Reminder set": a local notification shortly before the start. */
    fun toggleReminder() {
        val card = _state.value.card ?: return
        val start = parseIsoInstant(card.startsAt)?.toEpochMilli() ?: return
        if (id in gateway.reminded.value) gateway.cancelReminder(id) else gateway.remind(card, start)
    }

    private fun start() {
        flow?.cancel()
        flow = viewModelScope.launch { run() }
    }

    private fun phase(next: LivePhase) = _state.update { it.copy(phase = next) }

    private suspend fun run() {
        phase(LivePhase.Loading)
        // The listing is for the title, times and state; joining works without it.
        val card = try {
            gateway.card(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (card != null) _state.update { it.copy(card = card) }
        if (card?.state == LiveClassState.CANCELLED) return phase(LivePhase.Cancelled)

        // Before the wait room: show when it opens, then join by itself (joining earlier is a 409).
        val start = card?.let { parseIsoInstant(it.startsAt)?.toEpochMilli() }
        if (start != null && gateway.nowMs() < start - WAIT_ROOM_MS) {
            phase(LivePhase.NotOpen(start - WAIT_ROOM_MS, start))
            delay(start - WAIT_ROOM_MS - gateway.nowMs())
        }
        join(start)
    }

    private suspend fun join(knownStart: Long?) {
        val joined = try {
            gateway.join(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiRequestException) {
            return phase(
                when (e.code) {
                    "NOT_ENTITLED" -> LivePhase.Locked
                    "CLASS_ENDED" -> LivePhase.Ended
                    "CLASS_CANCELLED" -> LivePhase.Cancelled
                    "CLASS_NOT_OPEN" -> knownStart?.let { LivePhase.NotOpen(it - WAIT_ROOM_MS, it) } ?: LivePhase.Failed(e.toUiError())
                    else -> LivePhase.Failed(e.toUiError())
                },
            )
        } catch (e: Exception) {
            return phase(LivePhase.Failed(e.toUiError()))
        }

        val start = parseIsoInstant(joined.startsAt)?.toEpochMilli() ?: return phase(LivePhase.Failed(UiError(UiError.GENERIC, UiError.Kind.Unknown)))
        val end = parseIsoInstant(joined.endsAt)?.toEpochMilli() ?: start
        val stream = try {
            gateway.resolve(joined, start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnsupportedVideoProviderException) {
            // A video source this build has no player for (e.g. Slike before its SDK is plugged in).
            return phase(LivePhase.Failed(UiError(UNSUPPORTED_VIDEO, UiError.Kind.Unknown)))
        } catch (e: Exception) {
            return phase(LivePhase.Failed(e.toUiError()))
        }

        if (gateway.nowMs() < start) {
            phase(LivePhase.WaitRoom(stream, start, end))
            delay(start - gateway.nowMs())
        }
        if (gateway.nowMs() < end) {
            phase(LivePhase.Playing(stream, start, end))
            delay(end - gateway.nowMs())
        }
        phase(LivePhase.Ended)
    }

    companion object {
        const val UNSUPPORTED_VIDEO = "This class can't play in this version of the app yet. Please update the app."
    }
}
