package timeshealth.app.core.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Test
import timeshealth.app.core.data.cache.CacheKeys
import timeshealth.app.core.data.cache.ResponseCache
import timeshealth.app.core.data.checkout.PendingOrders
import timeshealth.app.core.data.inbox.InboxSeenStore
import timeshealth.app.core.data.pass.OfflinePassStore
import timeshealth.app.core.data.push.PushRegistration
import timeshealth.app.core.data.repository.MarathonRepository
import timeshealth.app.core.data.repository.NotificationsRepository
import timeshealth.app.core.data.repository.ProfileRepository
import timeshealth.app.core.data.repository.WorkshopsRepository
import timeshealth.app.core.data.repository.YogaRepository
import timeshealth.app.core.data.session.LocalDataWiper
import timeshealth.app.core.data.session.PersonaTokenStore
import timeshealth.app.core.data.session.SessionRepository
import timeshealth.app.core.data.session.SessionStatus
import timeshealth.app.core.model.Concern
import timeshealth.app.core.model.DeleteAccountResponse
import timeshealth.app.core.model.MySessionsResponse
import timeshealth.app.core.model.NotificationListResponse
import timeshealth.app.core.model.SavedResponse
import timeshealth.app.core.model.Units
import timeshealth.app.core.model.UpdateProfileRequest
import timeshealth.app.core.model.WorkshopRegistrationResponse
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.core.network.ServerClock

/** The cache-invalidation rules and side effects the repositories carry over from RN. */
@OptIn(ExperimentalCoroutinesApi::class)
class RepositoriesTest {

    private val log = EventLog()
    private val api = FakeTimesHealthApi(log)
    private val store = FakeSecureStore(log)
    private val passes = OfflinePassStore(store, ServerClock { NOW_MS })

    private fun TestScope.cache() = ResponseCache(testTimeSource)

    private fun TestScope.session(cache: ResponseCache, identity: FakeIdentityGateway): SessionRepository {
        val push = PushRegistration(api, store)
        val wiper = LocalDataWiper(cache, push, passes, { log.add("tracking.suspend") }, InboxSeenStore(store), PendingOrders())
        return SessionRepository(identity, PersonaTokenStore(store), push, wiper, backgroundScope, SessionRepository.AUTH_SETTLE_TIMEOUT)
            .also { runCurrent() }
    }

    @Test
    fun `a new goal or focus area refetches Home, other profile edits don't`() = runTest {
        val cache = cache()
        val profile = ProfileRepository(api, cache, session(cache, FakeIdentityGateway(log, "u1")))
        api.onUpdateProfile = { profileResponse() }
        cache.seed(CacheKeys.Session)
        cache.seed(CacheKeys.Home)

        profile.updateProfile(UpdateProfileRequest(units = Units.IMPERIAL))
        assertThat(cache.wasInvalidated(CacheKeys.Session)).isTrue()
        assertThat(cache.wasInvalidated(CacheKeys.Home)).isFalse()

        profile.updateProfile(UpdateProfileRequest(concern = Concern.LOWER_BACK))
        assertThat(cache.wasInvalidated(CacheKeys.Home)).isTrue()
    }

    @Test
    fun `account deletion signs out without unregistering, a failed deletion signs nobody out`() = runTest {
        val cache = cache()
        val session = session(cache, FakeIdentityGateway(log, "u1"))
        val profile = ProfileRepository(api, cache, session)

        api.onDeleteAccount = { throw networkDown }
        expectThrows<ApiRequestException> { profile.deleteAccount() }
        assertThat(session.status.value).isNotEqualTo(SessionStatus.SignedOut)

        api.onDeleteAccount = { DeleteAccountResponse(deleted = true, authRecordDeleted = true) }
        profile.deleteAccount()
        assertThat(session.status.value).isEqualTo(SessionStatus.SignedOut)
        assertThat(api.callsTo("removePushToken")).isEmpty()
    }

    @Test
    fun `save sends the wanted state, shows it at once, and refetches afterwards`() = runTest {
        val cache = cache()
        val yoga = YogaRepository(api, cache)
        api.onMySessions = { MySessionsResponse(savedSessionIds = listOf("s1")) }
        yoga.mySessions.get()
        val gate = CompletableDeferred<Unit>()
        api.onSetSaved = { _, body -> gate.await(); SavedResponse(body.saved) }

        val save = async { yoga.setSaved("s2", saved = true) }
        runCurrent()
        // Optimistic: on screen before the server answers.
        assertThat(yoga.mySessions.data.first()?.savedSessionIds).containsExactly("s1", "s2").inOrder()
        gate.complete(Unit)
        save.await()

        assertThat(api.callsTo("setSaved")).containsExactly("setSaved s2 true")
        // The server's copy is the truth: the next read refetches.
        api.onMySessions = { MySessionsResponse(savedSessionIds = listOf("s1", "s2")) }
        yoga.mySessions.get()
        assertThat(api.callsTo("mySessions")).hasSize(2)
    }

