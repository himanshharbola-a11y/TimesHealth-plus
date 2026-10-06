package timeshealth.app.ui.gate

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import timeshealth.app.ui.components.ThSpinner
import timeshealth.app.ui.theme.BorderRule
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.PREVIEW_CANVAS_ARGB
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.RnType
import timeshealth.app.ui.theme.Spacing
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.ThShapes
import timeshealth.app.ui.theme.TimesHealthTheme

/**
 * The Gate destination: collects [GateViewModel] and hands navigation up.
 *
 * @param onDecided called once with where to go; the caller replaces the Gate.
 * @param onOpenPass opens a saved race pass (the bib) over the Gate.
 */
@Composable
fun GateRoute(
    viewModel: GateViewModel,
    onDecided: (GateDestination) -> Unit,
    onOpenPass: (eventId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val decided by rememberUpdatedState(onDecided)
    val phase = state.phase
    LaunchedEffect(phase) {
        if (phase is GatePhase.Done) decided(phase.destination)
    }
    val context = LocalContext.current
    GateScreen(
        state = state,
        onRetry = viewModel::retry,
        onUpdate = { openPlayStore(context, viewModel.applicationId) },
        onOpenPass = onOpenPass,
    )
}

/**
 * The Gate's faces (stateless). No design file covers this screen; it is the
 * RN index.tsx's, on the design tokens: the coral serif wordmark, centred copy,
 * a coral 12dp button, and the saved race passes as white bordered rows.
 */
@Composable
fun GateScreen(
    state: GateUiState,
    onRetry: () -> Unit,
    onUpdate: () -> Unit,
    onOpenPass: (eventId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasBg)
            .systemBarsPadding()
            .padding(horizontal = Spacing.X8l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Md, Alignment.CenterVertically),
    ) {
        when (val phase = state.phase) {
            GatePhase.Starting, is GatePhase.Done -> {
                Wordmark()
                Text(
                    "One login across Yoga, Marathon & Diet.",
                    style = RnType.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                ThSpinner(Modifier.padding(top = Spacing.X6l))
                if (state.showPassesOnSplash) PassShortcuts(state.passes, onOpenPass)
            }

            is GatePhase.UpdateRequired -> {
                Wordmark()
                Title("Please update the app")
                Body(
                    "This version is no longer supported. Update to keep joining classes and " +
                        "using your race pass.",
                )
                CoralButton(label = "Update now", onClick = onUpdate)
                Text("Version ${phase.currentVersion}", style = RnType.bodySmall, color = TextMuted)
            }

            is GatePhase.Maintenance -> {
                Title("Back shortly")
                Body(phase.message)
                CoralButton(label = "Try again", onClick = onRetry, busy = phase.retrying)
            }

            is GatePhase.Unreachable -> {
                Title("We couldn’t reach TimesHealth+")
                Body("Check your connection and try again.")
                CoralButton(label = "Try again", onClick = onRetry, busy = phase.retrying)
                PassShortcuts(state.passes, onOpenPass)
            }
        }
    }
}

/** "TimesHealth+" in the coral serif (RN `type.displaySmall`, coral, 4dp under). */
@Composable
private fun Wordmark() {
    Text(
        "TimesHealth+",
        style = RnType.displaySmall,
        color = CoralBrand,
        modifier = Modifier.padding(bottom = Spacing.Xs),
    )
}

@Composable
private fun Title(text: String) {
    Text(text, style = RnType.headlineSmall, textAlign = TextAlign.Center)
}

@Composable
private fun Body(text: String) {
    Text(text, style = RnType.bodyMedium, color = TextMuted, textAlign = TextAlign.Center)
}

/** RN gate button: coral, 12dp corners, padded 32×18, at least 180 wide, 20dp above. */
@Composable
private fun CoralButton(label: String, onClick: () -> Unit, busy: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = !busy,
        shape = ThShapes.Md,
        colors = ButtonDefaults.buttonColors(
            containerColor = CoralBrand,
            contentColor = PaperWhite,
            disabledContainerColor = CoralBrand,
            disabledContentColor = PaperWhite,
        ),
        contentPadding = PaddingValues(horizontal = Spacing.X8l, vertical = Spacing.X4l),
        modifier = Modifier.padding(top = Spacing.X5l).widthIn(min = 180.dp),
    ) {
        if (busy) {
            ThSpinner(color = PaperWhite)
        } else {
            Text(label, style = RnType.titleMedium, color = PaperWhite)
        }
    }
}

/**
 * "No signal? Your race pass is saved on this phone." and one row per saved
 * pass. Opens the bib from the phone, no network needed (§8.3).
 */
@Composable
private fun ColumnScope.PassShortcuts(passes: List<PassShortcut>, onOpenPass: (String) -> Unit) {
    if (passes.isEmpty()) return
    Spacer(Modifier.height(Spacing.X7l - Spacing.Md))
    Text(
        "No signal? Your race pass is saved on this phone.",
        style = RnType.bodySmall,
        color = TextMuted,
        textAlign = TextAlign.Center,
    )
    passes.forEach { pass ->
        OutlinedButton(
            onClick = { onOpenPass(pass.eventId) },
            shape = ThShapes.Md,
            border = BorderStroke(1.dp, BorderRule),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = PaperWhite, contentColor = TextPrimary),
            contentPadding = PaddingValues(horizontal = Spacing.X5l, vertical = Spacing.X3l),
            modifier = Modifier.padding(top = Spacing.Xxs),
        ) {
            Icon(Icons.Filled.QrCode, contentDescription = null, modifier = Modifier.size(18.dp), tint = TextPrimary)
            Spacer(Modifier.size(Spacing.Lg))
            Text(
                "Open race pass · ${pass.eventName}",
                style = RnType.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The Play Store app if it's there, else the web listing. */
private fun openPlayStore(context: Context, applicationId: String) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$applicationId"))
    try {
        context.startActivity(market)
    } catch (_: ActivityNotFoundException) {
        val web = Uri.parse("https://play.google.com/store/apps/details?id=$applicationId")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, web))
        } catch (_: ActivityNotFoundException) {
            // No store and no browser: nothing more the app can do.
        }
    }
}

@Preview(showBackground = true, backgroundColor = PREVIEW_CANVAS_ARGB, heightDp = 640)
@Composable
private fun GateSplashPreview() {
    TimesHealthTheme {
        GateScreen(
            state = GateUiState(slow = true, passes = listOf(PassShortcut("e1", "Hyderabad Half Marathon"))),
            onRetry = {}, onUpdate = {}, onOpenPass = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = PREVIEW_CANVAS_ARGB, heightDp = 640)
@Composable
private fun GateUnreachablePreview() {
    TimesHealthTheme {
        GateScreen(
            state = GateUiState(
                phase = GatePhase.Unreachable(),
                passes = listOf(PassShortcut("e1", "Hyderabad Half Marathon")),
            ),
            onRetry = {}, onUpdate = {}, onOpenPass = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = PREVIEW_CANVAS_ARGB, heightDp = 640)
@Composable
private fun GateUpdatePreview() {
    TimesHealthTheme {
        Box {
            GateScreen(
                state = GateUiState(phase = GatePhase.UpdateRequired("1.0.0")),
                onRetry = {}, onUpdate = {}, onOpenPass = {},
            )
        }
    }
}
