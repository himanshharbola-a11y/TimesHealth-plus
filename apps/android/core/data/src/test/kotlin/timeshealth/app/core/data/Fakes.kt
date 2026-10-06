package timeshealth.app.core.data

import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import timeshealth.app.core.data.security.SecureStore
import timeshealth.app.core.model.ApiError
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
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.core.network.TimesHealthApi

/** One ordered record of what every fake saw, so tests can assert on sequence across fakes. */
class EventLog {
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun add(event: String) {
        events += event
    }

    fun indexOf(event: String): Int = events.indexOf(event).also {
        check(it >= 0) { "No \"$event\" in $events" }
    }

    fun count(event: String): Int = events.count { it == event }

    fun clear() = events.clear()
}

/** In-memory [SecureStore] that logs writes and can be made to fail or pause. */
class FakeSecureStore(private val log: EventLog = EventLog()) : SecureStore {
    val values: MutableMap<String, String> = Collections.synchronizedMap(linkedMapOf())

    /** When true, every read fails (a broken Keystore). Reads must still come back null. */
    var failReads = false

    /** When true, every write throws. */
    var failWrites = false

    /** When set, the next put for this key waits until the deferred completes. */
    var pausePut: Pair<String, CompletableDeferred<Unit>>? = null

    override suspend fun get(key: String): String? {
        if (failReads) return null
        return values[key]
    }

    override suspend fun put(key: String, value: String) {
        pausePut?.let { (k, gate) ->
            if (k == key) {
                pausePut = null
                gate.await()
            }
        }
        if (failWrites) throw IOException("disk full")
        log.add("store.put $key")
        values[key] = value
    }

    override suspend fun remove(vararg keys: String) {
        if (failWrites) throw IOException("disk full")
        keys.forEach {
            log.add("store.remove $it")
            values.remove(it)
        }
    }
}

/** An [IdentityGateway] whose user the test controls. */
class FakeIdentityGateway(
    private val log: EventLog = EventLog(),
    initialUid: String? = null,
) : IdentityGateway {
    val uid = MutableStateFlow(initialUid)
    var idTokenFor: (String) -> String? = { "id-token-$it" }
    var failIdToken = false

    override fun currentUserId(): String? = uid.value

    override suspend fun idToken(forceRefresh: Boolean): String? {
        if (failIdToken) throw IllegalStateException("provider down")
        return uid.value?.let(idTokenFor)
    }

    override suspend fun signOut() {
        log.add("identity.signOut")
        uid.value = null
    }

    override val authState: Flow<String?> get() = uid
}

fun apiError(status: Int, code: String) = ApiRequestException(status, ApiError(code, "refused: $code"))

val networkDown get() = ApiRequestException(0, ApiError("NETWORK", "No connection."))

private fun unexpected(name: String): Nothing = throw AssertionError("Unexpected API call: $name")

/**
 * [TimesHealthApi] with one replaceable handler per endpoint (all fail by default) and a log of
 * the calls made, as "api.<method> <argument>".
 */
class FakeTimesHealthApi(private val log: EventLog = EventLog()) : TimesHealthApi {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun callsTo(method: String): List<String> = calls.filter { it == method || it.startsWith("$method ") }

    private suspend fun <T> record(call: String, handler: suspend () -> T): T {
        calls += call
        log.add("api.$call")
        return handler()
    }

