package timeshealth.app.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import timeshealth.app.ui.components.ThSpinner
import timeshealth.app.ui.components.dashedBorder
import timeshealth.app.ui.theme.AmberWarn
import timeshealth.app.ui.theme.Carbon950
import timeshealth.app.ui.theme.CoralTint
import timeshealth.app.ui.theme.CrimsonAlert
import timeshealth.app.ui.theme.DisabledContent
import timeshealth.app.ui.theme.PREVIEW_CANVAS_ARGB
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlumBrand
import timeshealth.app.ui.theme.Radii
import timeshealth.app.ui.theme.RnType
import timeshealth.app.ui.theme.Spacing
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThShapes
import timeshealth.app.ui.theme.TimesHealthTheme

/** Everything the sign-in card can ask for (hoisted to [LoginViewModel]). */
@Immutable
data class AuthActions(
    val onPhoneChange: (String) -> Unit,
    val onOtpChange: (String) -> Unit,
    val onEmailChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onGoogle: () -> Unit,
    val onSendOtp: () -> Unit,
    val onVerifyOtp: () -> Unit,
    val onShowEmail: () -> Unit,
    val onToggleCreateAccount: () -> Unit,
    val onSubmitEmail: () -> Unit,
    val onForgotPassword: () -> Unit,
    val onBackToMenu: () -> Unit,
    val onPersona: (DevPersona) -> Unit,
) {
    companion object {
        /** No-op actions for previews and tests. */
        val None = AuthActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

/**
 * The sign-in card's content (LoginScreenKt's Card body, 24dp padding): the
 * menu (Google / mobile + OTP / links), the OTP entry, or email, then the QA
 * personas (menu only, test builds only) and the legal line.
 */
@Composable
fun AuthCard(
    state: LoginUiState,
    actions: AuthActions,
    onNextHighlight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        when (state.mode) {
            AuthMode.MENU -> MenuFace(state, actions, onNextHighlight)
            AuthMode.OTP -> OtpFace(state, actions)
            AuthMode.EMAIL -> EmailFace(state, actions)
        }
        // Compliance line: ours, not the design's; kept quiet.
        Text(
            "By continuing you agree to our Privacy & Terms.",
            style = RnType.bodySmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.Xl),
        )
    }
}

/** LoginScreenKt's card, control for control; RN additions marked. */
@Composable
private fun ColumnScope.MenuFace(state: LoginUiState, actions: AuthActions, onNextHighlight: () -> Unit) {
    val phoneFocus = remember { FocusRequester() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Log In or Sign Up",
            style = MaterialTheme.typography.headlineLarge,
            color = TextPrimary,
            fontFamily = ThFonts.Serif,
            modifier = Modifier.weight(1f, fill = false),
        )
        TextButton(onClick = onNextHighlight) {
            Text("Next Highlight →", color = PlumBrand, fontSize = 11.sp)
        }
    }
    Text(
        "One account connects your Yoga subscription, race bibs & dietitian consults.",
        style = MaterialTheme.typography.bodySmall,
        color = TextMuted,
    )
    Spacer(Modifier.height(Spacing.X4l))

    OutlinedButton(
        onClick = actions.onGoogle,
        enabled = state.busy == null,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = ThShapes.Md,
    ) {
        if (state.busy == AuthAction.GOOGLE) {
            ThSpinner(color = TextPrimary)
        } else {
            Text("G  Continue with Google", color = TextPrimary, fontWeight = FontWeight.Bold)
        }
    }
    Spacer(Modifier.height(Spacing.Lg))

    OutlinedTextField(
        value = state.phone,
        onValueChange = actions.onPhoneChange,
        modifier = Modifier.fillMaxWidth().focusRequester(phoneFocus),
        label = { Text("Mobile number") },
        // M3 shows the prefix once the label has floated, as RN imitated.
        prefix = { Text("+91 ") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        shape = ThShapes.Md,
    )
    Spacer(Modifier.height(Spacing.Lg))

    DarkButton(
        label = "Send OTP & Log In",
        busy = state.busy == AuthAction.SEND_OTP,
        enabled = state.canSendOtp,
        onClick = actions.onSendOtp,
    )
    ErrorLine(state.error)
    Spacer(Modifier.height(Spacing.Sm))

    // The design jumps straight to onboarding here. Login is mandatory (§5), so
    // the honest "new here?" is: sign up with your number; the Gate then routes
    // every new account through the 4-step personalisation. Tapping it starts
    // that by focusing the number field (RN behaviour).
    CenteredLink(
        "New here? Walk through 4-step personalisation →",
        onClick = { phoneFocus.requestFocus() },
        fontSize = 11.5.sp,
    )
    // RN addition: email sign-in.
    CenteredLink("Continue with email instead", onClick = actions.onShowEmail)

    if (state.personas.isNotEmpty()) {
        PersonasBox(state, actions.onPersona)
    }
}

@Composable
private fun ColumnScope.OtpFace(state: LoginUiState, actions: AuthActions) {
    Text("Enter the code", style = MaterialTheme.typography.headlineLarge, fontFamily = ThFonts.Serif)
    Text(
        "OTP sent to ${state.e164.orEmpty()}",
        style = MaterialTheme.typography.bodySmall,
        color = TextMuted,
        modifier = Modifier.padding(bottom = Spacing.X4l),
    )
    OutlinedTextField(
        value = state.otp,
        onValueChange = actions.onOtpChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("6-digit code") },
        singleLine = true,
        textStyle = TextStyle(fontSize = 20.sp, letterSpacing = 8.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        shape = ThShapes.Md,
    )
    ErrorLine(state.error)
    Spacer(Modifier.height(Spacing.Lg))
    DarkButton(
        label = "Verify & Log In",
        busy = state.busy == AuthAction.VERIFY_OTP,
        enabled = state.canVerify,
        onClick = actions.onVerifyOtp,
    )
    Spacer(Modifier.height(Spacing.Sm))
    CenteredLink("Use a different number", onClick = actions.onBackToMenu)
}

