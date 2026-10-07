package timeshealth.app.ui.inbox

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timeshealth.app.core.data.repository.NotificationsRepository
import timeshealth.app.core.domain.formatSessionDate
import timeshealth.app.core.domain.parseIsoInstant
import timeshealth.app.core.model.NotificationItem
import timeshealth.app.core.network.ServerClock

/** What the bell's inbox needs from core:data. */
interface InboxListGateway {
    /** Every push this user was sent, newest first. */
    suspend fun items(refresh: Boolean): List<NotificationItem>

    /** When the user last looked, or null. */
    fun seenAtMs(): Long?

    suspend fun markSeen(items: List<NotificationItem>)

    fun nowMs(): Long
}

class RepositoryInboxListGateway @Inject constructor(
    private val notifications: NotificationsRepository,
    private val clock: ServerClock,
) : InboxListGateway {
    override suspend fun items(refresh: Boolean) = notifications.newestFirst(notifications.inbox.get(refresh).items)
    override fun seenAtMs(): Long? = notifications.seenAtMs
    override suspend fun markSeen(items: List<NotificationItem>) = notifications.markSeen(items)
    override fun nowMs(): Long = clock.now()
}

/**
 * The inbox stays calm while loading or failing: a quiet line, never an error
 * screen. Items already held stay up through a failed refresh.
 */
@Immutable
data class InboxUi(
    val items: List<NotificationItem> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** What the user had seen when this opening began: newer items are marked new. */
    val seenOnOpenMs: Long? = null,
) {
    fun isNew(item: NotificationItem): Boolean {
        val sent = parseIsoInstant(item.sentAt)?.toEpochMilli() ?: return false
        return seenOnOpenMs == null || sent > seenOnOpenMs
    }
}

/** The TopHeader bell's inbox (§11): every push this user was sent, newest first. */
@HiltViewModel
class InboxViewModel @Inject constructor(private val gateway: InboxListGateway) : ViewModel() {

    private val _state = MutableStateFlow(InboxUi())
    val state: StateFlow<InboxUi> = _state.asStateFlow()

    fun nowMs(): Long = gateway.nowMs()

    /** The sheet opened: note what was already seen, fetch fresh ("did I miss something?" means now), mark it all seen. */
    fun opened() {
        _state.value = _state.value.copy(seenOnOpenMs = gateway.seenAtMs(), loading = true, failed = false)
        load()
    }

    fun retry() {
        _state.value = _state.value.copy(loading = true, failed = false)
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val items = gateway.items(refresh = true)
                _state.value = _state.value.copy(items = items, loading = false, failed = false)
                gateway.markSeen(items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, failed = true)
            }
        }
    }
}

/** "Just now", "12m ago", "2h ago", "Yesterday", then the date. */
internal fun relativeTime(iso: String, nowMs: Long): String {
    val sent = parseIsoInstant(iso) ?: return ""
    val mins = ((nowMs - sent.toEpochMilli()).coerceAtLeast(0)) / 60_000
    return when {
        mins < 1 -> "Just now"
        mins < 60 -> "${mins}m ago"
        mins / 60 < 24 -> "${mins / 60}h ago"
        mins / 60 < 48 -> "Yesterday"
        else -> formatSessionDate(Instant.ofEpochMilli(sent.toEpochMilli()))
    }
}
