package timeshealth.server.cms

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Duration
import org.springframework.stereotype.Component

/**
 * Port of apps/api/src/services/contentCache.ts: a process-local read-through cache for content
 * that is identical for every user (sections, batches, rails, articles, promos, live classes).
 *
 * - Home's load spike is the minutes before each daily batch, when thousands open the app at once;
 *   without this each request re-reads the same global rows.
 * - Concurrent misses share ONE load (Caffeine computes once per key), and a failed load is not
 *   cached, so the next request retries.
 * - An admin edit calls [invalidateAll], so it shows on this instance at once; other instances
 *   pick it up within [TTL].
 *
 * Per-user data (entitlements, attendance, registrations) never goes here.
 */
@Component
class ContentCache {
    private val cache: Cache<String, Any> = Caffeine.newBuilder()
        .expireAfterWrite(TTL)
        .maximumSize(1_000)
        .build()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String, load: () -> T): T = cache.get(key) { load() } as T

    fun invalidateAll() = cache.invalidateAll()

    companion object {
        val TTL: Duration = Duration.ofSeconds(60)
    }
}
