package timeshealth.app.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import timeshealth.app.core.data.cache.CacheKeys
import timeshealth.app.core.data.cache.CachedResource
import timeshealth.app.core.data.cache.ResponseCache
import timeshealth.app.core.data.pass.OfflinePass
import timeshealth.app.core.data.pass.OfflinePassStore
import timeshealth.app.core.data.quietly
import timeshealth.app.core.model.ApplyReferralRequest
import timeshealth.app.core.model.ApplyReferralResponse
import timeshealth.app.core.model.BibTokenResponse
import timeshealth.app.core.model.ClaimUpgradeResponse
import timeshealth.app.core.model.MarathonListResponse
import timeshealth.app.core.model.OkResponse
import timeshealth.app.core.model.RaceDetailResponse
import timeshealth.app.core.model.ReferralState
import timeshealth.app.core.model.UpdateParticipantRequest
import timeshealth.app.core.model.toBody
import timeshealth.app.core.network.TimesHealthApi

/** Races (PRD §8): the list, race pages with the offline pass, the bib QR, Refer & Win. */
@Singleton
class MarathonRepository @Inject constructor(
    private val api: TimesHealthApi,
    private val cache: ResponseCache,
    private val offlinePasses: OfflinePassStore,
) {

    /**
     * The Races tab, ordered by the server (§8.2). Pass coordinates only if location permission is
     * already granted; the server falls back silently, so the user is never asked twice. Each
     * coordinate pair is its own entry: keep showing the date-ordered list until the nearest-first
     * one lands, rather than blanking the tab.
     */
    fun events(lat: Double? = null, lng: Double? = null): CachedResource<MarathonListResponse> =
        CachedResource(cache, CacheKeys.marathonEvents(lat, lng), 5.minutes) { api.marathonEvents(lat, lng) }

    /**
     * A race page (§8.3). Every successful load also keeps the race pass on the phone for the
     * stadium gate, where signal fails, and drops it if the server no longer issues one.
     */
    fun raceDetail(eventId: String): CachedResource<RaceDetailResponse> =
        CachedResource(cache, CacheKeys.raceDetail(eventId), Duration.ZERO) { fetchRaceDetail(eventId) }

    /** Refer & Win (§8.3). The server issues the coded link on first request. */
    val referral: CachedResource<ReferralState> =
        CachedResource(cache, CacheKeys.Referral, 5.minutes) { api.referral() }

    /** The saved pass for [eventId], if still inside its signature window. Works offline. */
    suspend fun offlinePass(eventId: String): OfflinePass? = offlinePasses.load(eventId)

    /** Every saved pass still inside its signature window, in save order. Works offline. */
    suspend fun offlinePasses(): List<OfflinePass> = offlinePasses.list()

    /**
     * A fresh short-lived QR token (docs/04 T1). Never cached: refetch it (about every 45 s)
     * while the bib is on screen.
     */
    suspend fun bibToken(eventId: String): BibTokenResponse = api.bibToken(eventId)

    /** T-shirt size and emergency contact (§8.3). */
    suspend fun updateParticipant(request: UpdateParticipantRequest): OkResponse =
        api.updateParticipant(request.eventId, request.toBody()).also {
            cache.invalidate(CacheKeys.raceDetail(request.eventId))
        }

    /** Records the friend's code this user came with (§8.3). */
    suspend fun applyReferralCode(code: String): ApplyReferralResponse =
        api.applyReferralCode(ApplyReferralRequest(code)).also { cache.invalidate(CacheKeys.Session) }

    /** Claims the free Premium upgrade earned with 5 referrals (§8.3). */
    suspend fun claimUpgrade(eventId: String): ClaimUpgradeResponse =
        api.claimUpgrade(eventId).also {
            cache.invalidate(CacheKeys.Marathon, CacheKeys.Session, CacheKeys.Home)
        }

    private suspend fun fetchRaceDetail(eventId: String): RaceDetailResponse {
        // Read BEFORE the request: if the user signs out while it is in flight, the response
        // must not write their pass back for the next person on the phone.
        val generation = offlinePasses.generation()
        val detail = api.raceDetail(eventId)
        // Best effort: the page has loaded either way.
        quietly {
            val bib = detail.bib
            if (bib != null) {
                offlinePasses.save(eventId, bib, generation, detail.event)
            } else {
                offlinePasses.remove(eventId, generation)
            }
        }
        return detail
    }
}
