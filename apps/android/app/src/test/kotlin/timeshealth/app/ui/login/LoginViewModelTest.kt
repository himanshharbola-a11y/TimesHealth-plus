package timeshealth.app.ui.login

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import timeshealth.app.FakeInteractiveSignIn
import timeshealth.app.FakeSessionGateway
import timeshealth.app.MainDispatcherRule
import timeshealth.app.buildInfo
import timeshealth.app.core.data.session.SessionStatus

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val session = FakeSessionGateway(SessionStatus.SignedOut)

    private fun login(
        debug: Boolean = true,
        devSignIn: Boolean = false,
        signIn: InteractiveSignIn = UnavailableSignIn(),
    ) = LoginViewModel(session, signIn, buildInfo(debug = debug, devSignIn = devSignIn))

    // ── QA personas ─────────────────────────────────────────────────────────

    @Test
    fun `personas are offered in debug builds and DEV_SIGNIN test builds only`() {
        assertThat(login(debug = true).state.value.personas).isEqualTo(DEV_PERSONAS)
        assertThat(login(debug = false, devSignIn = true).state.value.personas).isEqualTo(DEV_PERSONAS)
        assertThat(login(debug = false, devSignIn = false).state.value.personas).isEmpty()
    }

    @Test
    fun `the persona list is the RN app's, token for token`() {
        assertThat(DEV_PERSONAS.map { it.label }).containsExactly(
            "Free user", "Yoga subscriber", "Marathon registrant", "Yoga + Marathon",
            "Expired subscriber", "Race finisher",
        ).inOrder()
        assertThat(DEV_PERSONAS.first { it.label == "Yoga + Marathon" }.token)
            .isEqualTo("qa_both|both@th.test|+919000000004")
        assertThat(DEV_PERSONAS.map { it.token.substringBefore('|') })
            .containsExactly("qa_free", "qa_yoga", "qa_marathon", "qa_both", "qa_expired", "qa_finisher").inOrder()
    }

    @Test
    fun `tapping a persona signs in with its token, and the screen leaves once signed in`() = runTest {
        val vm = login()
        val yoga = DEV_PERSONAS.first { it.label == "Yoga subscriber" }
        val gate = CompletableDeferred<Unit>()
        session.personaGate = gate

        vm.signInPersona(yoga)
        runCurrent()
        assertThat(vm.state.value.busy).isEqualTo(AuthAction.PERSONA)
        assertThat(vm.state.value.busyPersona).isEqualTo(yoga.token)
        assertThat(vm.signedIn.value).isFalse()

        // A second tap while one is running does nothing.
        vm.signInPersona(DEV_PERSONAS.first())
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(session.personaTokens).containsExactly("qa_yoga|yoga@th.test|+919000000002")
        assertThat(vm.signedIn.value).isTrue()
        assertThat(vm.state.value.busy).isNull()
        assertThat(vm.state.value.busyPersona).isNull()
        assertThat(vm.state.value.error).isNull()
    }

    @Test
    fun `a persona sign-in that fails says so and frees the card`() = runTest {
        val vm = login()
        session.personaFailure = IllegalStateException("keystore unavailable")

        vm.signInPersona(DEV_PERSONAS.first())
        advanceUntilIdle()

        assertThat(vm.state.value.error).isEqualTo(SignInCopy.GENERIC_FAILURE)
        assertThat(vm.state.value.busy).isNull()
        assertThat(vm.signedIn.value).isFalse()
    }

    @Test
    fun `personas are refused in a build that doesn't offer them`() = runTest {
        val vm = login(debug = false, devSignIn = false)
        vm.signInPersona(DEV_PERSONAS.first())
        advanceUntilIdle()
        assertThat(session.personaTokens).isEmpty()
    }

    @Test
    fun `already signed in (a real account restored) leaves at once`() = runTest {
        session.emit(SessionStatus.SignedIn(timeshealth.app.core.data.session.SignInMethod.IDENTITY))
        assertThat(login().signedIn.value).isTrue()
    }

    // ── Google / phone / email in a build without them ──────────────────────

    @Test
    fun `without Firebase every control explains it isn't available`() = runTest {
        val vm = login()
        assertThat(vm.state.value.methods).isEqualTo(SignInAvailability.None)
        assertThat(vm.state.value.continueLabel).isEqualTo("Continue")

        vm.continueWithGoogle()
        assertThat(vm.state.value.error).isEqualTo(SignInCopy.GOOGLE_UNAVAILABLE)

        vm.onPhoneChange("98765 43210")
        assertThat(vm.state.value.error).isNull()
        vm.sendOtp()
        assertThat(vm.state.value.error).isEqualTo(SignInCopy.PHONE_UNAVAILABLE)
        assertThat(vm.state.value.mode).isEqualTo(AuthMode.MENU)

        vm.showEmail()
        vm.onEmailChange("priya@example.com")
        vm.onPasswordChange("secret1")
        vm.submitEmail()
        assertThat(vm.state.value.error).isEqualTo(SignInCopy.EMAIL_UNAVAILABLE)
    }

    // ── Phone OTP (structure for the Firebase step) ─────────────────────────

    @Test
    fun `the number field keeps 10 digits, and only an Indian mobile can get an OTP`() {
        val vm = login(signIn = FakeInteractiveSignIn())
        vm.onPhoneChange("+91 98765-43210 77")
        assertThat(vm.state.value.phone).isEqualTo("9198765432")

        vm.onPhoneChange("98765 43210")
        assertThat(vm.state.value.phone).isEqualTo("9876543210")
        assertThat(vm.state.value.e164).isEqualTo("+919876543210")
        assertThat(vm.state.value.canSendOtp).isTrue()

        vm.onPhoneChange("12345 67890") // not 6–9: not an Indian mobile
        assertThat(vm.state.value.canSendOtp).isFalse()
    }

    @Test
    fun `send OTP then verify the 6-digit code`() = runTest {
        val signIn = FakeInteractiveSignIn()
        val vm = login(signIn = signIn)
        vm.onPhoneChange("9876543210")
        vm.sendOtp()
        advanceUntilIdle()

        assertThat(signIn.phoneNumbers).containsExactly("+919876543210")
        assertThat(vm.state.value.mode).isEqualTo(AuthMode.OTP)

        vm.onOtpChange("12a34567")
        assertThat(vm.state.value.otp).isEqualTo("123456")
        vm.verifyOtp()
        advanceUntilIdle()
        assertThat(signIn.confirmedCodes).containsExactly("123456")

        vm.backToMenu()
        assertThat(vm.state.value.mode).isEqualTo(AuthMode.MENU)
        assertThat(vm.state.value.otp).isEmpty()
    }

    @Test
    fun `a provider error is shown as-is, a cancelled one not at all`() = runTest {
        val signIn = FakeInteractiveSignIn()
        val vm = login(signIn = signIn)

        signIn.failWith = SignInException("That code is wrong. Check it and try again.")
        vm.onPhoneChange("9876543210")
        vm.sendOtp()
        advanceUntilIdle()
        assertThat(vm.state.value.error).isEqualTo("That code is wrong. Check it and try again.")

        signIn.failWith = SignInException("cancelled", cancelled = true)
        vm.continueWithGoogle()
        advanceUntilIdle()
        assertThat(vm.state.value.error).isNull()
    }

    @Test
    fun `the splash CTA never names a method the build can't offer`() {
        fun label(google: Boolean, phone: Boolean) =
            LoginUiState(methods = SignInAvailability(google, phone, email = false)).continueLabel
        assertThat(label(google = true, phone = true)).isEqualTo("Continue with Google / Mobile")
        assertThat(label(google = false, phone = true)).isEqualTo("Continue with Mobile")
        assertThat(label(google = false, phone = false)).isEqualTo("Continue")
    }

    @Test
    fun `password reset needs a valid email and confirms where it went`() = runTest {
        val vm = login(signIn = FakeInteractiveSignIn())
        vm.showEmail()
        vm.onEmailChange("not-an-email")
        vm.sendPasswordReset()
        advanceUntilIdle()
        assertThat(vm.state.value.notice).isNull()

        vm.onEmailChange("  priya@example.com ")
        vm.sendPasswordReset()
        advanceUntilIdle()
        assertThat(vm.state.value.notice).isEqualTo("Password reset link sent to priya@example.com.")
    }
}
