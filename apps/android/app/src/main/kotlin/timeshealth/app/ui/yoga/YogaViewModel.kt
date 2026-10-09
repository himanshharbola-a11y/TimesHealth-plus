package timeshealth.app.ui.yoga

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timeshealth.app.core.data.repository.WorkshopsRepository
import timeshealth.app.core.data.repository.YogaRepository
import timeshealth.app.core.model.LiveWorkshop
import timeshealth.app.core.domain.istDate
import timeshealth.app.core.domain.parseIsoInstant
import timeshealth.app.core.model.JoinSessionResponse
import timeshealth.app.core.model.LiveClassListResponse
import timeshealth.app.core.model.LiveClassState
import timeshealth.app.core.model.SessionResponse
import timeshealth.app.core.model.UserPersona
import timeshealth.app.core.model.YogaAttendance
import timeshealth.app.core.model.YogaCatalogResponse
import timeshealth.app.core.model.YogaTodayResponse
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.core.network.ServerClock
import timeshealth.app.ui.session.AccountGateway
import timeshealth.app.ui.state.Loadable
import timeshealth.app.ui.state.UiState
import timeshealth.app.ui.state.toUiError

/** What the Yoga tab needs from core:data. */
interface YogaGateway {
    suspend fun today(refresh: Boolean = false): YogaTodayResponse
    val todayChanges: Flow<Unit>
    suspend fun catalog(refresh: Boolean = false): YogaCatalogResponse
    suspend fun liveClasses(refresh: Boolean = false): LiveClassListResponse
    suspend fun attendance(refresh: Boolean = false): YogaAttendance
    suspend fun join(batchId: String): JoinSessionResponse
    suspend fun setReminderSlot(batchId: String)
    suspend fun workshops(refresh: Boolean = false): List<LiveWorkshop>
    fun nowMs(): Long
}

class RepositoryYogaGateway @Inject constructor(
    private val yoga: YogaRepository,
    private val workshopsRepo: WorkshopsRepository,
    private val clock: ServerClock,
) : YogaGateway {
    override suspend fun workshops(refresh: Boolean) = workshopsRepo.workshops.get(refresh).workshops
    override suspend fun today(refresh: Boolean) = yoga.today.get(refresh)
    override val todayChanges: Flow<Unit> get() = yoga.today.changes
    override suspend fun catalog(refresh: Boolean) = yoga.catalog.get(refresh)
    override suspend fun liveClasses(refresh: Boolean) = yoga.liveClasses.get(refresh).also { clock.sync(it.serverTime) }
    override suspend fun attendance(refresh: Boolean) = yoga.attendance.get(refresh)
    override suspend fun join(batchId: String) = yoga.join(batchId)
    override suspend fun setReminderSlot(batchId: String) {
        yoga.setReminderSlot(batchId)
    }
    override fun nowMs(): Long = clock.now()
}

@Immutable
data class YogaUi(
    /** An active member: the class schedule and tracker. Otherwise the sales page with content in it (§7.2). */
    val member: Boolean,
    /** A lapsed member is welcomed back rather than pitched to as a stranger. */
    val expired: Boolean,
    val today: YogaTodayResponse,
    val catalog: YogaCatalogResponse?,
    val liveClasses: LiveClassListResponse?,
    /** Upcoming live workshops (masterclasses); optional, left out on failure. */
    val workshops: List<LiveWorkshop> = emptyList(),
)

/** What a tap on "Join" should do. */
sealed interface JoinTarget {
    /** The admin scheduled a premiere for this batch: play it in the app. */
    data class InApp(val liveClassId: String) : JoinTarget

    /** The class link (attendance already recorded by the server). */
    data class External(val url: String) : JoinTarget
}

/**
 * The Yoga tab (PRD §7). Members get today's schedule (live/next class, all
 * daily batches with their reminder slot, this week's live classes), the
 * programme tracks and recordings, and the attendance tracker. Everyone else
 * gets the sales page with free sessions and free live classes in it.
 */
