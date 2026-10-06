package timeshealth.app.core.data.cache

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * A cache key as a path, like the RN app's React Query keys (`['marathon', 'event', id]`).
 * Invalidating a key invalidates every key that starts with it, so `['marathon']` covers the
 * list, every race page and the referral card at once.
 */
class CacheKey(vararg parts: Any?) {
    val path: List<Any?> = parts.toList()

    /** True when [prefix]'s path is the start of this key's path (every key starts with the root). */
    fun startsWith(prefix: CacheKey): Boolean =
        path.size >= prefix.path.size && path.subList(0, prefix.path.size) == prefix.path

    internal fun prefixes(): List<List<Any?>> = (0..path.size).map { path.subList(0, it).toList() }

    override fun equals(other: Any?): Boolean = other is CacheKey && other.path == path

    override fun hashCode(): Int = path.hashCode()

    override fun toString(): String = path.joinToString(prefix = "CacheKey[", postfix = "]")
}

/** Every key the repositories use, mirroring `qk` in apps/mobile/src/api/hooks.ts. */
object CacheKeys {
    val AppConfig = CacheKey("app-config")
    val Session = CacheKey("session")
    val Home = CacheKey("home")

    val Yoga = CacheKey("yoga")
    val YogaToday = CacheKey("yoga", "today")
    val YogaCatalog = CacheKey("yoga", "catalog")
    val YogaAttendance = CacheKey("yoga", "attendance")
    val YogaMine = CacheKey("yoga", "mine")
    val YogaLive = CacheKey("yoga", "live")

    val Marathon = CacheKey("marathon")
    fun marathonEvents(lat: Double?, lng: Double?) = CacheKey("marathon", "events", lat, lng)
    fun raceDetail(eventId: String) = CacheKey("marathon", "event", eventId)
    val Referral = CacheKey("marathon", "referral")

    val Runs = CacheKey("runs")
    val Workshops = CacheKey("workshops")
    val Content = CacheKey("content")
    val Notifications = CacheKey("notifications")

    /**
     * What resolves against entitlement, refreshed after a purchase is granted. Not every cached
     * query at once: that was a self-inflicted burst of 8+ requests per purchase in RN.
     */
    val AfterPurchase: List<CacheKey> = listOf(Session, Home, Yoga, Marathon, Workshops)
}

/**
 * The in-memory response cache behind the repositories: React Query's job in the RN app, cut
 * down to what the screens need.
 *
 * - [get] answers from memory while an entry is younger than its max age (RN `staleTime`) and
 *   hasn't been invalidated; otherwise it fetches.
 * - [invalidate] marks entries stale and fires [changes], the "refetch now" signal a screen
 *   collects. Stale entries stay readable through [data] / [peek] so a screen can keep showing
 *   them while the refetch runs (RN `keepPreviousData`).
 * - [clear] forgets everything. The session wipes it whenever the signed-in identity changes, so
 *   the next person on a shared phone never sees the last one's data.
 *
 * A fetch that started before an invalidation, a [clear] or an optimistic [update] of its key
 * never writes its (older) answer into the cache. That is what stops a response for the previous
 * user, landing after sign-out, from repopulating the cache for the next one (RN cancelled its
 * in-flight queries for the same reason).
 */
@Singleton
class ResponseCache internal constructor(private val timeSource: TimeSource) {

    @Inject
    constructor() : this(TimeSource.Monotonic)

    private class Entry(val value: Any, val fetchedAt: TimeMark, val version: Long)

    private data class State(
        val entries: Map<CacheKey, Entry> = emptyMap(),
        /** Key path (a prefix) -> serial of its last invalidation. The root path is the last clear. */
        val invalidatedAt: Map<List<Any?>, Long> = emptyMap(),
        /** Key -> serial of its last optimistic [update]. */
        val writtenAt: Map<CacheKey, Long> = emptyMap(),
        val serial: Long = 0,
    ) {
        /** Grows whenever [key] or any prefix of it is invalidated or cleared. */
        fun versionOf(key: CacheKey): Long = key.prefixes().maxOf { invalidatedAt[it] ?: 0L }
    }

    private val state = MutableStateFlow(State())

