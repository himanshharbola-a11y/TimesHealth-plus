package timeshealth.app.core.data.session

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timeshealth.app.core.data.IdentityGateway
import timeshealth.app.core.data.di.ApplicationScope
import timeshealth.app.core.data.push.PushRegistration
import timeshealth.app.core.data.quietly
import timeshealth.app.core.network.TokenProvider

/**
 * Who is signed in, and every transition between users. Port of apps/mobile/src/store/session.ts
 * plus the credential half of apps/mobile/src/api/client.ts.
 *
 * Signed in means either a real identity-provider user exists (the provider persists that session
 * across launches on its own) or a QA persona has been chosen in a test build. Nothing else in
 * the app touches tokens: as the network layer's [TokenProvider], this picks the credential for
 * every request.
 *
 * Every transition (restore, persona sign-in, sign-out, a rejected token, a different account
 * appearing) runs one at a time under [transitions], so a wipe never interleaves with another
 * user's sign-in, and every one of them that changes WHO is signed in wipes the previous user's
 * data first ([LocalDataWiper]).
 */
@Singleton
class SessionRepository internal constructor(
    private val identity: IdentityGateway,
    private val personaTokens: PersonaTokenStore,
    private val push: PushRegistration,
    private val wiper: LocalDataWiper,
    private val scope: CoroutineScope,
    private val authSettleTimeout: Duration,
) : TokenProvider {

    @Inject
    constructor(
        identity: IdentityGateway,
        personaTokens: PersonaTokenStore,
        push: PushRegistration,
        wiper: LocalDataWiper,
        @ApplicationScope scope: CoroutineScope,
    ) : this(identity, personaTokens, push, wiper, scope, AUTH_SETTLE_TIMEOUT)

    private val _status = MutableStateFlow<SessionStatus>(SessionStatus.Loading)

    /** Loading until the saved login is restored, then SignedIn / SignedOut. */
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    private val transitions = Mutex()

    /** The uid the identity provider last reported; meaningful once [uidReported]. */
    private var lastUid: String? = null
    private var uidReported = false

    /**
     * Grows on every wipe and every status change: identifies "this session". A rejected token is
     * acted on only for the session it was seen in ([onUnauthorized]).
     */
    private val sessionSerial = AtomicLong(0)
    private val unauthorizedHandledFor = AtomicLong(-1)

    init {
        scope.launch { restore() }
    }

    private val method: SignInMethod? get() = (_status.value as? SessionStatus.SignedIn)?.method

    /**
     * Restores the persona (if one was chosen), then follows the identity provider for the
     * lifetime of the app: sign-in on the login screen, token revocation and sign-out elsewhere
     * all flow through here.
     */
    private suspend fun restore() {
        transitions.withLock {
            if (personaTokens.get() != null) setStatus(SessionStatus.SignedIn(SignInMethod.PERSONA))
        }
        identity.authState
            // A provider that breaks means "signed out": never stuck on Loading, never a crash.
            .catch { emit(null) }
            .collect { uid -> transitions.withLock { onIdentityChanged(uid) } }
    }

    private suspend fun onIdentityChanged(uid: String?) {
        // A different account (or none) than before: the last one's data must go. The first
        // report on launch is the restored login itself, not a change.
        if (uidReported && uid != lastUid) wipe()
        uidReported = true
        lastUid = uid
        // A persona wins over the real account underneath it.
        if (method == SignInMethod.PERSONA) return
        setStatus(if (uid != null) SessionStatus.SignedIn(SignInMethod.IDENTITY) else SessionStatus.SignedOut)
    }

    /** Signs in as a QA persona (test builds). The previous user's data is wiped first. */
    suspend fun signInPersona(token: String) {
        require(token.isNotBlank()) { "A persona token is required" }
        // A half-finished transition is worse than a finished one, whatever happens to the
        // screen that asked for it.
        withContext(NonCancellable) {
            transitions.withLock {
                wipe()
                personaTokens.set(token)
                setStatus(SessionStatus.SignedIn(SignInMethod.PERSONA))
            }
        }
    }

    /**
     * Signs out: unregisters this device's push token (unless [accountDeleted]), clears the
     * persona token, signs out of the identity provider, then wipes everything the user left on
     * the phone. Always ends signed out.
     *
     * @param accountDeleted the server has already erased the account, including its device
     *   registrations, so skip the calls that would need it to exist.
     */
    suspend fun signOut(accountDeleted: Boolean = false) {
        withContext(NonCancellable) {
            transitions.withLock {
                // First, while still authenticated: stop this phone receiving this user's alerts.
                if (accountDeleted) push.forgetDevice() else push.unregisterDevice()
                personaTokens.set(null)
                identitySignOut()
                wipe()
                setStatus(SessionStatus.SignedOut)
            }
        }
    }

    /**
     * The server rejected a token we sent (a 401 on a credentialed request; see the network
     * module's AuthInterceptor). Drops the user back to login.
     *
     * Called on an OkHttp thread, once per in-flight request that was rejected, so it returns at
     * once and acts only once per session: the first 401 ends the session, the rest are no-ops.
     * No push unregistration: the server has just refused this user's credential, so the call
     * couldn't be authenticated (the server prunes dead tokens).
     */
    fun onUnauthorized() {
        if (_status.value == SessionStatus.SignedOut) return
        val serial = sessionSerial.get()
        if (unauthorizedHandledFor.getAndSet(serial) == serial) return
        scope.launch {
            transitions.withLock {
                // Ended or replaced while this waited: the 401 belonged to a session that is
                // already gone, and must not sign out whoever is here now.
                if (sessionSerial.get() != serial || _status.value == SessionStatus.SignedOut) return@withLock
                personaTokens.set(null)
                identitySignOut()
                wipe()
                setStatus(SessionStatus.SignedOut)
            }
        }
    }

    /**
     * The credential for the next request: the persona token if one is set (it wins, so testers
     * can switch states without signing out of their real account), else the identity provider's
     * ID token, else null.
     *
     * A request made before auth settles (launch, before the provider has restored the saved
     * login) waits for it, up to [AUTH_SETTLE_TIMEOUT], rather than going out without a token and
     * failing. RN kept such requests from firing at all (the entry gate waited) and never let a
     * 401 on a token-less request sign anyone out; the network layer keeps that second rule.
     *
     * A failing store or provider means "no token", never a crash (RN parity).
     */
    override suspend fun token(): String? {
        if (_status.value == SessionStatus.Loading) {
            withTimeoutOrNull(authSettleTimeout) { _status.first { it != SessionStatus.Loading } }
        }
        personaTokens.get()?.let { return it }
        return quietly { identity.idToken(forceRefresh = false) }
    }

    private suspend fun identitySignOut() {
        // Signing out must always succeed from the user's point of view.
        quietly { identity.signOut() }
        // The provider reports this sign-out on authState too. The wipe for it happens right
        // here, so record it now and let that echo be a no-op rather than a second wipe.
        if (uidReported && quietly { identity.currentUserId() } == null) lastUid = null
    }

    private suspend fun wipe() {
        sessionSerial.incrementAndGet()
        wiper.forgetCachedData()
    }

    private fun setStatus(next: SessionStatus) {
        if (_status.value == next) return
        _status.value = next
        sessionSerial.incrementAndGet()
    }

    companion object {
        /**
         * How long a request waits for auth to settle on launch. Under the network layer's 15 s
         * call deadline, which also covers the wait.
         */
        val AUTH_SETTLE_TIMEOUT: Duration = 5.seconds
    }
}
