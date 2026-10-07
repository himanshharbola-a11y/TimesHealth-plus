package timeshealth.app.ui.run

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timeshealth.app.core.domain.Coordinates
import timeshealth.app.core.domain.PacePoint
import timeshealth.app.core.domain.Split
import timeshealth.app.core.domain.TimedPoint
import timeshealth.app.core.domain.computeSplits
import timeshealth.app.core.domain.paceSeries
import timeshealth.app.core.data.repository.RunsRepository
import timeshealth.app.core.integrations.analytics.Analytics
import timeshealth.app.core.model.RunHistoryResponse
import timeshealth.app.core.integrations.analytics.AnalyticsEvents
import timeshealth.app.core.runtracker.ActiveRun
import timeshealth.app.core.runtracker.FinishedRun
import timeshealth.app.core.runtracker.LocationPermissionRequiredException
import timeshealth.app.core.runtracker.RunDisplayPreferences
import timeshealth.app.core.runtracker.RunOwnerProvider
import timeshealth.app.core.runtracker.RunTracker
import timeshealth.app.core.runtracker.decodePolyline
import timeshealth.app.core.runtracker.sync.RunSync

/** What the run screens need from core:runtracker (an interface, so the ViewModel is testable). */
interface RunGateway {
    suspend fun owner(): String?
    fun activeRun(owner: String): Flow<ActiveRun?>
    fun liveRoute(runId: String): Flow<List<TimedPoint>>
    suspend fun recover(owner: String): ActiveRun?
    fun hasLocationPermission(): Boolean
    suspend fun start(owner: String): ActiveRun
    suspend fun pause()
    suspend fun resume()
    suspend fun finish(): FinishedRun?
    suspend fun route(runId: String): List<TimedPoint>
    fun enqueueUpload(owner: String)
    suspend fun imperial(): Boolean
    fun nowMs(): Long

    /** Recent runs and totals from the server. */
    suspend fun history(refresh: Boolean): RunHistoryResponse
    fun track(event: timeshealth.app.core.integrations.analytics.AnalyticsEvent)
}

class TrackerRunGateway @Inject constructor(
    private val tracker: RunTracker,
    private val owners: RunOwnerProvider,
    private val display: RunDisplayPreferences,
    private val analytics: Analytics,
    private val runs: RunsRepository,
    @ApplicationContext private val context: Context,
) : RunGateway {
    override suspend fun owner() = owners.currentOwner()
    override fun activeRun(owner: String) = tracker.activeRun(owner)
    override fun liveRoute(runId: String) = tracker.liveRoute(runId)
    override suspend fun recover(owner: String) = tracker.recover(owner)
    override fun hasLocationPermission() = tracker.hasLocationPermission()
    override suspend fun start(owner: String) = tracker.start(owner)
    override suspend fun pause() {
        tracker.pause()
    }
    override suspend fun resume() {
        tracker.resume()
    }
    override suspend fun finish() = tracker.finish()
    override suspend fun route(runId: String) = tracker.route(runId)
    override fun enqueueUpload(owner: String) = RunSync.enqueue(context, owner)
    override suspend fun imperial() = display.imperial()
    override fun nowMs() = System.currentTimeMillis()
    override suspend fun history(refresh: Boolean) = runs.history.get(refresh)
    override fun track(event: timeshealth.app.core.integrations.analytics.AnalyticsEvent) = analytics.track(event)
}

@Immutable
sealed interface RunUi {
    data object Loading : RunUi

    /** No run in progress: the start screen. */
    data object Ready : RunUi

    /** Recording or paused: the live screen. */
    data class Active(val run: ActiveRun, val route: List<TimedPoint>) : RunUi

    /** Finished and saved: the summary. */
    data class Summary(
        val run: FinishedRun,
        val route: List<Coordinates>,
        val splits: List<Split>,
        val pace: List<PacePoint>,
    ) : RunUi

    /** Finished under the minimum distance: not saved. */
    data object TooShort : RunUi

    /** Signed out (no profile to file the run under). */
    data object NoOwner : RunUi
}

