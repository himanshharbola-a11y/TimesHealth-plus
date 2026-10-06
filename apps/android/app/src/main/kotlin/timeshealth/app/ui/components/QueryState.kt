package timeshealth.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import timeshealth.app.ui.state.UiError
import timeshealth.app.ui.state.UiState
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.Carbon950
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.PREVIEW_CANVAS_ARGB
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.RnType
import timeshealth.app.ui.theme.Spacing
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextOnDarkMuted
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThShapes
import timeshealth.app.ui.theme.TimesHealthTheme

/*
 * Loading and error states shared by every data-driven screen: the port of
 * apps/mobile/src/components/QueryState.tsx.
 *
 * Screens used to treat "no data yet" as "still loading", so a failed request
 * spun forever with no way out. Every screen distinguishes the two, and an
 * error always offers a retry (and, on a full-screen route with no header, a
 * way back).
 */

/**
 * The app's spinner: RN's small ActivityIndicator (20dp) in coral, or white
 * on dark / inside a filled button.
 */
@Composable
fun ThSpinner(
    modifier: Modifier = Modifier,
    color: Color = CoralBrand,
    size: Dp = 20.dp,
) {
    CircularProgressIndicator(modifier = modifier.size(size), color = color, strokeWidth = 2.dp)
}

/** Full-area loading: a centred spinner on the canvas (or Carbon950 when [dark]). */
@Composable
fun LoadingState(modifier: Modifier = Modifier, dark: Boolean = false) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (dark) Carbon950 else CanvasBg),
        contentAlignment = Alignment.Center,
    ) {
        ThSpinner(color = if (dark) PaperWhite else CoralBrand)
    }
}

/**
 * Full-area error: the cloud-off glyph, the message, "Try again" and, for a
 * full-screen route without a header, "Go back" (never a screen with no way
 * out). QueryState.tsx `ErrorState`: 28dp padding, 12dp gaps, a coral 12dp
 * button padded 28×12.
 *
 * @param message the words to show, usually [UiError.message].
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    dark: Boolean = false,
) {
    val muted = if (dark) TextOnDarkMuted else TextMuted
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(if (dark) Carbon950 else CanvasBg)
            .padding(Spacing.X7l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Xl, Alignment.CenterVertically),
    ) {
        Icon(Icons.Outlined.CloudOff, contentDescription = null, modifier = Modifier.size(36.dp), tint = muted)
        Text(
            text = message,
            style = RnType.bodyLarge,
            color = if (dark) TextOnDarkMuted else TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (onRetry != null) {
            Button(
                onClick = onRetry,
                shape = ThShapes.Md,
                colors = ButtonDefaults.buttonColors(containerColor = CoralBrand, contentColor = PaperWhite),
                contentPadding = PaddingValues(horizontal = Spacing.X7l, vertical = Spacing.Xl),
            ) {
                Text("Try again", style = RnType.titleSmall, color = PaperWhite)
            }
        }
        if (onBack != null) {
            Spacer(Modifier.height(Spacing.Xxs))
            TextButton(onClick = onBack) {
                Text("Go back", style = RnType.titleSmall, color = if (dark) TextOnDarkMuted else TextSecondary)
            }
        }
    }
}

/** [ErrorState] for a [UiError]. */
@Composable
fun ErrorState(
    error: UiError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    dark: Boolean = false,
) = ErrorState(error.message, modifier, onRetry, onBack, dark)

/**
 * Renders a [UiState]: [LoadingState] while loading, [ErrorState] (with
 * [onRetry], and [onBack] when given) on failure, else [content] with the data.
 */
@Composable
fun <T> UiStateContent(
    state: UiState<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    dark: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        UiState.Loading -> LoadingState(modifier, dark)
        is UiState.Failed -> ErrorState(state.error, modifier, onRetry, onBack, dark)
        is UiState.Ready -> content(state.data)
    }
}

@Preview(showBackground = true, backgroundColor = PREVIEW_CANVAS_ARGB, heightDp = 360)
@Composable
private fun ErrorStatePreview() {
    TimesHealthTheme {
        ErrorState(message = "No connection. Check your network and try again.", onRetry = {}, onBack = {})
    }
}

@Preview(showBackground = true, heightDp = 200)
@Composable
private fun LoadingStatePreview() {
    TimesHealthTheme { LoadingState() }
}
