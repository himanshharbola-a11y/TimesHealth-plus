package timeshealth.app.core.data

import kotlin.time.Duration.Companion.hours
import timeshealth.app.core.data.cache.CacheKey
import timeshealth.app.core.data.cache.ResponseCache

/** Runs [block] and returns the [T] it threw; fails the test if it threw nothing or something else. */
suspend inline fun <reified T : Throwable> expectThrows(block: suspend () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("Expected ${T::class.simpleName} but got $e", e)
    }
    throw AssertionError("Expected ${T::class.simpleName} but nothing was thrown")
}

/** Puts "old" under [key], as if a screen had loaded it. */
suspend fun ResponseCache.seed(key: CacheKey) {
    get(key, 1.hours, refresh = false) { "old" }
}

/**
 * True when [key] was invalidated (or cleared) since [seed]: a long-lived entry is then refetched
 * instead of being served from memory.
 */
suspend fun ResponseCache.wasInvalidated(key: CacheKey): Boolean =
    get(key, 1.hours, refresh = false) { "refetched" } == "refetched"
