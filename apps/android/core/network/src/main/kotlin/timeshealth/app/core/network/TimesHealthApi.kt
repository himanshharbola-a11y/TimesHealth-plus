package timeshealth.app.core.network

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import timeshealth.app.core.model.AppConfigResponse
import timeshealth.app.core.model.ApplyReferralRequest
import timeshealth.app.core.model.ApplyReferralResponse
import timeshealth.app.core.model.BibTokenResponse
import timeshealth.app.core.model.ClaimUpgradeResponse
import timeshealth.app.core.model.CompletedResponse
import timeshealth.app.core.model.ContentResponse
import timeshealth.app.core.model.CreateOrderRequest
import timeshealth.app.core.model.CreateOrderResponse
import timeshealth.app.core.model.DeleteAccountResponse
import timeshealth.app.core.model.DietLeadRequest
import timeshealth.app.core.model.DietLeadResponse
import timeshealth.app.core.model.HomeFeedResponse
import timeshealth.app.core.model.JoinSessionRequest
import timeshealth.app.core.model.JoinSessionResponse
import timeshealth.app.core.model.MarathonListResponse
import timeshealth.app.core.model.MySessionsResponse
import timeshealth.app.core.model.NotificationListResponse
import timeshealth.app.core.model.OkResponse
import timeshealth.app.core.model.OnboardingStepRequest
import timeshealth.app.core.model.OrderStatusResponse
import timeshealth.app.core.model.PlaybackResponse
import timeshealth.app.core.model.ProfileResponse
import timeshealth.app.core.model.RaceDetailResponse
import timeshealth.app.core.model.ReferralState
import timeshealth.app.core.model.RegisterPushTokenRequest
import timeshealth.app.core.model.RegisterPushTokenResponse
import timeshealth.app.core.model.RemovePushTokenRequest
import timeshealth.app.core.model.RemovePushTokenResponse
import timeshealth.app.core.model.RunHistoryResponse
import timeshealth.app.core.model.SavedResponse
import timeshealth.app.core.model.SessionResponse
import timeshealth.app.core.model.SetCompletedRequest
import timeshealth.app.core.model.SetRegisteredRequest
import timeshealth.app.core.model.SetReminderSlotRequest
import timeshealth.app.core.model.SetSavedRequest
import timeshealth.app.core.model.SimulatePaymentResponse
import timeshealth.app.core.model.UpdateParticipantBody
import timeshealth.app.core.model.UpdateProfileRequest
import timeshealth.app.core.model.UploadRunRequest
import timeshealth.app.core.model.UploadRunResponse
import timeshealth.app.core.model.WorkshopListResponse
import timeshealth.app.core.model.WorkshopRegistrationResponse
import timeshealth.app.core.model.YogaAttendance
import timeshealth.app.core.model.YogaCatalogResponse
import timeshealth.app.core.model.YogaTodayResponse

/**
 * Every TimesHealth+ API endpoint the app calls. Paths, methods and error codes are taken from
 * the server routes (apps/api/src/routes/), all served under the `/v1` base URL.
 *
 * Paths are RELATIVE (no leading `/`) so they resolve under the base URL's `/v1`; a leading
 * slash would resolve against the host root and drop it.
 *
 * Every method throws [ApiRequestException] and nothing else for an unsuccessful call (see
 * there for the status and code rules). The codes listed per method are the ones the route can
 * send besides 401 (session ended, see [AuthInterceptor]) and 503 UNAVAILABLE (retry; never a
 * sign-out). Ids in `@Path` are percent-encoded by Retrofit, so an id can never change the path.
 *
 * Build it with [NetworkFactory].
 */
interface TimesHealthApi {

    // ── Session / bootstrap (session.ts) ────────────────────────────────────

    /**
     * Force-update gate and maintenance switch, checked on every launch BEFORE sign-in.
     * [Anonymous]: it must work even when an old build's login is what broke. A short deadline
     * (RN's 8 s) because launch waits on it. Syncs [ServerClock].
     */
    @Anonymous
    @CallTimeout(millis = 8_000)
    @GET("config")
    suspend fun config(): AppConfigResponse

    /**
     * Resolves identity, entitlements and the first screen. Call only once the identity
     * provider has restored the saved login; see [AuthInterceptor] for why. Syncs [ServerClock].
     */
    @GET("session")
    suspend fun session(): SessionResponse

    /**
     * One onboarding step (§5): progress is saved as it goes, every step is skippable, and step
     * 4 completes onboarding. 400 INVALID_BODY / INVALID_PHONE.
     */
    @POST("onboarding/step")
    suspend fun onboardingStep(@Body body: OnboardingStepRequest): ProfileResponse

