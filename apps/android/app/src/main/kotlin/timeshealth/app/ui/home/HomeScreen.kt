package timeshealth.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import timeshealth.app.ui.components.SectionHeader
import timeshealth.app.ui.components.UiStateContent
import timeshealth.app.ui.state.UiState
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.PREVIEW_CANVAS_ARGB
import timeshealth.app.ui.theme.Spacing
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThLayout
import timeshealth.app.ui.theme.TimesHealthTheme

@Composable
fun HomeRoute(viewModel: HomeViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(state = state, onRetry = viewModel::retry, modifier = modifier)
}

/** PLACEHOLDER Home: the design's greeting, then a note that the feed is coming. */
@Composable
fun HomeScreen(state: UiState<HomeGreeting>, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    // Only one branch is ever composed, so [modifier] applies exactly once.
    UiStateContent(state = state, onRetry = onRetry, modifier = modifier) { greeting ->
        Column(
            modifier
                .fillMaxSize()
                .background(CanvasBg)
                .verticalScroll(rememberScrollState()),
        ) {
            Greeting(greeting)
            SectionHeader(title = "Your feed")
            Text(
                "Classes, races and reels for you land here soon.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = ThLayout.Gutter),
            )
        }
    }
}

/**
 * HomeScreenKt's greeting item: 18×8 padding, the muted 12sp Medium day line
 * over the serif 24sp SemiBold "Hello, {firstName} 👋".
 */
@Composable
fun Greeting(greeting: HomeGreeting, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = ThLayout.Gutter, vertical = Spacing.Md)) {
        Text(
            greeting.dayLine,
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Hello, ${greeting.firstName} 👋",
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = ThFonts.Serif,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(showBackground = true, backgroundColor = PREVIEW_CANVAS_ARGB, heightDp = 400)
@Composable
private fun HomePreview() {
    TimesHealthTheme {
        HomeScreen(state = UiState.Ready(HomeGreeting("Good morning · Thursday", "Priya")), onRetry = {})
    }
}
