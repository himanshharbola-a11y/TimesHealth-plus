package timeshealth.app.core.data

import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import timeshealth.app.core.data.cache.ResponseCache
import timeshealth.app.core.data.checkout.PendingOrders
import timeshealth.app.core.data.di.DataModule
import timeshealth.app.core.data.inbox.InboxSeenStore
import timeshealth.app.core.data.pass.OfflinePassStore
import timeshealth.app.core.data.push.PushRegistration
import timeshealth.app.core.data.session.LocalDataWiper
import timeshealth.app.core.data.session.PersonaTokenStore
import timeshealth.app.core.data.session.SessionRepository
import timeshealth.app.core.data.session.SessionStatus
import timeshealth.app.core.data.session.SignInMethod
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.core.network.ServerClock
import timeshealth.app.core.network.TimesHealthApi

/**
 * The Hilt provider's wiring, end to end over HTTP: the session supplies the credential and hears
 * the 401s. Real threads (OkHttp's), so this one runs on runBlocking rather than virtual time.
 */
class ApiWiringTest {

    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = EventLog()
    private val store = FakeSecureStore(log)
    private val identity = FakeIdentityGateway(log)

    private lateinit var session: SessionRepository

    private val api: TimesHealthApi = DataModule.timesHealthApi(
        config = object : AppConfig {
            override val apiBaseUrl = server.url("/v1").toString()
            override val debug = false
        },
        session = { session },
        serverClock = ServerClock(),
    )

    init {
        val push = PushRegistration(api, store)
        val passes = OfflinePassStore(store, ServerClock())
        val wiper = LocalDataWiper(ResponseCache(), push, passes, { log.add("tracking.suspend") }, InboxSeenStore(store), PendingOrders())
        store.values[PersonaTokenStore.PERSONA_KEY] = "qa_free|free@th.test"
        session = SessionRepository(identity, PersonaTokenStore(store), push, wiper, scope, 5.seconds)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private val unauthorized = MockResponse().setResponseCode(401).setBody("""{"code":"UNAUTHORIZED","message":"Sign in again."}""")

    @Test
    fun `requests carry the session's token, and a rejected one ends the session once`() = runBlocking<Unit> {
        repeat(3) { server.enqueue(unauthorized) }

        repeat(3) { runCatching { api.home() } }
        withTimeout(5_000) { session.status.first { it == SessionStatus.SignedOut } }
        delay(200) // any straggling handlers

        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer qa_free|free@th.test")
        assertThat(log.count("identity.signOut")).isEqualTo(1)
        assertThat(log.count("tracking.suspend")).isEqualTo(1)
        assertThat(store.values).doesNotContainKey(PersonaTokenStore.PERSONA_KEY)
    }

    @Test
    fun `a 503 is a bad moment on the server, never a sign-out`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":"UNAVAILABLE","message":"Try again."}"""))

        val error = runCatching { api.home() }.exceptionOrNull() as ApiRequestException
        delay(200)

        assertThat(error.status).isEqualTo(503)
        assertThat(session.status.value).isEqualTo(SessionStatus.SignedIn(SignInMethod.PERSONA))
        assertThat(log.count("identity.signOut")).isEqualTo(0)
    }
}