    @Test
    fun `a double tap ends where the last tap left it - requests run in order`() = runTest {
        val cache = cache()
        val yoga = YogaRepository(api, cache)
        api.onMySessions = { MySessionsResponse() }
        yoga.mySessions.get()
        val firstGate = CompletableDeferred<Unit>()
        api.onSetSaved = { _, body -> if (body.saved) firstGate.await(); SavedResponse(body.saved) }

        val tap1 = async { yoga.setSaved("s1", saved = true) }
        runCurrent()
        val tap2 = async { yoga.setSaved("s1", saved = false) }
        runCurrent()
        assertThat(yoga.mySessions.peek()?.savedSessionIds).isEmpty()
        assertThat(api.callsTo("setSaved")).containsExactly("setSaved s1 true") // the second waits its turn
        firstGate.complete(Unit)
        tap1.await()
        tap2.await()

        assertThat(api.callsTo("setSaved")).containsExactly("setSaved s1 true", "setSaved s1 false").inOrder()
    }

    @Test
    fun `a failed save still refetches the truth`() = runTest {
        val cache = cache()
        val yoga = YogaRepository(api, cache)
        api.onMySessions = { MySessionsResponse() }
        yoga.mySessions.get()
        api.onSetSaved = { _, _ -> throw networkDown }

        expectThrows<ApiRequestException> { yoga.setSaved("s1", saved = true) }

        api.onMySessions = { MySessionsResponse() }
        assertThat(yoga.mySessions.get().savedSessionIds).isEmpty()
    }

    @Test
    fun `workshop booking sends the wanted state and refreshes workshops and Home`() = runTest {
        val cache = cache()
        val workshops = WorkshopsRepository(api, cache)
        api.onSetWorkshopRegistration = { _, body -> WorkshopRegistrationResponse(body.registered) }
        cache.seed(CacheKeys.Workshops)
        cache.seed(CacheKeys.Home)
        cache.seed(CacheKeys.Content)

        val result = workshops.setWorkshopRegistration("w1", registered = false)

        assertThat(result.registered).isFalse()
        assertThat(api.callsTo("setWorkshopRegistration")).containsExactly("setWorkshopRegistration w1 false")
        assertThat(cache.wasInvalidated(CacheKeys.Workshops)).isTrue()
        assertThat(cache.wasInvalidated(CacheKeys.Home)).isTrue()
        assertThat(cache.wasInvalidated(CacheKeys.Content)).isFalse()
    }

    @Test
    fun `a race page keeps the pass for the gate, and drops it when the bib is gone`() = runTest {
        val marathon = MarathonRepository(api, cache(), passes)
        api.onRaceDetail = { raceDetail(it) }

        marathon.raceDetail("delhi_half").get()
        assertThat(marathon.offlinePass("delhi_half")?.bibNumber).isEqualTo("A1024")
        assertThat(marathon.offlinePass("delhi_half")?.flagOffTime).isEqualTo("2026-10-26T00:45:00.000Z")

        api.onRaceDetail = { raceDetail(it, bib = null) }
        marathon.raceDetail("delhi_half").get()
        assertThat(marathon.offlinePasses()).isEmpty()
    }

    @Test
    fun `a race page that loads after sign-out doesn't save the previous user's pass`() = runTest {
        val cache = cache()
        val identity = FakeIdentityGateway(log, "u1")
        val session = session(cache, identity)
        val marathon = MarathonRepository(api, cache, passes)
        val gate = CompletableDeferred<Unit>()
        api.onRaceDetail = { gate.await(); raceDetail(it) }

        val page = async { marathon.raceDetail("delhi_half").get() }
        runCurrent()
        session.signOut()
        gate.complete(Unit)
        page.await()

        assertThat(marathon.offlinePasses()).isEmpty()
        assertThat(store.values.keys.filter { it.startsWith("th_pass") }).isEmpty()
        assertThat(cache.peek<Any>(CacheKeys.raceDetail("delhi_half"))).isNull()
    }

    @Test
    fun `the bell dot follows the inbox and the seen marker`() = runTest {
        val cache = cache()
        val inbox = NotificationsRepository(api, cache, InboxSeenStore(store))
        val items = listOf(notification("n1", "2026-10-06T05:00:00.000Z"), notification("n2", "2026-10-06T06:00:00.000Z"))
        api.onNotifications = { NotificationListResponse(items) }

        inbox.loadSeen()
        inbox.inbox.get()
        assertThat(inbox.hasUnread.first()).isTrue()

        inbox.markSeen(inbox.newestFirst(items))
        assertThat(inbox.hasUnread.first()).isFalse()
        assertThat(inbox.newestFirst(items).map { it.id }).containsExactly("n2", "n1").inOrder()
    }
}
