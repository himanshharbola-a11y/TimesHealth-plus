package timeshealth.app.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import timeshealth.app.core.data.cache.CacheKeys
import timeshealth.app.core.data.cache.CachedResource
import timeshealth.app.core.data.cache.ResponseCache
import timeshealth.app.core.model.ContentResponse
import timeshealth.app.core.network.TimesHealthApi

/** Articles, quotes, instructors, FAQs, the mentor and reels. Changes rarely. */
@Singleton
class ContentRepository @Inject constructor(api: TimesHealthApi, cache: ResponseCache) {

    val content: CachedResource<ContentResponse> =
        CachedResource(cache, CacheKeys.Content, 10.minutes) { api.content() }
}
