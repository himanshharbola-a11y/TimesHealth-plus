package timeshealth.app.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import timeshealth.app.core.data.cache.CacheKeys
import timeshealth.app.core.data.cache.CachedResource
import timeshealth.app.core.data.cache.ResponseCache
import timeshealth.app.core.model.SetRegisteredRequest
import timeshealth.app.core.model.WorkshopListResponse
import timeshealth.app.core.model.WorkshopRegistrationResponse
import timeshealth.app.core.network.TimesHealthApi

/** Live workshops. Paid seats go through checkout; free ones are booked here. */
@Singleton
class WorkshopsRepository @Inject constructor(
    private val api: TimesHealthApi,
    private val cache: ResponseCache,
) {

    /** GET /workshops, with live seat counts. */
    val workshops: CachedResource<WorkshopListResponse> =
        CachedResource(cache, CacheKeys.Workshops, 60.seconds) { api.workshops() }

    /**
     * Sets a free seat to the state the user WANTS. Idempotent, so a double tap or a retry can't
     * cancel the booking it just made.
     */
    suspend fun setWorkshopRegistration(workshopId: String, registered: Boolean): WorkshopRegistrationResponse =
        api.setWorkshopRegistration(workshopId, SetRegisteredRequest(registered)).also {
            // The same workshops render on Home (via the feed) and in the tabs.
            cache.invalidate(CacheKeys.Workshops, CacheKeys.Home)
        }
}