@Composable
private fun ColumnScope.EmailFace(state: LoginUiState, actions: AuthActions) {
    Text(
        if (state.createAccount) "Create your account" else "Log in with email",
        style = MaterialTheme.typography.headlineLarge,
        fontFamily = ThFonts.Serif,
        modifier = Modifier.padding(bottom = Spacing.X4l),
    )
    OutlinedTextField(
        value = state.email,
        onValueChange = actions.onEmailChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Email address") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        shape = ThShapes.Md,
    )
    Spacer(Modifier.height(Spacing.Lg))
    OutlinedTextField(
        value = state.password,
        onValueChange = actions.onPasswordChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(if (state.createAccount) "Choose a password (6+ characters)" else "Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = ThShapes.Md,
    )
    ErrorLine(state.error)
    if (state.notice != null) {
        Text(
            state.notice,
            style = RnType.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = Spacing.Lg),
        )
    }
    Spacer(Modifier.height(Spacing.Lg))
    DarkButton(
        label = if (state.createAccount) "Create Account" else "Log In",
        busy = state.busy == AuthAction.EMAIL,
        enabled = state.canSubmitEmail,
        onClick = actions.onSubmitEmail,
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.Sm),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextLink(
            if (state.createAccount) "Have an account? Log in" else "New here? Create account",
            onClick = actions.onToggleCreateAccount,
        )
        if (!state.createAccount) {
            TextLink(
                "Forgot password?",
                onClick = actions.onForgotPassword,
                enabled = state.emailValid && state.busy == null,
            )
        }
    }
    CenteredLink("← Other ways to log in", onClick = actions.onBackToMenu)
}

/**
 * LoginScreenKt's primary action: a 50dp Carbon950 M3 Button, 12dp corners,
 * label Bold white. Disabled uses M3's colours (onSurface at 12% / 38%).
 */
@Composable
private fun DarkButton(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = ThShapes.Md,
        colors = if (busy) {
            ButtonDefaults.buttonColors(containerColor = Carbon950, disabledContainerColor = Carbon950)
        } else {
            ButtonDefaults.buttonColors(containerColor = Carbon950)
        },
    ) {
        if (busy) {
            ThSpinner(color = PaperWhite)
        } else {
            Text(
                label,
                color = if (enabled) PaperWhite else DisabledContent,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** An M3 TextButton in the design's plum link style (it inherits labelLarge's weight). */
@Composable
private fun TextLink(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 12.sp,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(
            label,
            color = if (enabled) PlumBrand else TextMuted,
            fontSize = fontSize,
            textAlign = TextAlign.Center,
        )
    }
}

/** [TextLink] centred in the card, as the design's `align(CenterHorizontally)` links. */
@Composable
private fun ColumnScope.CenteredLink(label: String, onClick: () -> Unit, fontSize: TextUnit = 12.sp) {
    TextLink(label, onClick, Modifier.align(Alignment.CenterHorizontally), fontSize)
}

/** RN ErrorLine: coral-tint box, 8dp corners, 12dp padding, crimson icon + text. */
@Composable
private fun ErrorLine(error: String?) {
    if (error == null) return
    Row(
        modifier = Modifier
            .padding(top = Spacing.Lg)
            .fillMaxWidth()
            .background(CoralTint, ThShapes.Sm)
            .padding(Spacing.Xl)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Error, contentDescription = null, modifier = Modifier.size(16.dp), tint = CrimsonAlert)
        Text(error, style = RnType.bodyMedium, color = CrimsonAlert, modifier = Modifier.weight(1f))
    }
}

/**
 * QA personas: dev builds and DEV_SIGNIN test APKs only. A server must also
 * run with ALLOW_DEV_TOKENS=true to accept them, and refuses to in production.
 * Amber dashed box, 12dp corners, 16dp padding (RN devBox).
 */
@Composable
private fun PersonasBox(state: LoginUiState, onPersona: (DevPersona) -> Unit) {
    Column(
        modifier = Modifier
            .padding(top = Spacing.Xl)
            .fillMaxWidth()
            .dashedBorder(width = 1.dp, color = AmberWarn, radius = Radii.Md)
            .padding(Spacing.X3l),
    ) {
        Text(
            "Test personas · not in release builds".uppercase(Locale.ROOT),
            style = RnType.labelSmall,
            color = AmberWarn,
            modifier = Modifier.padding(bottom = Spacing.Md),
        )
        state.personas.forEach { persona ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = state.busy == null, role = Role.Button) { onPersona(persona) }
                    .padding(vertical = Spacing.Lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(persona.label, style = RnType.bodyLarge, modifier = Modifier.weight(1f))
                if (state.busyPersona == persona.token) ThSpinner()
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = PREVIEW_CANVAS_ARGB, heightDp = 900)
@Composable
private fun AuthCardMenuPreview() {
    TimesHealthTheme {
        AuthCard(
            state = LoginUiState(personas = DEV_PERSONAS, error = SignInCopy.GOOGLE_UNAVAILABLE),
            actions = AuthActions.None,
            onNextHighlight = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = PREVIEW_CANVAS_ARGB, heightDp = 600)
@Composable
private fun AuthCardEmailPreview() {
    TimesHealthTheme {
        AuthCard(
            state = LoginUiState(mode = AuthMode.EMAIL, email = "priya@example.com"),
            actions = AuthActions.None,
            onNextHighlight = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}
