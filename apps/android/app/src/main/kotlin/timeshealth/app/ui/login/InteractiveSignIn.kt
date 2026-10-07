package timeshealth.app.ui.login

import androidx.compose.runtime.Immutable
import javax.inject.Inject

/**
 * The interactive half of sign-in: the login card's Google / phone OTP / email
 * buttons. Port target of apps/mobile/src/lib/identity.ts (`signInWithGoogle`,
 * `startPhoneSignIn`, `signInWithEmail`, `sendPasswordReset`).
 *
 * How it fits with the rest: a successful call here signs the user in with the
 * identity provider; the provider then reports the new uid through
 * [timeshealth.app.core.data.IdentityGateway.authState], SessionRepository
 * flips to SignedIn, and the login screen navigates on THAT, never straight
 * after the call (the RN rule: navigating first would let the Gate bounce the
 * user back to login before the provider has caught up).
 *
 * Today only [UnavailableSignIn] is bound (wiring/UiWiring.kt): the Firebase
 * phone and Google flows are wired in a later step, behind this interface, by
 * a `FirebaseInteractiveSignIn` bound when `BuildConfig.FIREBASE_ENABLED`.
 * Implementations that need an Activity (Credential Manager, the reCAPTCHA
 * fallback of phone auth) get it from an activity holder, not from callers, so
 * the ViewModel stays free of Android types.
 *
 * Every method throws [SignInException] with user-facing text on failure.
 */
interface InteractiveSignIn {
    /** Which methods this build can offer. The card still shows the design's controls. */
    val availability: SignInAvailability

    suspend fun signInWithGoogle()

    /** Sends the OTP to [e164] (`+91XXXXXXXXXX`); confirm with the returned challenge. */
    suspend fun startPhoneSignIn(e164: String): PhoneChallenge

    /** Signs in, or with [createAccount] signs up, with email and password. */
    suspend fun signInWithEmail(email: String, password: String, createAccount: Boolean)

    suspend fun sendPasswordReset(email: String)
}

/** A sent OTP waiting for its code. */
fun interface PhoneChallenge {
    suspend fun confirm(code: String)
}

@Immutable
data class SignInAvailability(
    val google: Boolean,
    val phone: Boolean,
    val email: Boolean,
    /** Shown under the OTP / email forms by a stand-in provider (demo builds only). */
    val demoHint: String? = null,
) {
    /** Anything at all: RN `identityConfigured`. */
    val any: Boolean get() = google || phone || email

    companion object {
        val None = SignInAvailability(google = false, phone = false, email = false)
    }
}

/**
 * A sign-in step failed. [message] is shown as-is. [cancelled] means the user
 * backed out (closed the Google sheet): show nothing.
 */
class SignInException(message: String, val cancelled: Boolean = false) : Exception(message)

/** User-facing copy for methods this build doesn't offer. */
object SignInCopy {
    const val GOOGLE_UNAVAILABLE = "Google sign-in isn’t available in this build."
    const val PHONE_UNAVAILABLE = "Phone sign-in isn’t available in this build."
    const val EMAIL_UNAVAILABLE = "Email sign-in isn’t available in this build."
    const val GENERIC_FAILURE = "Sign-in failed. Please try again."
}

/**
 * The binding until the Firebase flows land: offers nothing, and says so
 * clearly if a control is used anyway. QA personas still sign in (debug /
 * DEV_SIGNIN builds), exactly like the RN app's unconfigured builds.
 */
class UnavailableSignIn @Inject constructor() : InteractiveSignIn {
    override val availability: SignInAvailability = SignInAvailability.None

    override suspend fun signInWithGoogle() = throw SignInException(SignInCopy.GOOGLE_UNAVAILABLE)

    override suspend fun startPhoneSignIn(e164: String): PhoneChallenge =
        throw SignInException(SignInCopy.PHONE_UNAVAILABLE)

    override suspend fun signInWithEmail(email: String, password: String, createAccount: Boolean) =
        throw SignInException(SignInCopy.EMAIL_UNAVAILABLE)

    override suspend fun sendPasswordReset(email: String) = throw SignInException(SignInCopy.EMAIL_UNAVAILABLE)
}
