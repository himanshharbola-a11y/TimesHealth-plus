package timeshealth.app.ui.login

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timeshealth.app.AppBuildInfo
import timeshealth.app.core.data.session.SessionStatus
import timeshealth.app.core.domain.isValidEmail
import timeshealth.app.core.domain.toIndianE164
import timeshealth.app.ui.session.SessionGateway

/** Which face of the sign-in card is showing (RN AuthPanel `mode`). */
enum class AuthMode { MENU, OTP, EMAIL }

/** The step currently running, so its button shows a spinner and the rest disable. */
enum class AuthAction { GOOGLE, SEND_OTP, VERIFY_OTP, EMAIL, RESET, PERSONA }

@Immutable
data class LoginUiState(
    val mode: AuthMode = AuthMode.MENU,
    val methods: SignInAvailability = SignInAvailability.None,
    /** QA personas; empty unless the build allows them. */
    val personas: List<DevPersona> = emptyList(),
    val busy: AuthAction? = null,
    /** The persona being signed in, for its row's spinner. */
    val busyPersona: String? = null,
    val error: String? = null,
    val notice: String? = null,
    /** 10 national digits, no prefix (the field shows +91). */
    val phone: String = "",
    val otp: String = "",
    val email: String = "",
    val password: String = "",
    val createAccount: Boolean = false,
) {
    /** `+91XXXXXXXXXX` when [phone] is a valid Indian mobile, else null. */
    val e164: String? get() = toIndianE164(phone)
    val canSendOtp: Boolean get() = e164 != null && busy == null
    val canVerify: Boolean get() = otp.length == OTP_LENGTH && busy == null
    val emailValid: Boolean get() = isValidEmail(email.trim())
    val canSubmitEmail: Boolean get() = emailValid && password.length >= MIN_PASSWORD && busy == null

    /**
     * The splash CTA. Never names a method this build can't offer (RN rule):
     * the design's "Continue with Google / Mobile" when Google is available.
     */
    val continueLabel: String get() = when {
        !methods.any -> "Continue"
        methods.google -> "Continue with Google / Mobile"
        else -> "Continue with Mobile"
    }

    companion object {
        const val OTP_LENGTH = 6
        const val MIN_PASSWORD = 6
        const val PHONE_DIGITS = 10
    }
}

/**
 * The sign-in card: port of login.tsx's AuthPanel logic.
 *
 * Login is mandatory (PRD §5); there is no guest mode. A successful step never
 * navigates by itself: [signedIn] turns true when the SESSION reports the new
 * user, and the screen navigates on that (back to the Gate, which decides
 * onboarding vs tabs).
 *
 * Google / phone OTP / email go through [InteractiveSignIn]; until the Firebase
 * flows are wired it reports nothing available and every control explains
 * that. QA personas (debug / DEV_SIGNIN builds) sign in through
 * [SessionGateway.signInPersona].
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val session: SessionGateway,
    private val signIn: InteractiveSignIn,
    build: AppBuildInfo,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LoginUiState(
            methods = signIn.availability,
            personas = if (build.personasEnabled) DEV_PERSONAS else emptyList(),
        ),
    )
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    /** True once someone is signed in: the screen goes back to the Gate. */
    val signedIn: StateFlow<Boolean> = session.status
        .map { it is SessionStatus.SignedIn }
        .stateIn(viewModelScope, SharingStarted.Eagerly, session.status.value is SessionStatus.SignedIn)

    private var phoneChallenge: PhoneChallenge? = null

    // ── Fields ──────────────────────────────────────────────────────────────

    fun onPhoneChange(text: String) =
        _state.update { it.copy(phone = text.filter(Char::isDigit).take(LoginUiState.PHONE_DIGITS), error = null) }

    fun onOtpChange(text: String) =
        _state.update { it.copy(otp = text.filter(Char::isDigit).take(LoginUiState.OTP_LENGTH), error = null) }

    fun onEmailChange(text: String) = _state.update { it.copy(email = text, error = null) }

    fun onPasswordChange(text: String) = _state.update { it.copy(password = text, error = null) }

    // ── Modes ───────────────────────────────────────────────────────────────

    fun showEmail() = _state.update { it.copy(mode = AuthMode.EMAIL, error = null, notice = null) }

    fun toggleCreateAccount() = _state.update { it.copy(createAccount = !it.createAccount, error = null) }

    /** "Use a different number" / "← Other ways to log in". */
    fun backToMenu() {
        phoneChallenge = null
        _state.update { it.copy(mode = AuthMode.MENU, otp = "", error = null, notice = null) }
    }

    // ── Steps ───────────────────────────────────────────────────────────────

    fun continueWithGoogle() {
        if (!_state.value.methods.google) return fail(SignInCopy.GOOGLE_UNAVAILABLE)
        run(AuthAction.GOOGLE) { signIn.signInWithGoogle() }
    }

    fun sendOtp() {
        val e164 = _state.value.e164 ?: return
        if (!_state.value.methods.phone) return fail(SignInCopy.PHONE_UNAVAILABLE)
        run(AuthAction.SEND_OTP) {
            phoneChallenge = signIn.startPhoneSignIn(e164)
            _state.update { it.copy(mode = AuthMode.OTP, otp = "") }
        }
    }

    fun verifyOtp() {
        val challenge = phoneChallenge ?: return
        val code = _state.value.otp
        if (code.length != LoginUiState.OTP_LENGTH) return
        run(AuthAction.VERIFY_OTP) { challenge.confirm(code) }
    }

    fun submitEmail() {
        val s = _state.value
        if (!s.methods.email) return fail(SignInCopy.EMAIL_UNAVAILABLE)
        if (!s.canSubmitEmail) return
        run(AuthAction.EMAIL) { signIn.signInWithEmail(s.email.trim(), s.password, s.createAccount) }
    }

    fun sendPasswordReset() {
        val s = _state.value
        if (!s.methods.email) return fail(SignInCopy.EMAIL_UNAVAILABLE)
        if (!s.emailValid) return
        val email = s.email.trim()
        run(AuthAction.RESET) {
            signIn.sendPasswordReset(email)
            _state.update { it.copy(notice = "Password reset link sent to $email.") }
        }
    }

    /** QA persona sign-in. Ignored when the build doesn't allow personas. */
    fun signInPersona(persona: DevPersona) {
        if (persona !in _state.value.personas || _state.value.busy != null) return
        _state.update { it.copy(busyPersona = persona.token) }
        run(AuthAction.PERSONA) { session.signInPersona(persona.token) }
    }

    /**
     * Runs one auth step with the shared busy / error convention (RN `run`):
     * one step at a time; a [SignInException]'s text is shown as-is, a
     * cancelled one shows nothing, anything else gets the generic line.
     */
    private fun run(action: AuthAction, step: suspend () -> Unit) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = action, error = null, notice = null) }
        viewModelScope.launch {
            val error: String? = try {
                step()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignInException) {
                if (e.cancelled) null else e.message
            } catch (e: Exception) {
                SignInCopy.GENERIC_FAILURE
            }
            _state.update { it.copy(busy = null, busyPersona = null, error = error) }
        }
    }

    private fun fail(message: String) = _state.update { it.copy(error = message, notice = null) }
}
