package timeshealth.app.ui.live

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import timeshealth.app.core.domain.formatDayAndTime
import timeshealth.app.core.domain.secondsUntil
import timeshealth.app.core.model.LiveClassCard
import timeshealth.app.ui.components.ErrorState
import timeshealth.app.ui.components.LightSystemBarIcons
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.components.ThSpinner
import timeshealth.app.ui.feed.FeedImage
import timeshealth.app.ui.feed.GradientDir
import timeshealth.app.ui.feed.InstructorAvatar
import timeshealth.app.ui.feed.LocalServerNow
import timeshealth.app.ui.feed.gradient
import timeshealth.app.ui.feed.rememberServerNow
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlayerCanvas
import timeshealth.app.ui.theme.PlumBrand
import timeshealth.app.ui.theme.PlumDeep
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextOnDarkMuted
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThShapes

@Composable
fun LiveClassRoute(viewModel: LiveClassViewModel, onBack: () -> Unit, onOpenPaywall: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalServerNow provides viewModel::nowMs) {
        LiveClassScreen(
            state = state,
            onBack = onBack,
            onRetry = viewModel::retry,
            onToggleReminder = viewModel::toggleReminder,
            onOpenPaywall = onOpenPaywall,
            nowMs = viewModel::nowMs,
        )
    }
}

/**
 * A live class: the player (or what stands in for it: the wait room
 * countdown, "opens at", the paywall), then the class details. Dark, like the
 * design's player screens.
 */
@Composable
fun LiveClassScreen(
    state: LiveClassUi,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleReminder: () -> Unit,
    onOpenPaywall: () -> Unit,
    nowMs: () -> Long,
) {
    // A dark screen edge to edge: light system bar icons while it is up.
    LightSystemBarIcons(statusBar = true, navigationBar = true)
    Column(Modifier.fillMaxSize().background(PlayerCanvas).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = PaperWhite)
            }
            Text(
                state.card?.title ?: "Live class", color = PaperWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
        }

        // The 16:9 stage: the player while live, otherwise what to expect.
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            when (val phase = state.phase) {
                is LivePhase.Playing -> PremierePlayer(phase.stream, phase.startsAtMs, nowMs, Modifier.fillMaxSize())
                else -> Stage(state, phase, onRetry, onOpenPaywall)
            }
        }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp)) {
            state.card?.let { Details(it) }
            ReminderAction(state, onToggleReminder)
        }
    }
}

@Composable
private fun Stage(state: LiveClassUi, phase: LivePhase, onRetry: () -> Unit, onOpenPaywall: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        FeedImage(
            state.card?.imageUrl, gradient(PlumDeep, PlumBrand),
            gradient(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.85f), GradientDir.VERTICAL),
            Modifier.fillMaxSize(),
        )
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (phase) {
                LivePhase.Loading -> ThSpinner(color = PaperWhite, size = 28.dp)
                is LivePhase.NotOpen -> {
                    val now = rememberServerNow()
                    TagPill("Starts ${formatDayAndTime(Instant.ofEpochMilli(phase.startsAtMs), now)}", TagTone.CORAL)
                    StageText("The wait room opens an hour before the class.")
                    StageCountdown(secondsUntil(phase.opensAtMs, now), "until the wait room opens")
                }
                is LivePhase.WaitRoom -> {
                    val now = rememberServerNow()
                    TagPill("Wait room", TagTone.CORAL)
                    StageText("You're in. The class starts for everyone at the same moment.")
                    StageCountdown(secondsUntil(phase.startsAtMs, now), "to go")
                }
                LivePhase.Locked -> {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = PaperWhite, modifier = Modifier.size(28.dp))
                    StageText("This live class is for yoga members.")
                    StageButton("Explore membership", onOpenPaywall)
                }
                LivePhase.Ended -> StageText("This class has ended. See you at the next one!")
                LivePhase.Cancelled -> StageText("This class was cancelled. Sorry about that.")
                is LivePhase.Failed -> ErrorState(phase.error, Modifier.fillMaxSize(), onRetry = onRetry, dark = true)
                is LivePhase.Playing -> Unit
            }
        }
    }
}

@Composable
private fun StageText(text: String) {
    Text(
        text, color = PaperWhite, fontSize = 14.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 10.dp),
    )
}

/** "1:04:09" / "14:32": a ticking clock for the wait room. */
internal fun clockCountdown(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}

@Composable
private fun StageCountdown(seconds: Long, caption: String) {
    Text(
        clockCountdown(seconds), color = PaperWhite, fontFamily = FontFamily.Monospace, fontSize = 30.sp,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp),
    )
    Text(caption, color = TextOnDarkMuted, fontSize = 12.sp)
}

@Composable
private fun StageButton(label: String, onClick: () -> Unit) {
    Text(
        label, color = PaperWhite, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(top = 14.dp)
            .clip(ThShapes.Md)
            .background(CoralBrand)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun Details(card: LiveClassCard) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (card.isFree) TagPill("Free class", TagTone.CORAL) else TagPill("Members", TagTone.PLUM)
        TagPill("${card.durationMinutes} min", TagTone.NEUTRAL)
    }
    Text(
        card.title, color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 22.sp, lineHeight = 27.sp,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp),
    )
    card.instructorName?.let { name ->
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InstructorAvatar(name, card.instructorAvatarUrl, 26)
            Text(name, color = PaperWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    if (card.description.isNotBlank()) {
        Text(card.description, color = TextOnDarkMuted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
    }
    Spacer(Modifier.height(16.dp))
}

/** Offered until the class starts. Android 13+ asks for the notification permission first. */
@Composable
private fun ReminderAction(state: LiveClassUi, onToggle: () -> Unit) {
    val phase = state.phase
    if (state.card == null || !(phase is LivePhase.NotOpen || phase is LivePhase.WaitRoom)) return
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onToggle()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ThShapes.Md)
            .background(if (state.reminderSet) Color.White.copy(alpha = 0.12f) else CoralBrand)
            .clickable(role = Role.Button) {
                if (!state.reminderSet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onToggle()
                }
            }
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (state.reminderSet) Icons.Filled.NotificationsActive else Icons.Filled.Notifications,
            contentDescription = null, tint = PaperWhite, modifier = Modifier.size(18.dp),
        )
        Text(
            if (state.reminderSet) "Reminder set · tap to turn off" else "Remind me 10 min before",
            color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        )
    }
}
