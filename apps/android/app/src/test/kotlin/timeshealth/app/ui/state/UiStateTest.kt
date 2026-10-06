package timeshealth.app.ui.state

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import timeshealth.app.httpError
import timeshealth.app.networkError

@OptIn(ExperimentalCoroutinesApi::class)
class UiStateTest {

    // ── UiError: QueryState.tsx messageFor ──────────────────────────────────

    @Test
    fun `offline and timeout keep the network layer's own words`() {
        val offline = networkError().toUiError()
        assertThat(offline.message).isEqualTo("No connection. Check your network and try again.")
        assertThat(offline.kind).isEqualTo(UiError.Kind.Network)

        val timeout = networkError("TIMEOUT", "That took too long. Check your connection and try again.").toUiError()
        assertThat(timeout.message).isEqualTo("That took too long. Check your connection and try again.")
        assertThat(timeout.kind).isEqualTo(UiError.Kind.Timeout)
    }

    @Test
    fun `http failures get the app's fixed copy, never the server's raw text`() {
        assertThat(httpError(404).toUiError().message).isEqualTo("This isn’t available any more.")
        assertThat(httpError(429).toUiError().message).isEqualTo("Too many requests just now. Wait a moment and try again.")
        assertThat(httpError(500).toUiError().message).isEqualTo("Something went wrong on our side. Please try again.")
        assertThat(httpError(503).toUiError().kind).isEqualTo(UiError.Kind.Server)
        assertThat(httpError(400).toUiError().message).isEqualTo(UiError.GENERIC)
        assertThat(IllegalStateException("boom").toUiError().message).isEqualTo(UiError.GENERIC)
    }

    // ── Loadable ────────────────────────────────────────────────────────────

    @Test
    fun `loads at once, then shows the data`() = runTest {
        val answer = CompletableDeferred<String>()
        val loadable = Loadable(backgroundScope, fetch = { answer.await() })
        assertThat(loadable.state.value).isEqualTo(UiState.Loading)

        answer.complete("hello")
        runCurrent()
        assertThat(loadable.state.value).isEqualTo(UiState.Ready("hello"))
        assertThat(loadable.state.value.dataOrNull).isEqualTo("hello")
    }

    @Test
    fun `a failure with nothing on screen is Failed, and retry fetches fresh`() = runTest {
        val refreshes = mutableListOf<Boolean>()
        var fail = true
        val loadable = Loadable(backgroundScope, fetch = { refresh ->
            refreshes += refresh
            if (fail) throw networkError() else "ok"
        })
        runCurrent()
        assertThat(loadable.state.value).isInstanceOf(UiState.Failed::class.java)

        fail = false
        loadable.retry()
        assertThat(loadable.state.value).isEqualTo(UiState.Loading)
        runCurrent()
        assertThat(loadable.state.value).isEqualTo(UiState.Ready("ok"))
        assertThat(refreshes).containsExactly(false, true).inOrder()
    }

    @Test
    fun `a background refetch is silent and a failed one keeps the data`() = runTest {
        val invalidated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var next: () -> String = { "v1" }
        val loadable = Loadable(backgroundScope, fetch = { next() }, refetchWhen = invalidated)
        runCurrent()

        next = { "v2" }
        invalidated.tryEmit(Unit)
        runCurrent()
        assertThat(loadable.state.value).isEqualTo(UiState.Ready("v2"))

        next = { throw networkError() }
        invalidated.tryEmit(Unit)
        runCurrent()
        val state = loadable.state.value as UiState.Ready
        assertThat(state.data).isEqualTo("v2")
        assertThat(state.refreshing).isFalse()
        assertThat(state.refreshError?.kind).isEqualTo(UiError.Kind.Network)
    }

    @Test
    fun `pull to refresh shows refreshing over the data`() = runTest {
        val answer = CompletableDeferred<String>()
        var first = true
        val loadable = Loadable(backgroundScope, fetch = { if (first) "v1".also { first = false } else answer.await() })
        runCurrent()

        loadable.refresh()
        runCurrent()
        assertThat(loadable.state.value).isEqualTo(UiState.Ready("v1", refreshing = true))

        answer.complete("v2")
        runCurrent()
        assertThat(loadable.state.value).isEqualTo(UiState.Ready("v2"))
    }
}
