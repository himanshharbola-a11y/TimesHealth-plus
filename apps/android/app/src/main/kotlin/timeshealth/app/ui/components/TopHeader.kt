package timeshealth.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import timeshealth.app.ui.theme.Carbon800
import timeshealth.app.ui.theme.Carbon900
import timeshealth.app.ui.theme.Carbon950
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.PREVIEW_CANVAS_ARGB
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlumBrand
import timeshealth.app.ui.theme.Spacing
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextOnDark
import timeshealth.app.ui.theme.TextOnDarkMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThLayout
import timeshealth.app.ui.theme.ThShapes
import timeshealth.app.ui.theme.TimesHealthTheme

/**
 * The brand bar above every tab: TopHeaderKt, which the design's MainActivityKt
 * puts in the Scaffold top bar for all four tabs.
 *
 * ```
 *   [♥] TimesHealth+              (🔔•) (P)
 *       ONE HEALTH ECOSYSTEM
 * ```
 *
 * Draws its own status-bar inset (`statusBarsPadding`, then 18×10 padding).
 * The bell opens the notification inbox and wears its 6dp coral dot only while
 * the inbox holds something unseen (the design always drew it; RN made it
 * truthful). The initial avatar opens Profile (PRD §10), which is why Profile
 * is not a tab: it is one tap away from every tab here.
 *
 * Stateless: the caller supplies the name and the unread flag and handles taps.
 *
 * @param userName the profile name; its first character, upper-cased, is the
 *   avatar's initial, "P" (profile) when there is none.
 * @param dark the design's dark variant (Carbon950 bar), for dark hero screens.
 */
@Composable
fun TopHeader(
    userName: String?,
    hasUnread: Boolean,
    onProfileClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
) {
    val background = if (dark) Carbon950 else CanvasBg
    val ink = if (dark) TextOnDark else TextPrimary
    val iconInk = if (dark) TextOnDarkMuted else TextSecondary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .statusBarsPadding()
            .padding(horizontal = ThLayout.Gutter, vertical = Spacing.Lg),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) {
                heading()
                contentDescription = "TimesHealth+"
            },
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandMark()
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Times",
                        color = ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp,
                    )
                    Text(
                        text = "Health+",
                        color = CoralBrand,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.3).sp,
                    )
                }
                Text(
                    text = "ONE HEALTH ECOSYSTEM",
                    color = TextMuted,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (dark) Carbon800 else PaperWhite)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Open notifications",
                        onClick = onNotificationsClick,
                    )
                    .semantics { contentDescription = if (hasUnread) "Notifications, new" else "Notifications" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = iconInk,
                )
                if (hasUnread) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-8).dp, y = 8.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(CoralBrand),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (dark) PlumBrand else Carbon900)
                    .clickable(role = Role.Button, onClickLabel = "Open profile", onClick = onProfileClick)
                    .semantics { contentDescription = "Open profile" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = avatarInitial(userName),
                    color = PaperWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** The 32dp coral tile with a white heart, 8dp corners: the brand mark. */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(32.dp).clip(ThShapes.Sm).background(CoralBrand),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(18.dp), tint = PaperWhite)
    }
}

/**
 * The avatar letter: the first character of the (trimmed) name, upper-cased;
 * "P" (profile) when there is no name. The design and RN rule.
 */
fun avatarInitial(name: String?): String =
    name?.trim()?.firstOrNull()?.toString()?.uppercase(Locale.ROOT) ?: "P"

@Preview(showBackground = true, backgroundColor = PREVIEW_CANVAS_ARGB)
@Composable
private fun TopHeaderPreview() {
    TimesHealthTheme {
        Column {
            TopHeader(userName = "Priya Sharma", hasUnread = true, onProfileClick = {}, onNotificationsClick = {})
            TopHeader(userName = null, hasUnread = false, onProfileClick = {}, onNotificationsClick = {})
            TopHeader(userName = "Arjun", hasUnread = true, onProfileClick = {}, onNotificationsClick = {}, dark = true)
        }
    }
}