    /** Skips onboarding entirely. Never refused. */
    @POST("onboarding/skip")
    suspend fun skipOnboarding(): ProfileResponse

    /**
     * Personal details (§10). Only non-null fields are sent. 400 INVALID_BODY / INVALID_PHONE;
     * 409 LOGIN_IDENTIFIER when changing the email or phone the user signs in with.
     */
    @PATCH("profile")
    suspend fun updateProfile(@Body body: UpdateProfileRequest): ProfileResponse

    /** Permanently deletes the account and its identity-provider record (DPDP, Play policy). */
    @DELETE("account")
    suspend fun deleteAccount(): DeleteAccountResponse

    /** The server-driven Home feed. Syncs [ServerClock]. */
    @GET("home")
    suspend fun home(): HomeFeedResponse

    // ── Yoga (yoga.ts) ──────────────────────────────────────────────────────

    /** All 8 daily batches with the user's reminder slot flagged (§7.1). */
    @GET("yoga/today")
    suspend fun yogaToday(): YogaTodayResponse

    /**
     * Joins a live class. The server writes the attendance mark itself (docs/04 T9); a retry is
     * safe because the ledger keeps one mark per IST day. 403 NOT_ENTITLED, 404 NOT_FOUND,
     * 409 CLASS_NOT_OPEN (outside the join window), 400 INVALID_BODY.
     */
    @POST("yoga/join")
    suspend fun joinSession(@Body body: JoinSessionRequest): JoinSessionResponse

    /** The attendance tracker (§7.1). 403 NOT_ENTITLED for non-subscribers. */
    @GET("yoga/attendance")
    suspend fun yogaAttendance(): YogaAttendance

    /** Sets the batch reminders fire for. 403 NOT_ENTITLED, 404 NOT_FOUND (unknown batch). */
    @PUT("yoga/reminder-slot")
    suspend fun setReminderSlot(@Body body: SetReminderSlotRequest): OkResponse

    /** Categories plus the full session library. */
    @GET("yoga/catalog")
    suspend fun yogaCatalog(): YogaCatalogResponse

    /**
     * A FRESH signed playback URL, fetched when the player opens: catalogue URLs expire within
     * minutes. 403 NOT_ENTITLED for a locked session, 404 NOT_FOUND when there is no video.
     */
    @GET("yoga/sessions/{id}/playback")
    suspend fun playback(@Path("id") sessionId: String): PlaybackResponse

    /** Saves or unsaves a session: the WANTED state, never a toggle. 404 for an unknown session. */
    @POST("yoga/sessions/{id}/save")
    suspend fun setSaved(@Path("id") sessionId: String, @Body body: SetSavedRequest): SavedResponse

    /** Marks a recording completed or not: the WANTED state. Does not count as attendance. */
    @POST("yoga/sessions/{id}/complete")
    suspend fun setCompleted(@Path("id") sessionId: String, @Body body: SetCompletedRequest): CompletedResponse

    /** The caller's saved and completed session ids. */
    @GET("yoga/me/sessions")
    suspend fun mySessions(): MySessionsResponse

    // ── Marathon (marathon.ts) ──────────────────────────────────────────────

    /**
     * The Races tab, ordered by the server (§8.2). Send coordinates only if the app already has
     * location permission; the server falls back silently, so the user is never asked twice.
     * Null values are left out of the query; the server uses them only when both are present.
     */
    @GET("marathon/events")
    suspend fun marathonEvents(
        @Query("lat") lat: Double? = null,
        @Query("lng") lng: Double? = null,
    ): MarathonListResponse

    /** Race detail (§8.3). 404 NOT_FOUND. */
    @GET("marathon/events/{id}")
    suspend fun raceDetail(@Path("id") eventId: String): RaceDetailResponse

    /**
     * A fresh short-lived QR token for the bib (docs/04 T1). Refetch while the bib is on
     * screen; never cache it. 404 NOT_FOUND when the user has no bib for this event.
     */
    @GET("marathon/events/{id}/bib-token")
    suspend fun bibToken(@Path("id") eventId: String): BibTokenResponse

    /**
     * T-shirt size and emergency contact (§8.3). Build the body with
     * `UpdateParticipantRequest.toBody()`. 404 NOT_FOUND (not registered), 409 RACE_STARTED,
     * 400 INVALID_BODY / INVALID_PHONE.
     */
    @PATCH("marathon/events/{id}/participant")
    suspend fun updateParticipant(@Path("id") eventId: String, @Body body: UpdateParticipantBody): OkResponse

