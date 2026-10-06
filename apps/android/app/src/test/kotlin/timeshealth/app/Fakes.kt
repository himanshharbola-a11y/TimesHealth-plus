package timeshealth.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import timeshealth.app.core.data.pass.OfflinePass
import timeshealth.app.core.data.session.SessionStatus
import timeshealth.app.core.model.ApiError
import timeshealth.app.core.model.AppConfigResponse
import timeshealth.app.core.model.Entitlements
import timeshealth.app.core.model.Maintenance
import timeshealth.app.core.model.PersonaInfo
import timeshealth.app.core.model.RaceTier
import timeshealth.app.core.model.SessionResponse
import timeshealth.app.core.model.UserPersona
import timeshealth.app.core.model.UserProfile
import timeshealth.app.core.model.Units
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.ui.login.InteractiveSignIn
import timeshealth.app.ui.login.PhoneChallenge
import timeshealth.app.ui.login.SignInAvailability
import timeshealth.app.ui.session.AccountGateway
import timeshealth.app.ui.session.InboxGateway
import timeshealth.app.ui.session.SessionGateway

/** Runs `Dispatchers.Main` (viewModelScope) on a test dispatcher, so `delay` is virtual. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

// ── Fixtures ────────────────────────────────────────────────────────────────

fun appConfig(minVersion: String = "1.0.0", maintenance: Boolean = false, message: String? = null) =
    AppConfigResponse(
        minSupportedAppVersion = minVersion,
        maintenance = Maintenance(active = maintenance, message = message),
        serverTime = "2026-10-06T06:00:00.000Z",
    )

fun sessionResponse(needsOnboarding: Boolean = false, name: String? = "Priya Sharma") = SessionResponse(
    profile = UserProfile(
        id = "user_1",
        name = name,
        emailIsLogin = true,
        phoneIsLogin = false,
        profileCompletion = 60,
        onboardingCompleted = !needsOnboarding,
        units = Units.METRIC,
        locale = "en-IN",
    ),
    entitlements = Entitlements(marathon = emptyList()),
    persona = PersonaInfo(UserPersona.FREE, hasYoga = false, hasMarathon = false, label = "Free"),
    needsOnboarding = needsOnboarding,
    serverTime = "2026-10-06T06:00:00.000Z",
    minSupportedAppVersion = "1.0.0",
    maintenance = Maintenance(active = false),
)

fun offlinePass(eventId: String = "evt_hyd", name: String = "Hyderabad Half Marathon") = OfflinePass(
    eventId = eventId,
    bibNumber = "H1234",
    participantName = "Priya Sharma",
    category = "21K",
    tier = RaceTier.CLASSIC,
    eventName = name,
    offlinePayload = "ref.user_1.9999999999.sig",
    savedAt = "2026-10-06T06:00:00.000Z",
)

fun networkError(code: String = "NETWORK", message: String = "No connection. Check your network and try again.") =
    ApiRequestException(status = 0, error = ApiError(code = code, message = message))

fun httpError(status: Int, code: String = "ERR") = ApiRequestException(status, ApiError(code, "server words"))

fun homeResponse(
    userName: String? = "Priya Sharma",
    components: List<timeshealth.app.core.model.FeedComponent> = emptyList(),
) = timeshealth.app.core.model.HomeFeedResponse(
    greeting = "Good morning · Thursday",
    userName = userName,
    components = components,
    serverTime = "2026-10-08T01:00:00.000Z",
    ttlSeconds = 60,
)

// ── Fakes ───────────────────────────────────────────────────────────────────

class FakeSessionGateway(initial: SessionStatus = SessionStatus.Loading) : SessionGateway {
    private val _status = MutableStateFlow(initial)
    override val status: StateFlow<SessionStatus> = _status

    val personaTokens = mutableListOf<String>()
    var signOuts = 0

    /** When set, signInPersona waits for it (to observe the busy state). */
    var personaGate: CompletableDeferred<Unit>? = null
    var personaFailure: Exception? = null

    fun emit(status: SessionStatus) {
        _status.value = status
    }

    override suspend fun signInPersona(token: String) {
        personaGate?.await()
        personaFailure?.let { throw it }
        personaTokens += token
        _status.value = SessionStatus.SignedIn(timeshealth.app.core.data.session.SignInMethod.PERSONA)
    }

    override suspend fun signOut() {
        signOuts++
        _status.value = SessionStatus.SignedOut
    }
}

/**
 * Scripted [AccountGateway]: each call takes the next answer from its queue
 * (the last one repeats). A [CompletableDeferred] answer suspends until completed.
 */
class FakeAccountGateway : AccountGateway {
    val configAnswers = ArrayDeque<Any>()
    val sessionAnswers = ArrayDeque<Any>()
    var passes: List<OfflinePass> = emptyList()

    val configCalls = mutableListOf<Boolean>()
    val sessionCalls = mutableListOf<Boolean>()

    override val cachedSession = MutableStateFlow<SessionResponse?>(null)
    override val sessionChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override suspend fun config(refresh: Boolean): AppConfigResponse {
        configCalls += refresh
        return answer(configAnswers) as AppConfigResponse
    }

    override suspend fun session(refresh: Boolean): SessionResponse {
        sessionCalls += refresh
        return (answer(sessionAnswers) as SessionResponse).also { cachedSession.value = it }
    }

    override suspend fun savedPasses(): List<OfflinePass> = passes

    private suspend fun answer(queue: ArrayDeque<Any>): Any {
        val next = if (queue.size > 1) queue.removeFirst() else queue.first()
        return when (next) {
            is Throwable -> throw next
            is CompletableDeferred<*> -> next.await()!!
            else -> next
        }
    }
}

class FakeInboxGateway : InboxGateway {
    override val hasUnread = MutableStateFlow(false)
    override val changes: Flow<Unit> = MutableSharedFlow()
    var refreshes = 0
    override suspend fun loadSeen() = Unit
    override suspend fun refresh(force: Boolean) {
        refreshes++
    }
}

class FakeInteractiveSignIn(
    override val availability: SignInAvailability = SignInAvailability(google = true, phone = true, email = true),
) : InteractiveSignIn {
    val phoneNumbers = mutableListOf<String>()
    val confirmedCodes = mutableListOf<String>()
    var failWith: Exception? = null

    override suspend fun signInWithGoogle() {
        failWith?.let { throw it }
    }

    override suspend fun startPhoneSignIn(e164: String): PhoneChallenge {
        failWith?.let { throw it }
        phoneNumbers += e164
        return PhoneChallenge { confirmedCodes += it }
    }

    override suspend fun signInWithEmail(email: String, password: String, createAccount: Boolean) {
        failWith?.let { throw it }
    }

    override suspend fun sendPasswordReset(email: String) {
        failWith?.let { throw it }
    }
}

fun buildInfo(version: String = "1.0.0", debug: Boolean = true, devSignIn: Boolean = false) = AppBuildInfo(
    versionName = version,
    applicationId = "timeshealth.app",
    debug = debug,
    devSignIn = devSignIn,
    firebaseEnabled = false,
)

class FakeHomeGateway(var nowMs: Long = 1_791_427_200_000L) : timeshealth.app.ui.home.HomeGateway {
    val answers = ArrayDeque<Any>()
    val calls = mutableListOf<Boolean>()
    override val homeChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override suspend fun home(refresh: Boolean): timeshealth.app.core.model.HomeFeedResponse {
        calls += refresh
        val next = if (answers.size > 1) answers.removeFirst() else answers.first()
        if (next is Throwable) throw next
        return next as timeshealth.app.core.model.HomeFeedResponse
    }

    override fun nowMs(): Long = nowMs
}
