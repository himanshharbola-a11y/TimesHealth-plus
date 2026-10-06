package timeshealth.app.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import timeshealth.app.core.data.cache.CacheKeys
import timeshealth.app.core.data.cache.CachedResource
import timeshealth.app.core.data.cache.ResponseCache
import timeshealth.app.core.model.RunHistoryResponse
import timeshealth.app.core.model.UploadRunRequest
import timeshealth.app.core.model.UploadRunResponse
import timeshealth.app.core.network.TimesHealthApi

/** Run history and uploads (PRD §8.6). Recording itself lives in :core:runtracker. */
@Singleton
class RunsRepository @Inject constructor(
    private val api: TimesHealthApi,
    private val cache: ResponseCache,
) {

    /** GET /runs: the latest 100 runs (without routes) and lifetime totals. */
    val history: CachedResource<RunHistoryResponse> =
        CachedResource(cache, CacheKeys.Runs, Duration.ZERO) { api.runHistory() }

    /**
     * Uploads a recorded run. The client owns the id, so a retry is a no-op on the server: safe
     * for the run tracker's upload worker to repeat after a lost response. Throws on any failure,
     * so the run stays unsynced and is retried.
     */
    suspend fun upload(request: UploadRunRequest): UploadRunResponse =
        api.uploadRun(request).also { cache.invalidate(CacheKeys.Runs) }
}
