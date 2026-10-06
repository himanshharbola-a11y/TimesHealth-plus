package timeshealth.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.PREVIEW_CANVAS_ARGB
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.RnType
import timeshealth.app.ui.theme.Spacing
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.ThShapes
import timeshealth.app.ui.theme.TimesHealthTheme

/**
 * Stand-in for a screen that isn't built yet, so the navigation graph is
 * complete and every route can be opened (deep links, QA). Shows the screen's
 * name; [onBack] adds a back arrow (full-screen routes must never be a dead
 * end), [actionLabel] + [onAction] an optional way forward.
 *
 * Screen agents: replace the destination's call to this in AppNavHost with the
 * real screen; do not restyle this.
 */
@Composable
fun ComingSoonScreen(
    screenName: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onBack: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().background(CanvasBg)) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(Spacing.Xs)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
        }
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = Spacing.X8l),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            TagPill(text = "Coming soon", tone = TagTone.NEUTRAL)
            Text(screenName, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    shape = ThShapes.Md,
                    colors = ButtonDefaults.buttonColors(containerColor = CoralBrand, contentColor = PaperWhite),
                    contentPadding = PaddingValues(horizontal = Spacing.X7l, vertical = Spacing.Xl),
                    modifier = Modifier.padding(top = Spacing.Xl),
                ) {
                    Text(actionLabel, style = RnType.titleSmall, color = PaperWhite)
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = PREVIEW_CANVAS_ARGB, heightDp = 400)
@Composable
private fun ComingSoonPreview() {
    TimesHealthTheme {
        ComingSoonScreen(screenName = "Race results", detail = "Event evt_123", onBack = {})
    }
}
