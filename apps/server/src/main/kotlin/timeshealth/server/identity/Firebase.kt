package timeshealth.server.identity

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Clock
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import timeshealth.server.config.ServerEnv

/**
 * Firebase app bootstrap (identity/firebase.ts `initFirebaseApp`). Firebase is today's identity
 * provider AND the FCM push credential — two roles. When Times SSO replaces Firebase for login,
 * push stays on FCM, so the push port must use this holder directly.
 */
class FirebaseAppHolder(private val env: ServerEnv) {
    @Volatile
    private var app: FirebaseApp? = null

    /** Idempotent. False when no service account is configured. A broken one throws. */
    @Synchronized
    fun init(): Boolean {
        if (app != null) return true
        val account = loadServiceAccount() ?: return false
        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(ByteArrayInputStream(account)))
            .build()
        app = FirebaseApp.getApps().firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(options)
        return true
    }

    /** The initialised app, or null when Firebase is not configured. */
    fun appOrNull(): FirebaseApp? = if (init()) app else null

    private fun loadServiceAccount(): ByteArray? {
        if (env.firebaseServiceAccountFile.isNotEmpty()) {
            // Node: path.resolve(process.cwd(), file). The .env value ../../.secrets/… resolves to
            // the same file from apps/api and from apps/server.
            val file = Paths.get(System.getProperty("user.dir")).resolve(env.firebaseServiceAccountFile).normalize()
            return Files.readAllBytes(file)
        }
        if (env.firebaseServiceAccountB64.isNotEmpty()) {
            // Buffer.from(x, 'base64') ignores characters outside the alphabet; so does the MIME decoder.
            return Base64.getMimeDecoder().decode(env.firebaseServiceAccountB64)
        }
        return null
    }
}

/** The claims this server reads from a verified Firebase ID token. */
data class DecodedIdToken(
    val uid: String,
    val email: String?,
    val emailVerified: Boolean,
    val phone: String?,
    val name: String?,
    /** `auth_time`, seconds. Null when absent (then never "revoked", as in Node: NaN < x). */
    val authTimeSeconds: Long?,
)

data class FirebaseUserState(val tokensValidAfterMs: Long, val disabled: Boolean)

/** A Firebase Auth error, with the Node SDK's code string ("auth/id-token-expired"). */
class FirebaseAuthCodeException(val code: String, cause: Throwable? = null) : RuntimeException(code, cause)

/**
 * The three Firebase Auth calls this server makes. An interface so the provider's rules
 * (definitive codes, the revocation cache) are tested without Google.
 */
interface FirebaseAuthGateway {
    fun verifyIdToken(token: String): DecodedIdToken
    fun getUser(uid: String): FirebaseUserState
    fun deleteUser(uid: String)
}

/** The Firebase Admin Java SDK behind [FirebaseAuthGateway]. Errors become Node-style codes. */
class SdkFirebaseAuthGateway(private val holder: FirebaseAppHolder) : FirebaseAuthGateway {
    private fun auth(): FirebaseAuth =
        FirebaseAuth.getInstance(holder.appOrNull() ?: throw IllegalStateException("Firebase is not configured on this server"))

    override fun verifyIdToken(token: String): DecodedIdToken = translate {
        // Signature + expiry checked locally against Google's cached public keys (checkRevoked
        // = false; revocation is the cached getUser check in FirebaseIdentityProvider).
        val t = auth().verifyIdToken(token)
        DecodedIdToken(
            uid = t.uid,
            email = t.email,
            emailVerified = t.isEmailVerified,
            phone = t.claims["phone_number"] as? String,
            name = t.name,
            authTimeSeconds = (t.claims["auth_time"] as? Number)?.toLong(),
        )
    }

    override fun getUser(uid: String): FirebaseUserState = translate {
        val u = auth().getUser(uid)
        FirebaseUserState(tokensValidAfterMs = u.tokensValidAfterTimestamp, disabled = u.isDisabled)
    }

    override fun deleteUser(uid: String) = translate { auth().deleteUser(uid) }

