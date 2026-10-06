package timeshealth.app.core.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import timeshealth.app.core.data.inbox.InboxSeenStore

@OptIn(ExperimentalCoroutinesApi::class)
class InboxSeenStoreTest {

    private val store = FakeSecureStore()
    private val seen = InboxSeenStore(store)

    private val older = notification("n1", "2026-10-06T05:00:00.000Z")
    private val newer = notification("n2", "2026-10-06T06:00:00.000Z")
    private val newerMs = 1_791_266_400_000L

    @Test
    fun `no dot until the marker is loaded, then everything unseen is unread`() = runTest {
        assertThat(seen.hasUnread(listOf(older))).isFalse()

        seen.load()

        assertThat(seen.state.value).isEqualTo(InboxSeenStore.State(seenAtMs = null, loaded = true))
        assertThat(seen.hasUnread(listOf(older))).isTrue()
        assertThat(seen.hasUnread(emptyList())).isFalse()
    }

    @Test
    fun `opening the inbox marks up to the newest item as seen, and it survives a restart`() = runTest {
        seen.load()

        seen.markSeen(listOf(older, newer))

        assertThat(seen.state.value.seenAtMs).isEqualTo(newerMs)
        assertThat(seen.hasUnread(listOf(older, newer))).isFalse()
        assertThat(seen.hasUnread(listOf(notification("n3", "2026-10-06T06:00:01.000Z")))).isTrue()

        val afterRestart = InboxSeenStore(store)
        afterRestart.load()
        assertThat(afterRestart.state.value.seenAtMs).isEqualTo(newerMs)
    }

    @Test
    fun `the marker never moves backwards`() = runTest {
        seen.markSeen(newerMs)
        seen.markSeen(newerMs - 1_000)

        assertThat(seen.state.value.seenAtMs).isEqualTo(newerMs)
        assertThat(store.values[InboxSeenStore.SEEN_KEY]).isEqualTo(newerMs.toString())
    }

    @Test
    fun `a markSeen that raced ahead of the load wins, unless the disk is newer`() = runTest {
        store.values[InboxSeenStore.SEEN_KEY] = "1000"
        store.failWrites = true // keep the disk as it was, to see the merge

        seen.markSeen(5_000)
        seen.load()
        assertThat(seen.state.value.seenAtMs).isEqualTo(5_000)

        store.values[InboxSeenStore.SEEN_KEY] = "9000"
        val other = InboxSeenStore(store)
        other.markSeen(5_000)
        other.load()
        assertThat(other.state.value.seenAtMs).isEqualTo(9_000)
    }

    @Test
    fun `an unreadable sentAt is never unread and never marks anything`() = runTest {
        seen.load()
        val broken = notification("bad", "yesterday")

        assertThat(seen.hasUnread(listOf(broken))).isFalse()
        seen.markSeen(listOf(broken))
        assertThat(seen.state.value.seenAtMs).isNull()
    }

    @Test
    fun `reset forgets the marker, in memory and on disk`() = runTest {
        seen.load()
        seen.markSeen(newerMs)

        seen.reset()

        assertThat(seen.state.value).isEqualTo(InboxSeenStore.State(seenAtMs = null, loaded = true))
        assertThat(store.values).doesNotContainKey(InboxSeenStore.SEEN_KEY)
        assertThat(seen.hasUnread(listOf(older))).isTrue()
        // Nothing on disk to bring back.
        seen.load()
        assertThat(seen.state.value.seenAtMs).isNull()
    }

    @Test
    fun `the previous user's marker can't land on disk after the reset`() = runTest {
        val gate = CompletableDeferred<Unit>()
        store.pausePut = InboxSeenStore.SEEN_KEY to gate
        launch { seen.markSeen(5_000) } // first write, paused mid-flight
        runCurrent()
        launch { seen.markSeen(9_000) } // waits behind it
        runCurrent()

        launch { seen.reset() }
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertThat(seen.state.value.seenAtMs).isNull()
        assertThat(store.values).doesNotContainKey(InboxSeenStore.SEEN_KEY)
    }

    @Test
    fun `a failing store only costs the dot after a restart`() = runTest {
        store.failWrites = true

        seen.markSeen(newerMs)

        assertThat(seen.state.value.seenAtMs).isEqualTo(newerMs)
    }
}