@HiltViewModel
class YogaViewModel @Inject constructor(
    private val gateway: YogaGateway,
    account: AccountGateway,
) : ViewModel() {

    private val session = Loadable(viewModelScope, { r -> account.session(r) }, account.sessionChanges)
    private val today = Loadable(viewModelScope, { r -> gateway.today(r) }, gateway.todayChanges)
    private val catalog = MutableStateFlow<YogaCatalogResponse?>(null)
    private val live = MutableStateFlow<LiveClassListResponse?>(null)
    private val workshops = MutableStateFlow<List<LiveWorkshop>>(emptyList())

    val state: StateFlow<UiState<YogaUi>> = combine(session.state, today.state, catalog, live, workshops) { s, t, c, l, w -> merge(s, t, c, l).withWorkshops(w) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    private val _attendance = MutableStateFlow<UiState<YogaAttendance>?>(null)
    val attendance: StateFlow<UiState<YogaAttendance>?> = _attendance.asStateFlow()

    /** A one-off message (couldn't join, couldn't set the reminder). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** The batch whose join or reminder is in flight. */
    private val _busyBatch = MutableStateFlow<String?>(null)
    val busyBatch: StateFlow<String?> = _busyBatch.asStateFlow()

    init {
        loadExtras(refresh = false)
    }

    fun nowMs(): Long = gateway.nowMs()

    fun retry() {
        session.retry()
        today.retry()
        loadExtras(refresh = true)
    }

    fun refresh() {
        today.refresh()
        session.refresh()
        loadExtras(refresh = true)
        if (_attendance.value != null) loadAttendance(refresh = true)
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun loadExtras(refresh: Boolean) {
        // Optional sections: a failure leaves them out, never blanks the tab.
        viewModelScope.launch { catalog.value = quietly { gateway.catalog(refresh) } ?: catalog.value }
        viewModelScope.launch { live.value = quietly { gateway.liveClasses(refresh) } ?: live.value }
        viewModelScope.launch { workshops.value = quietly { gateway.workshops(refresh) } ?: workshops.value }
    }

    /** The Tracker segment: attendance, members only (non-members get 403). */
    fun loadAttendance(refresh: Boolean = false) {
        if (_attendance.value is UiState.Ready && !refresh) return
        if (_attendance.value == null) _attendance.value = UiState.Loading
        viewModelScope.launch {
            _attendance.value = try {
                UiState.Ready(gateway.attendance(refresh))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiState.Failed(e.toUiError())
            }
        }
    }

    /**
     * Join a batch. A premiere scheduled for it today plays in the app (the live
     * class records attendance on join); otherwise the server's join records
     * attendance and hands back the class link.
     */
    fun join(batchId: String, open: (JoinTarget) -> Unit) {
        premiereFor(batchId)?.let { return open(JoinTarget.InApp(it)) }
        if (_busyBatch.value != null) return
        _busyBatch.value = batchId
        viewModelScope.launch {
            try {
                open(JoinTarget.External(gateway.join(batchId).joinUrl))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiRequestException) {
                _message.value = e.error.message.ifBlank { "Couldn’t join the class. Try again." }
            } catch (e: Exception) {
                _message.value = "Couldn’t join the class. Check your connection and try again."
            } finally {
                _busyBatch.value = null
            }
        }
    }

    /** Pick [batchId] for class reminders (§7.1; any batch can still be joined). */
    fun setReminderSlot(batchId: String) {
        if (_busyBatch.value != null) return
        _busyBatch.value = batchId
        viewModelScope.launch {
            try {
                gateway.setReminderSlot(batchId)
                today.refresh()
                _message.value = "Reminders set for this batch."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Couldn’t update your reminder. Check your connection and try again."
            } finally {
                _busyBatch.value = null
            }
        }
    }

    /** Today's admin-scheduled live class for [batchId], if not cancelled or over. */
    internal fun premiereFor(batchId: String): String? {
        val batch = (today.state.value as? UiState.Ready)?.data?.batches?.firstOrNull { it.id == batchId } ?: return null
        val day = parseIsoInstant(batch.startsAt)?.let(::istDate) ?: return null
        return live.value?.items?.firstOrNull { lc ->
            lc.batchId == batchId && lc.state != LiveClassState.CANCELLED && lc.state != LiveClassState.ENDED &&
                parseIsoInstant(lc.startsAt)?.let(::istDate) == day
        }?.id
    }

    private fun merge(
        session: UiState<SessionResponse>,
        today: UiState<YogaTodayResponse>,
        catalog: YogaCatalogResponse?,
        live: LiveClassListResponse?,
    ): UiState<YogaUi> = when {
        session is UiState.Failed -> session
        today is UiState.Failed -> today
        session is UiState.Ready && today is UiState.Ready -> UiState.Ready(
            YogaUi(
                member = session.data.persona.hasYoga,
                expired = session.data.persona.persona == UserPersona.YOGA_EXPIRED,
                today = today.data,
                catalog = catalog,
                liveClasses = live,
            ),
            refreshing = today.refreshing,
        )
        else -> UiState.Loading
    }

    private fun UiState<YogaUi>.withWorkshops(w: List<LiveWorkshop>): UiState<YogaUi> =
        if (this is UiState.Ready) UiState.Ready(data.copy(workshops = w), refreshing = refreshing) else this

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
