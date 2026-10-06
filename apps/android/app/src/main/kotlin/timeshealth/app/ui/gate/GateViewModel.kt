package timeshealth.app.ui.gate

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timeshealth.app.AppBuildInfo
import timeshealth.app.core.data.session.SessionStatus
import timeshealth.app.core.model.AppConfigResponse
import timeshealth.app.core.model.SessionResponse
import timeshealth.app.ui.session.AccountGateway
import timeshealth.app.ui.session.SessionGateway

/** Where the Gate sends the user once it has decided. */
enum class GateDestination { LOGIN, ONBOARDING, TABS }

/** A saved race pass offered from the Gate: opens the bib with no network (PRD §8.3). */
@Immutable
data class PassShortcut(val eventId: String, val eventName: String)

/** What the Gate is showing. */
@Immutable
sealed interface GatePhase {
    /** The splash: checking the launch config, restoring the login, fetching the session. */
    data object Starting : GatePhase

    /** This version is below the server's minSupportedAppVersion: "Update now" (Play Store). */
    data class UpdateRequired(val currentVersion: String) : GatePhase

    /** The server's maintenance switch is on: "Back shortly" + its message + Try again. */
    data class Maintenance(val message: String, val retrying: Boolean = false) : GatePhase

    /** GET /session failed: "We couldn't reach TimesHealth+" + Try again + saved passes. */
    data class Unreachable(val retrying: Boolean = false) : GatePhase

    /** Decided: the screen navigates there (and leaves the back stack). */
    data class Done(val destination: GateDestination) : GatePhase
}

@Immutable
data class GateUiState(
    val phase: GatePhase = GatePhase.Starting,
    /** Race passes saved on this phone (still inside their offline signature window). */
    val passes: List<PassShortcut> = emptyList(),
    /** The launch has taken longer than [GateViewModel.SLOW_AFTER]. */
    val slow: Boolean = false,
) {
    /**
     * The splash offers the saved passes once the launch is slow: a normal
     * launch is gone in a second; still here after a few, the network is
     * struggling, and a runner at a packed stadium shouldn't have to wait.
     */
    val showPassesOnSplash: Boolean get() = phase == GatePhase.Starting && slow && passes.isNotEmpty()
}

/**
 * The entry gate: port of apps/mobile/app/index.tsx (PRD §5 plus the two launch
 * switches every release needs).
 *
 * ```
 * launch → [force update? maintenance?] → session status
 *        ├─ signed out → LOGIN
 *        └─ signed in → GET /session
 *             ├─ fails → "We couldn't reach TimesHealth+" (+ saved race passes)
 *             ├─ needsOnboarding → ONBOARDING
 *             └─ else → TABS
 * ```
 *
 * - The launch config (public GET /config) is checked BEFORE sign-in: the
 *   update gate has to work even in a version whose login is what broke. It
 *   fails OPEN: a hiccup fetching it must never lock every user out; if the API
 *   is truly down, the session step says so.
 * - GET /session waits for the session status to leave Loading, so it never
 *   asks before the identity provider has restored the saved login.
 * - "Known account skips onboarding" is decided server-side (needsOnboarding),
 *   so a returning subscriber never sees onboarding again, even on a fresh install.
 * - Race morning at a packed stadium is when the API is least reachable, so a
 *   saved race pass opens from here without waiting for it (§8.3).
 */
@HiltViewModel
class GateViewModel @Inject constructor(
    private val session: SessionGateway,
    private val account: AccountGateway,
    private val build: AppBuildInfo,
) : ViewModel() {

    private val _state = MutableStateFlow(GateUiState())
    val state: StateFlow<GateUiState> = _state.asStateFlow()

    /** The Play Store listing "Update now" opens. */
    val applicationId: String get() = build.applicationId

    private var flow: Job? = null

    init {
        viewModelScope.launch {
            val passes = attempt { account.savedPasses() }.orEmpty()
            _state.update { s -> s.copy(passes = passes.map { PassShortcut(it.eventId, it.eventName) }) }
        }
        viewModelScope.launch {
            delay(SLOW_AFTER)
            _state.update { it.copy(slow = true) }
        }
        start(refreshConfig = false, refreshSession = false)
    }

    /** "Try again" on the maintenance and the unreachable messages. */
    fun retry() {
        when (val phase = _state.value.phase) {
            is GatePhase.Maintenance -> if (!phase.retrying) {
                setPhase(phase.copy(retrying = true))
                start(refreshConfig = true, refreshSession = false)
            }
            is GatePhase.Unreachable -> if (!phase.retrying) {
                setPhase(phase.copy(retrying = true))
                start(refreshConfig = false, refreshSession = true)
            }
            else -> Unit
        }
    }

    private fun start(refreshConfig: Boolean, refreshSession: Boolean) {
        flow?.cancel()
        flow = viewModelScope.launch {
            // 1. Launch switches. Fail open: no config means no switch is on.
            val config: AppConfigResponse? = attempt { account.config(refresh = refreshConfig) }
            if (config != null) {
                if (isOlderVersion(build.versionName, config.minSupportedAppVersion)) {
                    setPhase(GatePhase.UpdateRequired(build.versionName))
                    return@launch
                }
                if (config.maintenance.active) {
                    setPhase(GatePhase.Maintenance(config.maintenance.message ?: MAINTENANCE_DEFAULT))
                    return@launch
                }
            }
            if (_state.value.phase is GatePhase.Maintenance) setPhase(GatePhase.Starting)

            // 2. Who is this? Wait for the saved login to be restored, then ask the server.
            var refresh = refreshSession
            session.status.collectLatest { status ->
                when (status) {
                    SessionStatus.Loading -> setPhase(GatePhase.Starting)
                    SessionStatus.SignedOut -> setPhase(GatePhase.Done(GateDestination.LOGIN))
                    is SessionStatus.SignedIn -> {
                        val response: SessionResponse? = attempt { account.session(refresh = refresh) }
                        refresh = false
                        setPhase(
                            when {
                                response == null -> GatePhase.Unreachable()
                                response.needsOnboarding -> GatePhase.Done(GateDestination.ONBOARDING)
                                else -> GatePhase.Done(GateDestination.TABS)
                            },
                        )
                    }
                }
            }
        }
    }

    private fun setPhase(phase: GatePhase) = _state.update { it.copy(phase = phase) }

    /** Runs [block]; any failure but cancellation is null (the caller decides what null means). */
    private inline fun <T> attempt(block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    companion object {
        /** RN Splash: offer the saved pass after this long on the splash. */
        val SLOW_AFTER = 3.seconds

        const val MAINTENANCE_DEFAULT = "TimesHealth+ is being updated. Please try again soon."
    }
}

/**
 * True when [current] is older than [minimum], comparing numeric
 * major.minor.patch. Port of index.tsx `isOlder`: each part is read like JS
 * `parseInt(n, 10) || 0` (leading digits; anything unreadable is 0), and
 * missing parts are 0, so "1.2" == "1.2.0".
 */
fun isOlderVersion(current: String, minimum: String): Boolean {
    fun parts(v: String) = v.split('.').map { part ->
        part.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
    val a = parts(current)
    val b = parts(minimum)
    for (i in 0 until 3) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x < y
    }
    return false
}
