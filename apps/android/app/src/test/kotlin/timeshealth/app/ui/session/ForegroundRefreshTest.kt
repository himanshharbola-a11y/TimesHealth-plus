package timeshealth.app.ui.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import timeshealth.app.core.data.cache.CacheKey
import timeshealth.app.core.data.cache.CacheKeys
import timeshealth.app.core.data.cache.ResponseCache

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundRefreshTest {

    private var now = 1_000_000L
    private val cache = ResponseCache()
    private val refresh = ForegroundRefresh(cache) { now }

    /** Counts the refetch signals for each key while [block] runs. */
    private fun kotlinx.coroutines.test.TestScope.signals(keys: List<CacheKey>, block: () -> Unit): Map<CacheKey, Int> {
        val counts = keys.associateWith { 0 }.toMutableMap()
        keys.forEach { key ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                cache.changes(key).collect { counts[key] = counts.getValue(key) + 1 }
            }
        }
        block()
        return counts
    }

    private val watched = ForegroundRefresh.REFRESHED + listOf(CacheKeys.Runs, CacheKeys.Workshops)

    @Test
    fun `back after a minute or more refreshes session, home, yoga, marathon, notifications`() = runTest {
        val counts = signals(watched) {
            refresh.onBackground()
            now += ForegroundRefresh.STALE_AFTER_BACKGROUND_MS
            refresh.onForeground()
        }
        ForegroundRefresh.REFRESHED.forEach { assertThat(counts[it]).isEqualTo(1) }
        // Only those: nothing else is entitlement-shaped.
        assertThat(counts[CacheKeys.Runs]).isEqualTo(0)
        assertThat(counts[CacheKeys.Workshops]).isEqualTo(0)
    }

    @Test
    fun `a quick switch away and back refreshes nothing`() = runTest {
        val counts = signals(watched) {
            refresh.onBackground()
            now += ForegroundRefresh.STALE_AFTER_BACKGROUND_MS - 1
            refresh.onForeground()
        }
        assertThat(counts.values.sum()).isEqualTo(0)
    }

    @Test
    fun `the first start of the process is not a return from the background`() = runTest {
        val counts = signals(watched) {
            now += 10 * ForegroundRefresh.STALE_AFTER_BACKGROUND_MS
            refresh.onForeground()
        }
        assertThat(counts.values.sum()).isEqualTo(0)
    }

    @Test
    fun `each background trip is measured on its own`() = runTest {
        val counts = signals(listOf(CacheKeys.Session)) {
            refresh.onBackground()
            now += 5_000
            refresh.onForeground() // short: nothing
            now += 120_000 // in the foreground: doesn't count
            refresh.onBackground()
            now += 5_000
            refresh.onForeground() // short again: nothing
        }
        assertThat(counts[CacheKeys.Session]).isEqualTo(0)
    }
}
