package timeshealth.app.core.data.inbox

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timeshealth.app.core.data.quietly
import timeshealth.app.core.data.security.SecureStore
import timeshealth.app.core.domain.parseIsoInstant
import timeshealth.app.core.model.NotificationItem

/**
 * The notification inbox's "seen" marker. Port of apps/mobile/src/lib/inboxSeen.ts.
 *
 * Unread = sent after the newest item the user has already seen in the inbox. One store, because
 * every screen's bell must clear together when any one inbox is opened.
 *
 * Lives here rather than with the inbox screen so the session can reset it on sign-out: the
 * marker is per device, and the next person to sign in on this phone must not have their alerts
 * marked read by someone else.
 */
@Singleton
class InboxSeenStore @Inject constructor(private val store: SecureStore) {

    /**
     * @property seenAtMs epoch ms of the newest item shown when the inbox was last opened; null
     *   when nothing has been seen.
     * @property loaded false until the saved marker has been read. Show no dot until then, rather
     *   than a dot that flickers off.
     */
    data class State(val seenAtMs: Long?, val loaded: Boolean)

    private val _state = MutableStateFlow(State(seenAtMs = null, loaded = false))
    val state: StateFlow<State> = _state.asStateFlow()

    private val loadStarted = AtomicBoolean(false)

    /** Bumped by [reset]; a read or write that began before it must not bring the old marker back. */
    private val generation = AtomicInteger(0)

    private val writes = Mutex()

    /** Reads the saved marker, once per process. */
    suspend fun load() {
        if (!loadStarted.compareAndSet(false, true)) return
        val gen = generation.get()
        val saved = quietly { store.get(SEEN_KEY) }?.toLongOrNull()
        _state.update { current ->
            // A reset that raced ahead of this read leaves nothing worth loading.
            if (gen != generation.get()) return@update current
            // A markSeen that raced ahead of this read wins.
            val seenAt = if (saved != null) maxOf(saved, current.seenAtMs ?: 0L) else current.seenAtMs
            State(seenAtMs = seenAt, loaded = true)
        }
    }

    /** Marks everything sent up to [atMs] as seen. Never moves the marker backwards. */
    suspend fun markSeen(atMs: Long) {
        var advanced = false
        _state.update { current ->
            advanced = current.seenAtMs == null || current.seenAtMs < atMs
            if (advanced) State(seenAtMs = atMs, loaded = true) else current
        }
        if (!advanced) return
        val gen = generation.get()
        writes.withLock {
            if (gen != generation.get()) return@withLock
            // The latest marker, not necessarily this call's: two quick markSeens can reach this
            // lock in either order, and the disk must end up with the newer one.
            val latest = _state.value.seenAtMs ?: return@withLock
            // Losing this only means the dot may reappear after a restart.
            quietly { store.put(SEEN_KEY, latest.toString()) }
        }
    }

    /** Opening the inbox marks everything in it as seen: the newest [items] `sentAt`. */
    suspend fun markSeen(items: List<NotificationItem>) {
        items.mapNotNull { it.sentAtMs() }.maxOrNull()?.let { markSeen(it) }
    }

    /**
     * True when [items] hold something newer than what the user last saw. False until the marker
     * is loaded (no flickering dot) and for an empty inbox.
     */
    fun hasUnread(items: List<NotificationItem>, state: State = this.state.value): Boolean {
        if (!state.loaded || items.isEmpty()) return false
        return items.any { item ->
            val sent = item.sentAtMs() ?: return@any false
            state.seenAtMs == null || sent > state.seenAtMs
        }
    }

    /** Forgets the marker. Called whenever the signed-in identity changes. */
    suspend fun reset() {
        generation.incrementAndGet()
        loadStarted.set(true) // nothing left on disk worth loading
        _state.value = State(seenAtMs = null, loaded = true)
        writes.withLock { store.remove(SEEN_KEY) }
    }

    private fun NotificationItem.sentAtMs(): Long? = parseIsoInstant(sentAt)?.toEpochMilli()

    internal companion object {
        const val SEEN_KEY = "th_inbox_seen_at"
    }
}
