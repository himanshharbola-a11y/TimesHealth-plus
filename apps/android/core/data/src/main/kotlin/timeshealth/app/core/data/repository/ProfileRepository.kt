package timeshealth.app.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import timeshealth.app.core.data.cache.CacheKeys
import timeshealth.app.core.data.cache.CachedResource
import timeshealth.app.core.data.cache.ResponseCache
import timeshealth.app.core.data.session.SessionRepository
import timeshealth.app.core.model.AppConfigResponse
import timeshealth.app.core.model.DeleteAccountResponse
import timeshealth.app.core.model.OnboardingStepRequest
import timeshealth.app.core.model.ProfileResponse
import timeshealth.app.core.model.SessionResponse
import timeshealth.app.core.model.UpdateProfileRequest
import timeshealth.app.core.network.TimesHealthApi

/** Launch config, the session (profile, entitlements, persona), onboarding, profile edits, account deletion. */
@Singleton
class ProfileRepository @Inject constructor(
    private val api: TimesHealthApi,
    private val cache: ResponseCache,
    private val sessionRepository: SessionRepository,
) {

    /** GET /config: the force-update gate and maintenance switch, checked on every launch BEFORE sign-in. */
    val config: CachedResource<AppConfigResponse> =
        CachedResource(cache, CacheKeys.AppConfig, 5.minutes) { api.config() }

    /**
     * GET /session. Fetch it only once [SessionRepository.status] has left Loading, so it never
     * asks before the identity provider has restored the saved login.
     */
    val session: CachedResource<SessionResponse> =
        CachedResource(cache, CacheKeys.Session, 60.seconds) { api.session() }

    /** One onboarding step (§5). Progress is saved as it goes; step 4 completes onboarding. */
    suspend fun onboardingStep(body: OnboardingStepRequest): ProfileResponse =
        api.onboardingStep(body).also {
            // The goal and focus area chosen here order the Home rails.
            cache.invalidate(CacheKeys.Session, CacheKeys.Home)
        }

    suspend fun skipOnboarding(): ProfileResponse =
        api.skipOnboarding().also { cache.invalidate(CacheKeys.Session) }

    /** PATCH /profile (§10). Only non-null fields are sent. */
    suspend fun updateProfile(body: UpdateProfileRequest): ProfileResponse =
        api.updateProfile(body).also {
            cache.invalidate(CacheKeys.Session)
            // A new goal or focus area re-orders the Home rails (§6.3).
            if (body.healthGoal != null || body.concern != null) cache.invalidate(CacheKeys.Home)
        }

    /**
     * Permanently deletes the account (DPDP, Play policy), then signs out.
     *
     * @throws timeshealth.app.core.network.ApiRequestException when the deletion failed: nothing
     *   has been deleted and the user stays signed in ("check your connection and try again").
     */
    suspend fun deleteAccount(): DeleteAccountResponse {
        val result = api.deleteAccount()
        // The server has already erased the account and its device registrations.
        sessionRepository.signOut(accountDeleted = true)
        return result
    }
}
