package timeshealth.app.ui.login

import java.security.MessageDigest
import javax.inject.Inject
import timeshealth.app.ui.session.SessionGateway

/**
 * STAND-IN for the TIL SSO SDK, so the login flows (Google, mobile + OTP,
 * email) can be walked through end to end before the real provider is
 * plugged in. Bound only in debug / DEV_SIGNIN builds (wiring/UiWiring.kt);
 * release builds keep [UnavailableSignIn] until the tech team binds the real
 * implementation of [InteractiveSignIn] there. No screen changes either way.
 *
 * Each method signs in with a QA identity derived from what was typed, through
 * the same persona path the test personas use, so the server (with
 * ALLOW_DEV_TOKENS, never in production) treats it as a real account: a new
 * number or email is a new user and goes through onboarding.
 *
 * The demo OTP is [DEMO_OTP]; the login card shows it as a hint.
 */
class DemoSignIn @Inject constructor(private val session: SessionGateway) : InteractiveSignIn {

    override val availability = SignInAvailability(
        google = true, phone = true, email = true,
        demoHint = "Demo sign-in: use OTP $DEMO_OTP. Real SSO is plugged in by the tech team.",
    )

    override suspend fun signInWithGoogle() = signIn("google")

    override suspend fun startPhoneSignIn(e164: String): PhoneChallenge = PhoneChallenge { code ->
        if (code.trim() != DEMO_OTP) throw SignInException("That code isn’t right. Check it and try again.")
        signIn("ph_" + e164.filter(Char::isDigit))
    }

    override suspend fun signInWithEmail(email: String, password: String, createAccount: Boolean) {
        if (password.length < 6) throw SignInException("Use at least 6 characters for the password.")
        signIn("em_" + hash(email.trim().lowercase()))
    }

    override suspend fun sendPasswordReset(email: String) = Unit

    /** A uid-only persona token (no email/phone, which the server confines to the QA ranges). */
    private suspend fun signIn(key: String) {
        try {
            session.signInPersona("qa_demo_$key||")
        } catch (e: SignInException) {
            throw e
        } catch (e: Exception) {
            throw SignInException("Couldn’t sign in. Check your connection and try again.")
        }
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    companion object {
        const val DEMO_OTP = "123456"
    }
}