    var onConfig: suspend () -> AppConfigResponse = { unexpected("config") }
    var onSession: suspend () -> SessionResponse = { unexpected("session") }
    var onOnboardingStep: suspend (OnboardingStepRequest) -> ProfileResponse = { unexpected("onboardingStep") }
    var onSkipOnboarding: suspend () -> ProfileResponse = { unexpected("skipOnboarding") }
    var onUpdateProfile: suspend (UpdateProfileRequest) -> ProfileResponse = { unexpected("updateProfile") }
    var onDeleteAccount: suspend () -> DeleteAccountResponse = { unexpected("deleteAccount") }
    var onHome: suspend () -> HomeFeedResponse = { unexpected("home") }
    var onYogaToday: suspend () -> YogaTodayResponse = { unexpected("yogaToday") }
    var onJoinSession: suspend (JoinSessionRequest) -> JoinSessionResponse = { unexpected("joinSession") }
    var onYogaAttendance: suspend () -> YogaAttendance = { unexpected("yogaAttendance") }
    var onSetReminderSlot: suspend (SetReminderSlotRequest) -> OkResponse = { unexpected("setReminderSlot") }
    var onYogaCatalog: suspend () -> YogaCatalogResponse = { unexpected("yogaCatalog") }
    var onPlayback: suspend (String) -> PlaybackResponse = { unexpected("playback") }
    var onSetSaved: suspend (String, SetSavedRequest) -> SavedResponse = { _, _ -> unexpected("setSaved") }
    var onSetCompleted: suspend (String, SetCompletedRequest) -> CompletedResponse = { _, _ -> unexpected("setCompleted") }
    var onMySessions: suspend () -> MySessionsResponse = { unexpected("mySessions") }
    var onMarathonEvents: suspend (Double?, Double?) -> MarathonListResponse = { _, _ -> unexpected("marathonEvents") }
    var onRaceDetail: suspend (String) -> RaceDetailResponse = { unexpected("raceDetail") }
    var onBibToken: suspend (String) -> BibTokenResponse = { unexpected("bibToken") }
    var onUpdateParticipant: suspend (String, UpdateParticipantBody) -> OkResponse = { _, _ -> unexpected("updateParticipant") }
    var onReferral: suspend () -> ReferralState = { unexpected("referral") }
    var onApplyReferralCode: suspend (ApplyReferralRequest) -> ApplyReferralResponse = { unexpected("applyReferralCode") }
    var onClaimUpgrade: suspend (String) -> ClaimUpgradeResponse = { unexpected("claimUpgrade") }
    var onCreateOrder: suspend (CreateOrderRequest) -> CreateOrderResponse = { unexpected("createOrder") }
    var onOrderStatus: suspend (String) -> OrderStatusResponse = { unexpected("orderStatus") }
    var onSimulatePayment: suspend (String) -> SimulatePaymentResponse = { unexpected("simulatePayment") }
    var onUploadRun: suspend (UploadRunRequest) -> UploadRunResponse = { unexpected("uploadRun") }
    var onRunHistory: suspend () -> RunHistoryResponse = { unexpected("runHistory") }
    var onSubmitDietLead: suspend (DietLeadRequest) -> DietLeadResponse = { unexpected("submitDietLead") }
    var onWorkshops: suspend () -> WorkshopListResponse = { unexpected("workshops") }
    var onSetWorkshopRegistration: suspend (String, SetRegisteredRequest) -> WorkshopRegistrationResponse =
        { _, _ -> unexpected("setWorkshopRegistration") }
    var onContent: suspend () -> ContentResponse = { unexpected("content") }
    var onRegisterPushToken: suspend (RegisterPushTokenRequest) -> RegisterPushTokenResponse =
        { RegisterPushTokenResponse(registered = true) }
    var onRemovePushToken: suspend (RemovePushTokenRequest) -> RemovePushTokenResponse =
        { RemovePushTokenResponse(removed = true) }
    var onNotifications: suspend () -> NotificationListResponse = { unexpected("notifications") }
    var onLiveClasses: suspend () -> timeshealth.app.core.model.LiveClassListResponse = { unexpected("liveClasses") }
    var onJoinLiveClass: suspend (String) -> timeshealth.app.core.model.LiveClassJoinResponse = { unexpected("joinLiveClass") }

