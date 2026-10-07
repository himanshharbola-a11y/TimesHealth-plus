package timeshealth.app.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import timeshealth.app.core.domain.isAppRoute
import timeshealth.app.core.model.NotificationItem
import timeshealth.app.core.model.NotificationKind
import timeshealth.app.ui.components.ThSpinner
import timeshealth.app.ui.theme.BorderRule
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.SurfaceSand
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.Tint
import timeshealth.app.ui.theme.Tints

/** Icon and tint per kind; a kind this build doesn't know is a plain bell. */
private fun lookOf(kind: NotificationKind): Pair<ImageVector, Tint> = when (kind) {
    NotificationKind.SESSION_REMINDER -> Icons.Filled.Schedule to Tints.Plum
    NotificationKind.SESSION_LIVE -> Icons.Filled.SelfImprovement to Tints.Emerald
    NotificationKind.RACE_COUNTDOWN, NotificationKind.RACE_DAY_INFO -> Icons.Filled.DirectionsRun to Tints.Coral
    NotificationKind.RESULT_PUBLISHED -> Icons.Filled.EmojiEvents to Tints.Gold
    NotificationKind.UNKNOWN -> Icons.Outlined.NotificationsNone to Tints.Neutral
}

/**
 * The TopHeader bell's inbox: every push this user was sent, newest first, so
 * a tap on the bell is never a dead end (§11: push is an enhancement; this is
 * where a dismissed or missed one can still be found). A tap opens the item's
 * in-app route; anything that isn't one stays put.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxSheet(viewModel: InboxViewModel, onOpenRoute: (String) -> Unit, onDismiss: () -> Unit) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.opened() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PaperWhite,
    ) {
        // At most 80% of the screen; a short list wraps.
        val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.8f
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Notifications", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = TextMuted) }
            }
            when {
                ui.items.isNotEmpty() -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 20.dp)) {
                    items(ui.items, key = { it.id }) { item ->
                        InboxRow(item, ui.isNew(item), relativeTime(item.sentAt, viewModel.nowMs())) {
                            item.route?.takeIf(::isAppRoute)?.let(onOpenRoute)
                        }
                    }
                }
                ui.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { ThSpinner(size = 24.dp) }
                ui.failed -> StateMessage(null, "Notifications couldn’t load right now.", onRetry = viewModel::retry)
                else -> StateMessage("You’re all caught up", "Class reminders and race updates will appear here.")
            }
        }
    }
}

@Composable
private fun InboxRow(item: NotificationItem, new: Boolean, time: String, onClick: () -> Unit) {
    val (icon, tint) = lookOf(item.kind)
    val navigable = isAppRoute(item.route)
    Column {
        Row(
            Modifier.fillMaxWidth()
                .then(if (navigable) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .semantics(mergeDescendants = true) { contentDescription = "${if (new) "New. " else ""}${item.title}. ${item.body}" }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(tint.bg), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint.fg, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, color = TextPrimary, fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.body.isNotBlank()) {
                    Text(item.body, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Text(time, color = TextMuted, fontSize = 10.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
            if (new) Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(CoralBrand))
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(BorderRule))
    }
}

@Composable
private fun StateMessage(title: String?, body: String, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (title != null) {
            Box(Modifier.padding(bottom = 4.dp).size(48.dp).clip(CircleShape).background(SurfaceSand), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.NotificationsNone, null, tint = TextMuted, modifier = Modifier.size(24.dp))
            }
            Text(title, color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(body, color = TextMuted, fontSize = 12.5.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
        onRetry?.let {
            Text(
                "Try again", color = CoralBrand, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp).clickable(role = Role.Button, onClick = it).padding(6.dp),
            )
        }
    }
}
