package timeshealth.app.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import timeshealth.app.ui.navigation.AppTab
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.GoldAccent
import timeshealth.app.ui.theme.PREVIEW_CANVAS_ARGB
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.SageBrand
import timeshealth.app.ui.theme.Spacing
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.ThLayout
import timeshealth.app.ui.theme.TimesHealthTheme

/** One bar item: BottomNavBarKt's NavTabItem. */
@Immutable
data class NavTabItem(
    val tab: AppTab,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    /** The tab's OWN brand colour when selected (see [BottomNavBar]). */
    val activeColor: Color,
)

/**
 * The four tabs, in the design's order, icons and colours. DirectionsRun is the
 * design's own (non-auto-mirrored) glyph, hence the deprecation suppression.
 */
@Suppress("DEPRECATION")
val NavTabs: List<NavTabItem> = listOf(
    NavTabItem(AppTab.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home, TextPrimary),
    NavTabItem(AppTab.YOGA, "Yoga", Icons.Filled.SelfImprovement, Icons.Outlined.SelfImprovement, SageBrand),
    NavTabItem(AppTab.MARATHON, "Marathon", Icons.Filled.DirectionsRun, Icons.Outlined.DirectionsRun, CoralBrand),
    NavTabItem(AppTab.DIET, "Diet", Icons.Filled.Restaurant, Icons.Outlined.Restaurant, GoldAccent),
)

/**
 * The bottom tab bar: BottomNavBarKt, exactly.
 *
 * A flat paper-white bar (no rule, no shadow) that sits ABOVE the system
 * gesture bar / 3-button nav (`navigationBarsPadding()` then 64dp; nothing
 * else added), 8dp side padding, items spaced around. Each item: the filled
 * icon when selected and the outlined one when not, at 24dp; 3dp; a 10sp label
 * (Bold selected, Medium not); and under the selected label a 4dp dot (2dp
 * gap). Each tab lights up in ITS OWN brand colour (Home ink, Yoga sage,
 * Marathon coral, Diet gold); unselected is TextMuted.
 */
@Composable
fun BottomNavBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), clip = false,
                ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.12f))
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(PaperWhite)
            .navigationBarsPadding()
            .height(ThLayout.BottomNavHeight)
            .padding(horizontal = Spacing.Md)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavTabs.forEach { item ->
            val selected = item.tab == selectedTab
            val color = if (selected) item.activeColor else TextMuted
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(role = Role.Tab) { onTabSelected(item.tab) }
                    .semantics { this.selected = selected },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // The active tab sits in a soft pill of its own colour.
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(if (selected) item.activeColor.copy(alpha = 0.12f) else Color.Transparent)
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.label,
                        modifier = Modifier.size(24.dp),
                        tint = color,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = item.label,
                    color = color,
                    fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = PREVIEW_CANVAS_ARGB)
@Composable
private fun BottomNavBarPreview() {
    TimesHealthTheme {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTab.entries.forEach { BottomNavBar(selectedTab = it, onTabSelected = {}) }
        }
    }
}