    /** Refer & Win state (§8.3). The server issues the code on first request. */
    @GET("marathon/referral")
    suspend fun referral(): ReferralState

    /**
     * Records the friend's code this user came with (attribution only; the friend is credited
     * on this user's first PAID registration). 404 INVALID_REFERRAL_CODE, 409 OWN_REFERRAL_CODE.
     */
    @POST("marathon/referral/apply")
    suspend fun applyReferralCode(@Body body: ApplyReferralRequest): ApplyReferralResponse

    /**
     * Claims the free Premium upgrade earned with 5 referrals. Atomic and once only.
     * 404 NOT_REGISTERED, 409 ALREADY_PREMIUM / REGISTRATION_CLOSED / NO_FREE_UPGRADE.
     */
    @POST("marathon/events/{id}/claim-upgrade")
    suspend fun claimUpgrade(@Path("id") eventId: String): ClaimUpgradeResponse

    // ── Orders (orders.ts) ──────────────────────────────────────────────────

    /**
     * Creates (or, within 30 minutes, reuses) an unpaid order. The server prices it; the client
     * never sends a price (docs/04 T8). 400 INVALID_BODY; 409 with the refusal reason as the
     * code (sold out, registration closed, already registered, INVALID_REFERRAL_CODE, ...).
     */
    @POST("orders")
    suspend fun createOrder(@Body body: CreateOrderRequest): CreateOrderResponse

    /**
     * The order's status. Entitlement is granted only by the payment webhook, so poll this;
     * never treat the gateway's client-side success as the outcome. 404 NOT_FOUND.
     */
    @GET("orders/{id}")
    suspend fun orderStatus(@Path("id") orderId: String): OrderStatusResponse

    /** STUB gateway only: settles the order as the webhook would. 404 in production. */
    @POST("orders/{id}/simulate-payment")
    suspend fun simulatePayment(@Path("id") orderId: String): SimulatePaymentResponse

    // ── Runs (runs.ts) ──────────────────────────────────────────────────────

    /**
     * Uploads a run recorded on the device. The client owns the id (a UUID), so a retry is a
     * no-op on the server. 400 INVALID_BODY with per-field `fields`; 409 ID_CONFLICT.
     */
    @POST("runs")
    suspend fun uploadRun(@Body body: UploadRunRequest): UploadRunResponse

    /** Run history (latest 100, without routes) and lifetime totals. */
    @GET("runs")
    suspend fun runHistory(): RunHistoryResponse

    // ── Diet, workshops, content (diet.ts, content.ts) ──────────────────────

    /**
     * A dietitian call-back lead (§9). 400 INVALID_BODY with per-field `fields`,
     * 400 INVALID_PHONE, 429 TOO_MANY_REQUESTS (3 per day).
     */
    @POST("diet/leads")
    suspend fun submitDietLead(@Body body: DietLeadRequest): DietLeadResponse

    /** Live workshops with live seat counts. */
    @GET("workshops")
    suspend fun workshops(): WorkshopListResponse

    /**
     * Sets a free seat to the state wanted — register or cancel — idempotently: repeating the
     * call changes nothing. Paid seats go through [createOrder].
     * 402 PAYMENT_REQUIRED, 409 PAID_SEAT / WORKSHOP_FULL, 404 NOT_FOUND, 400 INVALID_BODY.
     */
    @POST("workshops/{id}/register")
    suspend fun setWorkshopRegistration(
        @Path("id") workshopId: String,
        @Body body: SetRegisteredRequest,
    ): WorkshopRegistrationResponse

    /** Articles, quotes, instructors, FAQs, mentor and reels. */
    @GET("content")
    suspend fun content(): ContentResponse

    // ── Devices and notifications (devices.ts) ──────────────────────────────

    /** Registers this device's push token for the signed-in user (§11). 400 INVALID_BODY. */
    @POST("devices/push-token")
    suspend fun registerPushToken(@Body body: RegisterPushTokenRequest): RegisterPushTokenResponse

    /** Unregisters a push token on sign-out, so a shared phone stops getting this user's alerts. */
    @POST("devices/push-token/remove")
    suspend fun removePushToken(@Body body: RemovePushTokenRequest): RemovePushTokenResponse

    /** The bell inbox: pushes sent to this user in the last 30 days, newest first. */
    @GET("notifications")
    suspend fun notifications(): NotificationListResponse
}