    override suspend fun config() = record("config") { onConfig() }
    override suspend fun session() = record("session") { onSession() }
    override suspend fun onboardingStep(body: OnboardingStepRequest) = record("onboardingStep") { onOnboardingStep(body) }
    override suspend fun skipOnboarding() = record("skipOnboarding") { onSkipOnboarding() }
    override suspend fun updateProfile(body: UpdateProfileRequest) = record("updateProfile") { onUpdateProfile(body) }
    override suspend fun deleteAccount() = record("deleteAccount") { onDeleteAccount() }
    override suspend fun home() = record("home") { onHome() }
    override suspend fun yogaToday() = record("yogaToday") { onYogaToday() }
    override suspend fun liveClasses() = record("liveClasses") { onLiveClasses() }
    override suspend fun joinLiveClass(liveClassId: String) = record("joinLiveClass $liveClassId") { onJoinLiveClass(liveClassId) }
    override suspend fun joinSession(body: JoinSessionRequest) = record("joinSession ${body.batchId}") { onJoinSession(body) }
    override suspend fun yogaAttendance() = record("yogaAttendance") { onYogaAttendance() }
    override suspend fun setReminderSlot(body: SetReminderSlotRequest) = record("setReminderSlot ${body.batchId}") { onSetReminderSlot(body) }
    override suspend fun yogaCatalog() = record("yogaCatalog") { onYogaCatalog() }
    override suspend fun playback(sessionId: String) = record("playback $sessionId") { onPlayback(sessionId) }
    override suspend fun setSaved(sessionId: String, body: SetSavedRequest) =
        record("setSaved $sessionId ${body.saved}") { onSetSaved(sessionId, body) }
    override suspend fun setCompleted(sessionId: String, body: SetCompletedRequest) =
        record("setCompleted $sessionId ${body.completed}") { onSetCompleted(sessionId, body) }
    override suspend fun mySessions() = record("mySessions") { onMySessions() }
    override suspend fun marathonEvents(lat: Double?, lng: Double?) = record("marathonEvents $lat,$lng") { onMarathonEvents(lat, lng) }
    override suspend fun raceDetail(eventId: String) = record("raceDetail $eventId") { onRaceDetail(eventId) }
    override suspend fun bibToken(eventId: String) = record("bibToken $eventId") { onBibToken(eventId) }
    override suspend fun updateParticipant(eventId: String, body: UpdateParticipantBody) =
        record("updateParticipant $eventId") { onUpdateParticipant(eventId, body) }
    override suspend fun referral() = record("referral") { onReferral() }
    override suspend fun applyReferralCode(body: ApplyReferralRequest) = record("applyReferralCode ${body.code}") { onApplyReferralCode(body) }
    override suspend fun claimUpgrade(eventId: String) = record("claimUpgrade $eventId") { onClaimUpgrade(eventId) }
    override suspend fun createOrder(body: CreateOrderRequest) = record("createOrder") { onCreateOrder(body) }
    override suspend fun orderStatus(orderId: String) = record("orderStatus $orderId") { onOrderStatus(orderId) }
    override suspend fun simulatePayment(orderId: String) = record("simulatePayment $orderId") { onSimulatePayment(orderId) }
    override suspend fun uploadRun(body: UploadRunRequest) = record("uploadRun ${body.id}") { onUploadRun(body) }
    override suspend fun runHistory() = record("runHistory") { onRunHistory() }
    override suspend fun submitDietLead(body: DietLeadRequest) = record("submitDietLead") { onSubmitDietLead(body) }
    override suspend fun workshops() = record("workshops") { onWorkshops() }
    override suspend fun setWorkshopRegistration(workshopId: String, body: SetRegisteredRequest) =
        record("setWorkshopRegistration $workshopId ${body.registered}") { onSetWorkshopRegistration(workshopId, body) }
    override suspend fun content() = record("content") { onContent() }
    override suspend fun registerPushToken(body: RegisterPushTokenRequest) =
        record("registerPushToken ${body.token}") { onRegisterPushToken(body) }
    override suspend fun removePushToken(body: RemovePushTokenRequest) =
        record("removePushToken ${body.token}") { onRemovePushToken(body) }
    override suspend fun notifications() = record("notifications") { onNotifications() }
}