    /**
     * The cached value for [key] when it is fresh (younger than [maxAge], not invalidated) and
     * [refresh] is false; otherwise the result of [fetch], cached unless it was overtaken (see the
     * class comment). Failures from [fetch] propagate and leave the cache untouched.
     */
    internal suspend fun <T : Any> get(
        key: CacheKey,
        maxAge: Duration,
        refresh: Boolean,
        fetch: suspend () -> T,
    ): T {
        val start = state.value
        val version = start.versionOf(key)
        if (!refresh) {
            val entry = start.entries[key]
            if (entry != null && entry.version >= version && entry.fetchedAt.elapsedNow() < maxAge) {
                @Suppress("UNCHECKED_CAST")
                return entry.value as T
            }
        }
        val written = start.writtenAt[key] ?: 0L
        val value = fetch()
        state.update { s ->
            if (s.versionOf(key) != version || (s.writtenAt[key] ?: 0L) != written) {
                s
            } else {
                s.copy(entries = s.entries + (key to Entry(value, timeSource.markNow(), version)))
            }
        }
        return value
    }

    /**
     * Optimistically rewrites a cached value (RN `setQueryData`), e.g. a session shown as saved the
     * moment it is tapped. Does nothing when nothing is cached. Fetches already in flight for
     * [key] won't overwrite it (RN cancelled them first); this does NOT fire [changes], since
     * refetching now would only bring back the state from before the write.
     */
    internal fun <T : Any> update(key: CacheKey, transform: (T) -> T) {
        state.update { s ->
            val entry = s.entries[key] ?: return@update s
            @Suppress("UNCHECKED_CAST")
            val next = transform(entry.value as T)
            val serial = s.serial + 1
            s.copy(
                entries = s.entries + (key to Entry(next, entry.fetchedAt, entry.version)),
                writtenAt = s.writtenAt + (key to serial),
                serial = serial,
            )
        }
    }

    /** Marks every key starting with any of [prefixes] stale and fires their [changes]. */
    fun invalidate(vararg prefixes: CacheKey) {
        if (prefixes.isEmpty()) return
        state.update { s ->
            var serial = s.serial
            val invalidated = s.invalidatedAt.toMutableMap()
            prefixes.forEach { invalidated[it.path] = ++serial }
            s.copy(invalidatedAt = invalidated, serial = serial)
        }
    }

    /** As [invalidate], for a list. */
    fun invalidate(prefixes: Collection<CacheKey>) = invalidate(*prefixes.toTypedArray())

    /** Forgets every cached value and fires every [changes]. */
    fun clear() {
        state.update { s ->
            val serial = s.serial + 1
            State(invalidatedAt = mapOf(emptyList<Any?>() to serial), serial = serial)
        }
    }

    /** The cached value for [key], fresh or stale, or null. */
    fun <T : Any> peek(key: CacheKey): T? {
        @Suppress("UNCHECKED_CAST")
        return state.value.entries[key]?.value as T?
    }

    /** The cached value for [key] as it changes (fetches, optimistic updates, [clear]). */
    fun <T : Any> data(key: CacheKey): Flow<T?> =
        state.map {
            @Suppress("UNCHECKED_CAST")
            it.entries[key]?.value as T?
        }.distinctUntilChanged()

    /**
     * Emits each time [key] must be refetched: it (or a prefix of it) was invalidated, or the
     * cache was cleared. Conflated: several invalidations in a row may arrive as one signal,
     * which is all a refetch needs.
     */
    fun changes(key: CacheKey): Flow<Unit> =
        state.map { it.versionOf(key) }.distinctUntilChanged().drop(1).map { }
}

/**
 * One cached endpoint: what a screen holds to load, observe and refresh a response. Created by
 * the repositories.
 */
class CachedResource<T : Any> internal constructor(
    private val cache: ResponseCache,
    val key: CacheKey,
    private val maxAge: Duration,
    private val fetch: suspend () -> T,
) {
    /** The response: from memory while fresh, else from the API. [refresh] always fetches. */
    suspend fun get(refresh: Boolean = false): T = cache.get(key, maxAge, refresh, fetch)

    /** The last value held, null until the first fetch and after a sign-out wipe. */
    val data: Flow<T?> get() = cache.data(key)

    /** The refetch signal: emits when this response is out of date (see [ResponseCache.changes]). */
    val changes: Flow<Unit> get() = cache.changes(key)

    /** The last value held, without fetching. */
    fun peek(): T? = cache.peek(key)

    /** Marks it stale, so the next [get] fetches and [changes] fires. */
    fun invalidate() = cache.invalidate(key)
}