/**
 * The GPS run tracker (PRD §8.6): ready → recording ⇄ paused → summary.
 *
 * The run itself lives in core:runtracker (foreground service, Room, upload
 * queue); this screen follows it. A run recovered after the app was killed
 * comes back paused, so the runner resumes on their real position.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RunTrackerViewModel @Inject constructor(private val gateway: RunGateway) : ViewModel() {

    private val _ui = MutableStateFlow<RunUi>(RunUi.Loading)
    val ui: StateFlow<RunUi> = _ui.asStateFlow()

    /** Distances in miles. */
    var imperial: Boolean = false
        private set

    /** A one-off message (couldn't start, couldn't finish). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** True when the screen must ask for location before starting. */
    private val _needsPermission = MutableStateFlow(false)
    val needsPermission: StateFlow<Boolean> = _needsPermission.asStateFlow()

    private var owner: String? = null

    /** Past runs for the start sheet. Optional: a failure just leaves it out. */
    private val _history = MutableStateFlow<RunHistoryResponse?>(null)
    val history: StateFlow<RunHistoryResponse?> = _history.asStateFlow()

    private fun loadHistory(refresh: Boolean) {
        viewModelScope.launch {
            try {
                _history.value = gateway.history(refresh)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Unit
            }
        }
    }

    /** Set while the summary is showing: the live stream (which emits null after finish) must not replace it. */
    private var showingResult = false

    init {
        viewModelScope.launch {
            imperial = runCatching { gateway.imperial() }.getOrDefault(false)
            val who = gateway.owner()
            owner = who
            if (who == null) {
                _ui.value = RunUi.NoOwner
                return@launch
            }
            loadHistory(refresh = false)
            gateway.recover(who)
            gateway.activeRun(who)
                .flatMapLatest { run -> if (run == null) flowOf(null) else gateway.liveRoute(run.id).map { RunUi.Active(run, it) } }
                .collect { active -> if (!showingResult) _ui.value = active ?: RunUi.Ready }
        }
    }

    fun nowMs(): Long = gateway.nowMs()

    fun consumeMessage() {
        _message.value = null
    }

    /** START. Without precise location, asks the screen to request it ([needsPermission]). */
    fun start() {
        val who = owner ?: return
        if (!gateway.hasLocationPermission()) {
            _needsPermission.value = true
            return
        }
        viewModelScope.launch {
            try {
                gateway.start(who)
                gateway.track(AnalyticsEvents.runStarted())
            } catch (e: CancellationException) {
                throw e
            } catch (e: LocationPermissionRequiredException) {
                _needsPermission.value = true
            } catch (e: Exception) {
                _message.value = "Couldn't start tracking. Keep the app open and try again."
            }
        }
    }

    /** The permission dialog closed (granted or not). */
    fun permissionResult(granted: Boolean) {
        _needsPermission.value = false
        if (granted && gateway.hasLocationPermission()) start()
        else if (!granted) _message.value = "Precise location is needed to measure your run."
    }

    fun pause() = viewModelScope.launch { gateway.pause() }

    fun resume() = viewModelScope.launch {
        try {
            gateway.resume()
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocationPermissionRequiredException) {
            _needsPermission.value = true
        } catch (e: Exception) {
            _message.value = "Couldn't resume tracking. Try again."
        }
    }

    fun finish() = viewModelScope.launch {
        val who = owner ?: return@launch
        val active = (_ui.value as? RunUi.Active)?.run
        showingResult = true
        // The timed points are pruned once the run uploads: read them before and after
        // finishing (the last fixes land during finish) and keep the fuller copy.
        val before = active?.let { gateway.route(it.id) }.orEmpty()
        val finished = try {
            gateway.finish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (finished == null) {
            showingResult = false
            _ui.value = RunUi.Ready
            return@launch
        }
        if (finished.isTooShort) {
            _ui.value = RunUi.TooShort
            return@launch
        }
        val after = gateway.route(finished.id)
        val timed = if (after.size >= before.size) after else before
        gateway.enqueueUpload(who)
        gateway.track(AnalyticsEvents.runFinished(finished.distanceKm, finished.durationSeconds))
        _ui.value = RunUi.Summary(
            run = finished,
            route = timed.ifEmpty { null } ?: finished.routePolyline?.let(::decodePolyline).orEmpty(),
            splits = computeSplits(timed, if (imperial) METERS_PER_MILE else 1000.0),
            pace = paceSeries(timed),
        )
    }

    /** "Done" on the summary or "Too short": back to Ready for another run. */
    fun done() {
        showingResult = false
        _ui.value = RunUi.Ready
        // The run just finished uploads in the background; refresh so it appears once synced.
        loadHistory(refresh = true)
    }

    companion object {
        const val METERS_PER_MILE = 1609.344
    }
}
