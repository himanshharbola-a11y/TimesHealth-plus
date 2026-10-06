package timeshealth.app.ui.yoga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import timeshealth.app.core.model.YogaSession
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.components.UiStateContent
import timeshealth.app.ui.feed.FeedImage
import timeshealth.app.ui.feed.GradientDir
import timeshealth.app.ui.feed.InstructorAvatar
import timeshealth.app.ui.feed.gradient
import timeshealth.app.ui.theme.BorderRule
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.Carbon900
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.GoldAccent
import timeshealth.app.ui.theme.LiveEmerald
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlumDeep
import timeshealth.app.ui.theme.PlumTint
import timeshealth.app.ui.theme.SageBrand
import timeshealth.app.ui.theme.SurfaceSand
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThLayout
import timeshealth.app.ui.theme.ThShapes

/** The hero's placeholder gradient per track (vertical), before or without the photo. */
private fun heroFallback(categoryId: String): List<Color> = when (categoryId) {
    "cat_core" -> listOf(Color(0xFF5E3906), Color(0xFF2E1B02))
    "cat_flex" -> listOf(Color(0xFF6B2215), Color(0xFF330E07))
    "cat_morning" -> listOf(Color(0xFF0A472E), Color(0xFF042417))
    "cat_sleep" -> listOf(Color(0xFF0F3250), Color(0xFF071929))
    "cat_spine" -> listOf(Color(0xFF381547), Color(0xFF1B0B24))
    else -> listOf(Color(0xFF263D34), Color(0xFF111E19))
}

/**
 * Session detail (design YogaSessionDetailView): the therapeutic depth of each
 * practice (body targets, lifestyle impact, the asana sequence). A locked
 * session still shows everything except playback (§6.3): its one action is to
 * subscribe, never a dead tap.
 */