    private inline fun <T> translate(block: () -> T): T = try {
        block()
    } catch (e: FirebaseAuthException) {
        throw FirebaseAuthCodeException(nodeCode(e.authErrorCode), e)
    } catch (e: IllegalArgumentException) {
        // The Node SDK's 'auth/argument-error': an empty or structurally invalid token.
        throw FirebaseAuthCodeException("auth/argument-error", e)
    }

    private fun nodeCode(code: AuthErrorCode?): String = when (code) {
        AuthErrorCode.EXPIRED_ID_TOKEN -> "auth/id-token-expired"
        AuthErrorCode.REVOKED_ID_TOKEN -> "auth/id-token-revoked"
        AuthErrorCode.INVALID_ID_TOKEN -> "auth/invalid-id-token"
        AuthErrorCode.USER_DISABLED -> "auth/user-disabled"
        AuthErrorCode.USER_NOT_FOUND -> "auth/user-not-found"
        null -> "auth/internal-error"
        else -> "auth/" + code.name.lowercase().replace('_', '-')
    }
}

/**
 * Port of identity/firebase.ts `firebaseProvider`.
 *
 * Revocation is checked per user at most every [REVOCATION_TTL_MS] instead of on every request:
 * `verifyIdToken(token, true)` costs a Firebase network call per API request (the 5:59 AM batch
 * spike), and an outage of that call used to sign everybody out. ID tokens expire hourly anyway.
 */
class FirebaseIdentityProvider(
    private val holder: FirebaseAppHolder,
    private val gateway: FirebaseAuthGateway,
    private val clock: Clock,
) : IdentityProvider {
    override val name = "firebase"

    private data class Revocation(val validAfterMs: Long, val disabled: Boolean, val checkedAt: Long)

    private val revocation = ConcurrentHashMap<String, Revocation>()

    override fun configured(): Boolean = holder.init()

    override fun verify(token: String): VerifiedIdentity {
        if (!holder.init()) throw IllegalStateException("Firebase is not configured on this server")
        val decoded = try {
            gateway.verifyIdToken(token)
        } catch (e: FirebaseAuthCodeException) {
            if (e.code in DEFINITIVE) throw TokenRejected(e.code)
            throw e // network / internal → 503, not a sign-out
        }

        val state = revocationState(decoded.uid)
        if (state?.disabled == true) throw TokenRejected("auth/user-disabled")
        // Tokens issued before "sign out everywhere" / a support revocation die.
        val authTime = decoded.authTimeSeconds
        if (state != null && authTime != null && authTime * 1000 < state.validAfterMs) {
            throw TokenRejected("auth/id-token-revoked")
        }

        return VerifiedIdentity(
            uid = decoded.uid,
            email = decoded.email,
            emailVerified = decoded.emailVerified,
            phone = decoded.phone,
            name = decoded.name,
        )
    }

    private fun revocationState(uid: String): Revocation? {
        val now = clock.millis()
        val cached = revocation[uid]
        if (cached != null && now - cached.checkedAt < REVOCATION_TTL_MS) return cached
        return try {
            val user = gateway.getUser(uid)
            val state = Revocation(user.tokensValidAfterMs, user.disabled, now)
            if (revocation.size > MAX_CACHED) revocation.clear() // bounded memory
            revocation[uid] = state
            state
        } catch (e: FirebaseAuthCodeException) {
            if (e.code == "auth/user-not-found") throw TokenRejected("user not found")
            cached
        } catch (e: Exception) {
            // Firebase unreachable: fall back to the last known state rather than locking a valid
            // user out (or, worse, signing them out).
            cached
        }
    }

    override fun deleteAccount(uid: String): Boolean {
        if (!holder.init()) return false
        gateway.deleteUser(uid)
        revocation.remove(uid)
        return true
    }

    companion object {
        const val REVOCATION_TTL_MS = 5 * 60_000L
        private const val MAX_CACHED = 50_000

        /** firebase-admin codes that mean "this token is no good" — never an outage. */
        val DEFINITIVE = setOf(
            "auth/id-token-expired",
            "auth/id-token-revoked",
            "auth/argument-error",
            "auth/invalid-id-token",
            "auth/user-disabled",
            "auth/user-not-found",
        )
    }
}
