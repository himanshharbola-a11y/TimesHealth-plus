package timeshealth.app.core.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Test
import timeshealth.app.core.data.cache.CacheKeys
import timeshealth.app.core.data.cache.ResponseCache
import timeshealth.app.core.data.checkout.PendingOrder
import timeshealth.app.core.data.checkout.PendingOrders
import timeshealth.app.core.data.inbox.InboxSeenStore
import timeshealth.app.core.data.pass.OfflinePassStore
import timeshealth.app.core.data.push.PushRegistration
import timeshealth.app.core.data.session.LocalDataWiper
import timeshealth.app.core.data.session.PersonaTokenStore
import timeshealth.app.core.data.session.SessionRepository
import timeshealth.app.core.data.session.SessionStatus
import timeshealth.app.core.data.session.SignInMethod
import timeshealth.app.core.model.PaymentGateway
import timeshealth.app.core.model.RemovePushTokenResponse
import timeshealth.app.core.network.ServerClock

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryTest {

    private val log = EventLog()
    private val store = FakeSecureStore(log)
    private val api = FakeTimesHealthApi(log)
    private val identity = FakeIdentityGateway(log)
    private val pendingOrders = PendingOrders()

    /** Replaces the provider's authState (e.g. one that hasn't reported yet). */
    private var authState: Flow<String?>? = null

    private class Harness(
        val session: SessionRepository,
        val cache: ResponseCache,
        val passes: OfflinePassStore,
        val inbox: InboxSeenStore,
        val push: PushRegistration,
    )

    private fun TestScope.start(): Harness {
        val cache = ResponseCache(testTimeSource)
        val push = PushRegistration(api, store)
        val passes = OfflinePassStore(store, ServerClock { NOW_MS })
        val inbox = InboxSeenStore(store)
        val tracking = TrackingSuspender { log.add("tracking.suspend") }
        val wiper = LocalDataWiper(cache, push, passes, tracking, inbox, pendingOrders)
        val gateway = authState?.let { flow ->
            object : IdentityGateway by identity {
                override val authState: Flow<String?> = flow
            }
        } ?: identity
        val session = SessionRepository(
            gateway, PersonaTokenStore(store), push, wiper, backgroundScope, SessionRepository.AUTH_SETTLE_TIMEOUT,
        )
        runCurrent()
        return Harness(session, cache, passes, inbox, push)
    }

    private fun TestScope.logStatusChanges(session: SessionRepository) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            session.status.drop(1).collect { log.add("status $it") }
        }
    }

    private val signedOut = SessionStatus.SignedOut
    private val identityUser = SessionStatus.SignedIn(SignInMethod.IDENTITY)
    private val persona = SessionStatus.SignedIn(SignInMethod.PERSONA)

    // ── Restore ─────────────────────────────────────────────────────────────

    @Test
    fun `a saved persona restores as signed in, and its token wins`() = runTest {
        store.values[PersonaTokenStore.PERSONA_KEY] = "qa_yoga|yoga@th.test"
        identity.uid.value = "u1"

        val h = start()

        assertThat(h.session.status.value).isEqualTo(persona)
        assertThat(h.session.token()).isEqualTo("qa_yoga|yoga@th.test")
    }

    @Test
    fun `the provider's restored user is signed in, without a wipe`() = runTest {
        identity.uid.value = "u1"

        val h = start()

        assertThat(h.session.status.value).isEqualTo(identityUser)
        assertThat(h.session.token()).isEqualTo("id-token-u1")
        assertThat(log.count("tracking.suspend")).isEqualTo(0)
    }

    @Test
    fun `nobody saved - signed out, no token`() = runTest {
        val h = start()

        assertThat(h.session.status.value).isEqualTo(signedOut)
        assertThat(h.session.token()).isNull()
    }

    @Test
    fun `an unreadable store means no persona, never a crash`() = runTest {
        store.values[PersonaTokenStore.PERSONA_KEY] = "qa_free"
        store.failReads = true

        val h = start()

        assertThat(h.session.status.value).isEqualTo(signedOut)
    }

    @Test
    fun `a request before auth settles waits for it, then carries the token`() = runTest {
        val reports = MutableSharedFlow<String?>()
        authState = reports
        val h = start()
        assertThat(h.session.status.value).isEqualTo(SessionStatus.Loading)

        val token = async { h.session.token() }
        runCurrent()
        assertThat(token.isCompleted).isFalse()

        identity.uid.value = "u1"
        reports.emit("u1")
        runCurrent()

        assertThat(token.await()).isEqualTo("id-token-u1")
        assertThat(currentTime).isEqualTo(0)
    }

    @Test
    fun `a provider that never settles holds a request for 5 s at most`() = runTest {
        authState = MutableSharedFlow()
        val h = start()

        assertThat(h.session.token()).isNull()
        assertThat(currentTime).isEqualTo(SessionRepository.AUTH_SETTLE_TIMEOUT.inWholeMilliseconds)
    }

    @Test
    fun `a failing provider token means no token, not a crash`() = runTest {
        identity.uid.value = "u1"
        identity.failIdToken = true
        val h = start()

        assertThat(h.session.token()).isNull()
    }

    // ── Sign-out ────────────────────────────────────────────────────────────

    @Test
    fun `sign-out - unregister while authenticated, then token, provider, wipe, signed out`() = runTest {
        identity.uid.value = "u1"
        val h = start()
        // Leave a bit of everything behind.
        assertThat(h.push.registerToken("fcm-token-1")).isTrue()
        h.passes.save("delhi_half", bib(), h.passes.generation(), marathonEvent())
        h.cache.seed(CacheKeys.Home)
        h.inbox.markSeen(5_000L)
        pendingOrders["key"] = PendingOrder("o1", PaymentGateway.STUB)
        var authenticatedDuringUnregister = false
        api.onRemovePushToken = {
            authenticatedDuringUnregister = h.session.token() == "id-token-u1"
            RemovePushTokenResponse(removed = true)
        }
        logStatusChanges(h.session)
        log.clear()

        h.session.signOut()
        runCurrent()

        val expected = listOf(
            "api.removePushToken fcm-token-1",
            "store.remove ${PushRegistration.TOKEN_KEY}",
            "store.remove ${PersonaTokenStore.PERSONA_KEY}",
            "identity.signOut",
            "store.remove ${OfflinePassStore.keyFor("delhi_half")}",
            "store.remove ${OfflinePassStore.INDEX_KEY}",
            "tracking.suspend",
            "store.remove ${InboxSeenStore.SEEN_KEY}",
            "status SignedOut",
        )
        assertThat(log.events.filter { it in expected }).containsExactlyElementsIn(expected).inOrder()
        assertThat(authenticatedDuringUnregister).isTrue()

        // Everything the user left is gone.
        assertThat(h.session.status.value).isEqualTo(signedOut)
        assertThat(h.session.token()).isNull()
        assertThat(h.cache.peek<Any>(CacheKeys.Home)).isNull()
        assertThat(h.passes.list()).isEmpty()
        assertThat(h.inbox.state.value).isEqualTo(InboxSeenStore.State(seenAtMs = null, loaded = true))
        assertThat(pendingOrders["key"]).isNull()
        // The provider echoing the sign-out is not a second wipe.
        assertThat(log.count("tracking.suspend")).isEqualTo(1)
        // The next user registers this device under their own account.
        api.calls.clear()
        h.push.registerToken("fcm-token-1")
        assertThat(api.callsTo("registerPushToken")).hasSize(1)
    }

    @Test
    fun `account deleted - nothing to unregister remotely, still wiped and signed out`() = runTest {
        identity.uid.value = "u1"
        val h = start()
        h.push.registerToken("fcm-token-1")

        h.session.signOut(accountDeleted = true)
        runCurrent()

        assertThat(api.callsTo("removePushToken")).isEmpty()
        assertThat(store.values).doesNotContainKey(PushRegistration.TOKEN_KEY)
        assertThat(log.count("identity.signOut")).isEqualTo(1)
        assertThat(log.count("tracking.suspend")).isEqualTo(1)
        assertThat(h.session.status.value).isEqualTo(signedOut)
    }

    @Test
    fun `sign-out always completes, even offline and with a failing provider`() = runTest {
        store.values[PersonaTokenStore.PERSONA_KEY] = "qa_free"
        val h = start()
        h.push.registerToken("fcm-token-1")
        api.onRemovePushToken = { throw networkDown }
        val brokenProvider = object : IdentityGateway by identity {
            override suspend fun signOut() {
                throw IllegalStateException("provider down")
            }
        }
        val session = SessionRepository(
            brokenProvider, PersonaTokenStore(store), h.push,
            LocalDataWiper(h.cache, h.push, h.passes, { log.add("tracking.suspend") }, h.inbox, pendingOrders),
            backgroundScope, SessionRepository.AUTH_SETTLE_TIMEOUT,
        )
        runCurrent()

        session.signOut()

        assertThat(session.status.value).isEqualTo(signedOut)
        assertThat(session.token()).isNull()
        assertThat(log.count("tracking.suspend")).isEqualTo(1)
    }

    // ── Sign-in and identity changes ────────────────────────────────────────

    @Test
    fun `persona sign-in wipes the previous user's data before storing the token`() = runTest {
        identity.uid.value = "u1"
        val h = start()
        h.cache.seed(CacheKeys.Home)
        log.clear()

        h.session.signInPersona("qa_marathon|marathon@th.test")

        assertThat(log.indexOf("tracking.suspend"))
            .isLessThan(log.indexOf("store.put ${PersonaTokenStore.PERSONA_KEY}"))
        assertThat(h.session.status.value).isEqualTo(persona)
        assertThat(h.session.token()).isEqualTo("qa_marathon|marathon@th.test")
        assertThat(h.cache.wasInvalidated(CacheKeys.Home)).isTrue()
    }

    @Test
    fun `a different account wipes cached data`() = runTest {
        identity.uid.value = "u1"
        val h = start()
        h.cache.seed(CacheKeys.Home)
        h.passes.save("delhi_half", bib(), h.passes.generation(), null)
        pendingOrders["key"] = PendingOrder("o1", PaymentGateway.STUB)

        identity.uid.value = "u2"
        runCurrent()

        assertThat(log.count("tracking.suspend")).isEqualTo(1)
        assertThat(h.cache.peek<Any>(CacheKeys.Home)).isNull()
        assertThat(h.passes.list()).isEmpty()
        assertThat(pendingOrders["key"]).isNull()
        assertThat(h.session.status.value).isEqualTo(identityUser)
        assertThat(h.session.token()).isEqualTo("id-token-u2")
    }

    @Test
    fun `the same account reported again is not a change`() = runTest {
        val reports = MutableSharedFlow<String?>()
        authState = reports
        identity.uid.value = "u1"
        val h = start()
        reports.emit("u1")
        runCurrent()
        h.cache.seed(CacheKeys.Home)

        reports.emit("u1")
        runCurrent()

        assertThat(log.count("tracking.suspend")).isEqualTo(0)
        assertThat(h.cache.wasInvalidated(CacheKeys.Home)).isFalse()
    }

    @Test
    fun `signing in after a signed-out launch also starts clean`() = runTest {
        val h = start()
        assertThat(h.session.status.value).isEqualTo(signedOut)

        identity.uid.value = "u1"
        runCurrent()

        assertThat(log.count("tracking.suspend")).isEqualTo(1)
        assertThat(h.session.status.value).isEqualTo(identityUser)
    }

    @Test
    fun `a persona wins over account changes underneath it, which still wipe`() = runTest {
        val h = start()
        h.session.signInPersona("qa_both")
        log.clear()

        identity.uid.value = "u9"
        runCurrent()

        assertThat(log.count("tracking.suspend")).isEqualTo(1)
        assertThat(h.session.status.value).isEqualTo(persona)
        assertThat(h.session.token()).isEqualTo("qa_both")
    }

    // ── A rejected token (401) ──────────────────────────────────────────────

    @Test
    fun `a 401 ends the session once, however many requests it rejected`() = runTest {
        store.values[PersonaTokenStore.PERSONA_KEY] = "qa_expired"
        identity.uid.value = "u1"
        val h = start()
        h.push.registerToken("fcm-token-1")
        logStatusChanges(h.session)
        log.clear()

        repeat(5) { h.session.onUnauthorized() }
        runCurrent()

        assertThat(log.count("identity.signOut")).isEqualTo(1)
        assertThat(log.count("tracking.suspend")).isEqualTo(1)
        assertThat(log.count("status SignedOut")).isEqualTo(1)
        assertThat(h.session.token()).isNull()
        // The server just refused this credential: no unregister call that can't authenticate.
        assertThat(api.callsTo("removePushToken")).isEmpty()

        // Stragglers still in flight after the session ended change nothing.
        h.session.onUnauthorized()
        runCurrent()
        assertThat(log.count("identity.signOut")).isEqualTo(1)
        assertThat(log.count("tracking.suspend")).isEqualTo(1)
    }

    @Test
    fun `the next session's 401 is handled again`() = runTest {
        val h = start()
        h.session.signInPersona("qa_free")
        h.session.onUnauthorized()
        runCurrent()
        assertThat(h.session.status.value).isEqualTo(signedOut)

        h.session.signInPersona("qa_yoga")
        h.session.onUnauthorized()
        runCurrent()

        assertThat(h.session.status.value).isEqualTo(signedOut)
        assertThat(h.session.token()).isNull()
    }

    @Test
    fun `a 401 that waited past a new sign-in doesn't sign the new user out`() = runTest {
        val h = start()
        h.session.signInPersona("qa_free")

        h.session.onUnauthorized() // the old persona's request was rejected...
        h.session.signInPersona("qa_yoga") // ...but a tester switched before it was handled.
        runCurrent()

        assertThat(h.session.status.value).isEqualTo(persona)
        assertThat(h.session.token()).isEqualTo("qa_yoga")
    }

    @Test
    fun `a 401 while signed out is ignored`() = runTest {
        val h = start()
        log.clear()

        h.session.onUnauthorized()
        runCurrent()

        assertThat(log.events).isEmpty()
    }
}
