package timeshealth.app.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import timeshealth.app.core.data.cache.CacheKeys
import timeshealth.app.core.data.cache.CachedResource
import timeshealth.app.core.data.cache.ResponseCache
import timeshealth.app.core.model.HomeFeedResponse
import timeshealth.app.core.network.TimesHealthApi

/**
 * The server-driven Home feed (PRD §6).
 *
 * Refetched (its [CachedResource.changes] fires) after a purchase is granted, a profile goal or
 * focus change, onboarding, a class join, a workshop booking and an upgrade claim: each of those
 * re-orders or re-fills the rails.
 */
@Singleton
class HomeRepository @Inject constructor(api: TimesHealthApi, cache: ResponseCache) {

    /**
     * GET /home. Home changes as batches start, so the cached copy is short-lived, but it is never
     * polled: countdowns tick locally from `serverTime` (the network layer syncs ServerClock).
     */
    val home: CachedResource<HomeFeedResponse> = CachedResource(cache, CacheKeys.Home, 30.seconds) { api.home() }
}