@Composable
fun SessionDetailRoute(viewModel: SessionDetailViewModel, onBack: () -> Unit, onPlay: (String) -> Unit, onPaywall: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    Box(Modifier.fillMaxSize().background(CanvasBg).statusBarsPadding()) {
        UiStateContent(state, viewModel::retry, Modifier.fillMaxSize(), onBack = onBack) { ui ->
            SessionDetail(
                ui,
                onBack = onBack,
                onSave = { viewModel.setSaved(!ui.saved) },
                onComplete = { viewModel.setCompleted(!ui.completed) },
                onMain = { if (ui.locked) onPaywall() else onPlay(ui.session.id) },
                onPaywall = onPaywall,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionDetail(ui: SessionUi, onBack: () -> Unit, onSave: () -> Unit, onComplete: () -> Unit, onMain: () -> Unit, onPaywall: () -> Unit) {
    val s = ui.session
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to sessions list", tint = TextPrimary) }
            IconButton(onClick = onSave) {
                Icon(
                    if (ui.saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    if (ui.saved) "Remove bookmark" else "Bookmark session",
                    tint = if (ui.saved) GoldAccent else TextPrimary,
                )
            }
        }

        Hero(s)

        Column(Modifier.padding(top = 16.dp).padding(horizontal = ThLayout.Gutter)) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(ThShapes.Md)
                    .background(if (s.isLive && !ui.locked) LiveEmerald else CoralBrand)
                    .clickable(role = Role.Button, onClick = onMain),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A locked session is a preview (§6.3): its action is to subscribe, in the brand colour, never greyed.
                if (!ui.locked) Icon(Icons.Filled.PlayArrow, null, tint = PaperWhite, modifier = Modifier.size(20.dp))
                Text(
                    when {
                        ui.locked -> "Subscribe to Watch"
                        s.isLive -> "Join Live Class Now"
                        else -> "Start Practice Session"
                    },
                    color = PaperWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                )
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    if (ui.saved) "Saved" else "Save Session",
                    if (ui.saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    fg = if (ui.saved) GoldAccent else TextPrimary,
                    bg = Color.Transparent,
                    border = if (ui.saved) GoldAccent else BorderRule,
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    if (ui.completed) "Completed ✓" else "Mark Complete",
                    if (ui.completed) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                    fg = if (ui.completed) PaperWhite else TextPrimary,
                    bg = if (ui.completed) SageBrand else SurfaceSand,
                    border = null,
                    onClick = onComplete,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Card(Modifier.padding(top = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(PlumTint), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.AccessibilityNew, null, tint = PlumDeep, modifier = Modifier.size(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Body Targets & Lifestyle Impact", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Where this practice works in your body & daily life", color = TextMuted, fontSize = 11.5.sp)
                }
            }
            Label("ANATOMICAL FOCUS AREAS", Modifier.padding(top = 12.dp))
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.targetBodyParts.forEach { p ->
                    Text(
                        p, color = PlumDeep, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(ThShapes.Sm).background(PlumTint).padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            Label("HOW THIS IMPROVES YOUR LIFESTYLE", Modifier.padding(top = 14.dp))
            Row(
                Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SurfaceSand).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Check, null, tint = SageBrand, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                Text(s.lifestyleImpact, color = TextPrimary, fontSize = 12.5.sp, lineHeight = 18.sp)
            }
        }

        Card {
            Label("YOUR INSTRUCTOR")
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InstructorAvatar(s.instructor.name, s.instructor.avatarUrl, 52)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(s.instructor.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Filled.Verified, "Verified Master Instructor", tint = GoldAccent, modifier = Modifier.size(15.dp))
                    }
                    Text(s.instructor.title, color = TextSecondary, fontSize = 11.5.sp)
                    Text(s.instructor.experience, color = TextMuted, fontSize = 11.sp)
                }
            }
            s.instructor.bio.takeIf { it.isNotBlank() }?.let {
                Text(it, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }

        if (s.keyPoses.isNotEmpty()) {
            Card {
                Label("PRACTICE SEQUENCE & ASANAS")
                Column(Modifier.padding(top = 10.dp)) {
                    s.keyPoses.forEachIndexed { i, p ->
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(20.dp).clip(CircleShape).background(PlumTint), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", color = PlumDeep, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(p, color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        // The pass pitch is for non-members; a subscriber already has it.
        if (!ui.entitled) MembershipBanner(onPaywall, Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun Hero(s: YogaSession) {
    Box(Modifier.padding(horizontal = ThLayout.Gutter).fillMaxWidth().clip(ThShapes.Xl).background(Carbon900)) {
        FeedImage(
            s.imageUrl,
            heroFallback(s.categoryId).let { gradient(it[0], it[1], GradientDir.VERTICAL) },
            gradient(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.88f), GradientDir.VERTICAL),
            Modifier.matchParentSize(),
        )
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (s.isLive) TagPill("Live now", TagTone.LIVE) else TagPill("On-demand class", TagTone.NEUTRAL)
                TagPill("${s.durationMinutes} mins · ${s.caloriesBurned} kcal", TagTone.GOLD)
            }
            Text(s.title, color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            Text(s.description, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
            Row(
                Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Groups, null, tint = CoralBrand, modifier = Modifier.size(18.dp))
                    Text("${groupThousands(s.joinedCountTillDate)} Yogis Joined Till Date", color = PaperWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${s.todayActiveCount} today", color = LiveEmerald, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun SecondaryButton(label: String, icon: ImageVector, fg: Color, bg: Color, border: Color?, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier.height(46.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .then(if (border != null) Modifier.border(1.dp, border, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        Text(label, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Card(padding h18, R16, PaperWhite, elevation 1), inner padding 16. */
@Composable
private fun Card(modifier: Modifier = Modifier.padding(top = 16.dp), content: @Composable () -> Unit) {
    Column(
        modifier.padding(horizontal = ThLayout.Gutter).fillMaxWidth().shadow(1.dp, ThShapes.Lg).clip(ThShapes.Lg).background(PaperWhite).padding(16.dp),
    ) { content() }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, modifier = modifier)
}
