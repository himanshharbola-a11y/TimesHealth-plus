package timeshealth.app.core.data

import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Test
import timeshealth.app.core.data.cache.CacheKey
import timeshealth.app.core.data.cache.CacheKeys
import timeshealth.app.core.data.cache.ResponseCache

@OptIn(ExperimentalCoroutinesApi::class)
class ResponseCacheTest {

    private var fetches = 0

    private suspend fun ResponseCache.load(key: CacheKey, refresh: Boolean = false): String =
        get(key, 30.seconds, refresh) { "v${++fetches}" }

    @Test
    fun `answers from memory while fresh, refetches once stale or on refresh`() = runTest {
        val cache = ResponseCache(testTimeSource)

        assertThat(cache.load(CacheKeys.Home)).isEqualTo("v1")
        delay(29_000)
        assertThat(cache.load(CacheKeys.Home)).isEqualTo("v1")
        assertThat(cache.load(CacheKeys.Home, refresh = true)).isEqualTo("v2")
        delay(30_000)
        assertThat(cache.load(CacheKeys.Home)).isEqualTo("v3")
    }

    @Test
    fun `a prefix invalidates every key under it and nothing else`() = runTest {
        val cache = ResponseCache(testTimeSource)
        val race = CacheKeys.raceDetail("delhi_half")
        val nearby = CacheKeys.marathonEvents(28.6, 77.2)
        listOf(race, nearby, CacheKeys.Referral, CacheKeys.Home).forEach { cache.seed(it) }

        cache.invalidate(CacheKeys.Marathon)

        assertThat(cache.wasInvalidated(race)).isTrue()
        assertThat(cache.wasInvalidated(nearby)).isTrue()
        assertThat(cache.wasInvalidated(CacheKeys.Referral)).isTrue()
        assertThat(cache.wasInvalidated(CacheKeys.Home)).isFalse()
    }

    @Test
    fun `changes fires for an invalidated key, a parent prefix and a clear, not for others`() = runTest {
        val cache = ResponseCache(testTimeSource)
        var homeSignals = 0
        var raceSignals = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cache.changes(CacheKeys.Home).collect { homeSignals++ } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            cache.changes(CacheKeys.raceDetail("delhi_half")).collect { raceSignals++ }
        }

        cache.invalidate(CacheKeys.Home)
        cache.invalidate(CacheKeys.Marathon)
        cache.invalidate(CacheKeys.Content)
        cache.clear()

        assertThat(homeSignals).isEqualTo(2)
        assertThat(raceSignals).isEqualTo(2)
    }

    @Test
    fun `invalidated data stays readable until the refetch lands`() = runTest {
        val cache = ResponseCache(testTimeSource)
        cache.load(CacheKeys.Home)

        cache.invalidate(CacheKeys.Home)

        assertThat(cache.peek<String>(CacheKeys.Home)).isEqualTo("v1")
        assertThat(cache.load(CacheKeys.Home)).isEqualTo("v2")
    }

    @Test
    fun `clear forgets everything, and a fetch from before it can't write the old user's data back`() = runTest {
        val cache = ResponseCache(testTimeSource)
        val gate = CompletableDeferred<Unit>()
        cache.load(CacheKeys.Session)
        val inFlight = async { cache.get(CacheKeys.Home, 30.seconds, false) { gate.await(); "previous user's home" } }
        runCurrent()

        cache.clear() // sign-out
        gate.complete(Unit)

        assertThat(inFlight.await()).isEqualTo("previous user's home") // its caller still gets an answer
        assertThat(cache.peek<String>(CacheKeys.Home)).isNull()
        assertThat(cache.peek<String>(CacheKeys.Session)).isNull()
    }

    @Test
    fun `a fetch that started before an invalidation doesn't count as fresh`() = runTest {
        val cache = ResponseCache(testTimeSource)
        val gate = CompletableDeferred<Unit>()
        val inFlight = async { cache.get(CacheKeys.Home, 30.seconds, false) { gate.await(); "before purchase" } }
        runCurrent()

        cache.invalidate(CacheKeys.Home) // e.g. a purchase was granted meanwhile
        gate.complete(Unit)
        inFlight.await()

        assertThat(cache.load(CacheKeys.Home)).isEqualTo("v1")
    }

    @Test
    fun `an optimistic update shows at once and an older fetch can't overwrite it`() = runTest {
        val cache = ResponseCache(testTimeSource)
        cache.get(CacheKeys.YogaMine, 30.seconds, false) { listOf("a") }
        val seen = mutableListOf<List<String>?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            cache.data<List<String>>(CacheKeys.YogaMine).collect { seen += it }
        }
        val gate = CompletableDeferred<Unit>()
        val inFlight = async { cache.get(CacheKeys.YogaMine, 30.seconds, true) { gate.await(); listOf("a") } }
        runCurrent()

        cache.update<List<String>>(CacheKeys.YogaMine) { it + "b" }
        gate.complete(Unit)
        inFlight.await()

        assertThat(cache.peek<List<String>>(CacheKeys.YogaMine)).containsExactly("a", "b")
        assertThat(seen).containsExactly(listOf("a"), listOf("a", "b")).inOrder()
    }

    @Test
    fun `an update with nothing cached does nothing`() = runTest {
        val cache = ResponseCache(testTimeSource)

        cache.update<List<String>>(CacheKeys.YogaMine) { it + "b" }

        assertThat(cache.peek<List<String>>(CacheKeys.YogaMine)).isNull()
    }

    @Test
    fun `a failed fetch leaves the cache as it was`() = runTest {
        val cache = ResponseCache(testTimeSource)
        cache.load(CacheKeys.Home)
        cache.invalidate(CacheKeys.Home)

        expectThrows<IllegalStateException> { cache.get<String>(CacheKeys.Home, 30.seconds, false) { error("offline") } }

        assertThat(cache.peek<String>(CacheKeys.Home)).isEqualTo("v1")
    }
}
