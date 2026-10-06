package timeshealth.app.core.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import timeshealth.app.core.data.push.PushRegistration
import timeshealth.app.core.model.DevicePlatform
import timeshealth.app.core.model.PushProvider
import timeshealth.app.core.model.RegisterPushTokenRequest
import timeshealth.app.core.model.RegisterPushTokenResponse

@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrationTest {

    private val api = FakeTimesHealthApi()
    private val store = FakeSecureStore()
    private val push = PushRegistration(api, store)

    @Test
    fun `registers an FCM token for this Android device and remembers it`() = runTest {
        val sent = mutableListOf<RegisterPushTokenRequest>()
        api.onRegisterPushToken = { sent += it; RegisterPushTokenResponse(registered = true) }

        assertThat(push.registerToken("fcm-token-1")).isTrue()

        assertThat(sent).containsExactly(RegisterPushTokenRequest("fcm-token-1", DevicePlatform.ANDROID, PushProvider.FCM))
        assertThat(store.values[PushRegistration.TOKEN_KEY]).isEqualTo("fcm-token-1")
    }

    @Test
    fun `the same token for the same user is registered once`() = runTest {
        repeat(3) { assertThat(push.registerToken("fcm-token-1")).isTrue() }

        assertThat(api.callsTo("registerPushToken")).hasSize(1)
    }

    @Test
    fun `a token already being registered is not sent again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        api.onRegisterPushToken = { gate.await(); RegisterPushTokenResponse(registered = true) }

        val first = async { push.registerToken("fcm-token-1") }
        runCurrent()
        val second = push.registerToken("fcm-token-1")
        gate.complete(Unit)

        assertThat(second).isTrue()
        assertThat(first.await()).isTrue()
        assertThat(api.callsTo("registerPushToken")).hasSize(1)
    }

    @Test
    fun `a new token is registered`() = runTest {
        push.registerToken("fcm-token-1")
        push.registerToken("fcm-token-2")

        assertThat(api.callsTo("registerPushToken"))
            .containsExactly("registerPushToken fcm-token-1", "registerPushToken fcm-token-2").inOrder()
        assertThat(store.values[PushRegistration.TOKEN_KEY]).isEqualTo("fcm-token-2")
    }

    @Test
    fun `after an identity change the next user registers the same token again`() = runTest {
        push.registerToken("fcm-token-1")

        push.reset()
        push.registerToken("fcm-token-1")

        assertThat(api.callsTo("registerPushToken")).hasSize(2)
    }

    @Test
    fun `a registration in flight across a sign-out doesn't stop the next user registering`() = runTest {
        val gate = CompletableDeferred<Unit>()
        api.onRegisterPushToken = { gate.await(); RegisterPushTokenResponse(registered = true) }
        val previousUser = async { push.registerToken("fcm-token-1") }
        runCurrent()

        push.reset() // sign-out while it was in flight
        gate.complete(Unit)
        previousUser.await()
        api.onRegisterPushToken = { RegisterPushTokenResponse(registered = true) }
        push.registerToken("fcm-token-1") // the next user

        assertThat(api.callsTo("registerPushToken")).hasSize(2)
    }

    @Test
    fun `a failed registration reports false, isn't remembered and is retried`() = runTest {
        api.onRegisterPushToken = { throw networkDown }
        assertThat(push.registerToken("fcm-token-1")).isFalse()
        assertThat(store.values).doesNotContainKey(PushRegistration.TOKEN_KEY)

        api.onRegisterPushToken = { RegisterPushTokenResponse(registered = true) }
        assertThat(push.registerToken("fcm-token-1")).isTrue()

        assertThat(api.callsTo("registerPushToken")).hasSize(2)
    }

    @Test
    fun `a blank token is never sent`() = runTest {
        assertThat(push.registerToken("  ")).isFalse()
        assertThat(api.calls).isEmpty()
    }

    @Test
    fun `token rotation registers exactly the token handed over and fetches nothing`() = runTest {
        push.registerToken("fcm-token-1")

        push.onTokenRotated("fcm-token-2")
        push.onTokenRotated("fcm-token-2") // FCM may report the same rotation twice

        assertThat(api.calls).containsExactly("registerPushToken fcm-token-1", "registerPushToken fcm-token-2").inOrder()
    }

    @Test
    fun `unregistering removes the remembered token and forgets it`() = runTest {
        push.registerToken("fcm-token-1")

        push.unregisterDevice()

        assertThat(api.callsTo("removePushToken")).containsExactly("removePushToken fcm-token-1")
        assertThat(store.values).doesNotContainKey(PushRegistration.TOKEN_KEY)
        // Signing in again on this phone registers it afresh.
        push.registerToken("fcm-token-1")
        assertThat(api.callsTo("registerPushToken")).hasSize(2)
    }

    @Test
    fun `a token remembered by an earlier launch is removed too`() = runTest {
        store.values[PushRegistration.TOKEN_KEY] = "fcm-from-last-week"

        push.unregisterDevice()

        assertThat(api.callsTo("removePushToken")).containsExactly("removePushToken fcm-from-last-week")
    }

    @Test
    fun `nothing registered - nothing to remove`() = runTest {
        push.unregisterDevice()

        assertThat(api.calls).isEmpty()
    }

    @Test
    fun `a failed removal is swallowed and the token kept for next time`() = runTest {
        push.registerToken("fcm-token-1")
        api.onRemovePushToken = { throw networkDown }

        push.unregisterDevice()

        assertThat(store.values[PushRegistration.TOKEN_KEY]).isEqualTo("fcm-token-1")
    }

    @Test
    fun `the priming screen is remembered per device`() = runTest {
        assertThat(push.hasPrimed()).isFalse()

        push.markPrimed()

        assertThat(push.hasPrimed()).isTrue()
        assertThat(PushRegistration(api, store).hasPrimed()).isTrue()
    }
}
