package timeshealth.app.ui.yoga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import timeshealth.app.core.model.YogaCategory
import timeshealth.app.core.model.YogaSession
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.components.UiStateContent
import timeshealth.app.ui.feed.FeedImage
import timeshealth.app.ui.feed.GradientDir
import timeshealth.app.ui.feed.InstructorAvatar
import timeshealth.app.ui.feed.gradient
import timeshealth.app.ui.theme.BorderRule
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.Carbon950
import timeshealth.app.ui.theme.CategoryGradients
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.GoldAccent
import timeshealth.app.ui.theme.LiveEmerald
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlumDeep
import timeshealth.app.ui.theme.PlumTint
import timeshealth.app.ui.theme.SurfaceSand
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThLayout
import timeshealth.app.ui.theme.ThShapes

/** YogaSessionCard's placeholder gradient per track (horizontal), before or without a photo. */
private fun sessionFallback(categoryId: String): List<Color> = when (categoryId) {
    "cat_core" -> listOf(Color(0xFF5E3906), Color(0xFF8C550A))
    "cat_flex" -> listOf(Color(0xFF6B2215), Color(0xFF9E3622))
    "cat_morning" -> listOf(Color(0xFF0A472E), Color(0xFF19734C))
    "cat_sleep" -> listOf(Color(0xFF0F3250), Color(0xFF1A5282))
    "cat_spine" -> listOf(Color(0xFF381547), Color(0xFF5A2272))
    else -> listOf(Color(0xFF263D34), Color(0xFF436959))
}

/**
 * The library (design YogaCategoryExplorerView): the body-target catalogue.
 * Tracks as cards and chips; the chosen track's banner; then its sessions, one
 * full-width card per row, in a lazy list (the catalogue grows).
 */
@Composable
fun YogaExplorerRoute(viewModel: YogaExplorerViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onPlay: (String) -> Unit, onPaywall: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val active by viewModel.activeCategory.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    Box(Modifier.fillMaxSize().background(CanvasBg).statusBarsPadding()) {
        UiStateContent(state, viewModel::retry, Modifier.fillMaxSize(), onBack = onBack) { lib ->
            val categories = lib.catalog.categories
            val category = categories.firstOrNull { it.id == active }
            val sessions = lib.catalog.sessions.filter { it.categoryId == active }
            val trained = categories.sumOf { it.totalYogisJoined }
            val cardsState = rememberLazyListState()
            val chipsState = rememberLazyListState()
            // Keep the chosen track in view in both rails.
            LaunchedEffect(active) {
                val i = categories.indexOfFirst { it.id == active }
                if (i > 0) {
                    cardsState.animateScrollToItem(i, scrollOffset = -120)
                    chipsState.animateScrollToItem(i, scrollOffset = -120)
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                item(key = "top") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to previous screen", tint = TextPrimary) }
                        Column(Modifier.padding(start = 4.dp).weight(1f)) {
                            Text("Yoga Studio Catalog", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("Targeted practices for anatomical health & lifestyle", color = TextSecondary, fontSize = 11.5.sp)
                        }
                    }
                }
                item(key = "strip") {
                    Row(
                        Modifier.padding(horizontal = ThLayout.Gutter).fillMaxWidth().clip(ThShapes.Md).background(Carbon950).padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(LiveEmerald))
                            Text("${lib.dailyBatches.takeIf { it > 0 } ?: 8} Live Daily Batches", color = PaperWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        if (trained > 0) {
                            Text("${if (trained >= 1000) "${trained / 1000}k+" else "$trained+"} Yogis Trained", color = GoldAccent, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                item(key = "rail-head") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = ThLayout.Gutter).padding(top = 18.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Categories & Anatomy Focus", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("${categories.size} Tracks", color = TextMuted, fontSize = 11.5.sp)
                    }
                }
                item(key = "cards") {
                    LazyRow(state = cardsState, contentPadding = PaddingValues(horizontal = ThLayout.Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(categories, key = { it.id }) { c -> CategoryCard(c, c.id == active) { viewModel.select(c.id) } }
                    }
                }
                item(key = "chips") {
                    LazyRow(state = chipsState, modifier = Modifier.padding(top = 12.dp), contentPadding = PaddingValues(horizontal = ThLayout.Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(categories, key = { it.id }) { c ->
                            val on = c.id == active
                            Text(
                                c.name, color = if (on) PaperWhite else TextPrimary, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.clip(ThShapes.Pill).background(if (on) PlumDeep else PaperWhite)
                                    .border(1.dp, if (on) PlumDeep else BorderRule, ThShapes.Pill)
                                    .semantics { selected = on }
                                    .clickable(role = Role.Tab) { viewModel.select(c.id) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                category?.let { item(key = "banner-${it.id}") { CategoryBanner(it) } }
                // The pass pitch is for non-members only; a subscriber already has it.
                if (!lib.entitled) item(key = "pass") { MembershipBanner(onPaywall, Modifier.padding(top = 16.dp)) }
                category?.let { c ->
                    item(key = "sessions-head") {
                        Column(Modifier.padding(horizontal = ThLayout.Gutter).padding(top = 20.dp, bottom = 4.dp)) {
                            Text("Sessions in ${c.name}", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("${sessions.size} specialized guided session${if (sessions.size == 1) "" else "s"}", color = TextMuted, fontSize = 12.sp)
                        }
                    }
                }
                itemsIndexed(sessions, key = { _, s -> s.id }) { _, s ->
                    SessionCard(
                        s,
                        completed = s.id in lib.completed,
                        saved = s.id in lib.saved,
                        onOpen = { onOpen(s.id) },
                        onToggleSave = { viewModel.setSaved(s.id, s.id !in lib.saved) },
                        // A locked session opens its detail page: preview and subscription.
                        onStart = { if (lib.locked(s)) onOpen(s.id) else onPlay(s.id) },
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** YogaCategoryVisualCard: 152×115; the chosen one gets the gold frame, check and chip. */
@Composable
private fun CategoryCard(c: YogaCategory, selected: Boolean, onClick: () -> Unit) {
    val target = c.bodyTargetSummary.split(',').firstOrNull()?.trim().orEmpty()
    Box(
        Modifier.width(152.dp).height(115.dp).shadow(if (selected) 4.dp else 1.dp, ThShapes.Lg).clip(ThShapes.Lg)
            .border(if (selected) 2.5.dp else 1.dp, if (selected) GoldAccent else BorderRule, ThShapes.Lg)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = "${c.name}, ${c.sessionCount} sessions"
            }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        FeedImage(c.imageUrl, gradient(PlumDeep, Carbon950, GradientDir.VERTICAL), gradient(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.88f), GradientDir.VERTICAL), Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${c.sessionCount} ${if (c.sessionCount == 1) "SESSION" else "SESSIONS"}",
                    color = if (selected) Carbon950 else PaperWhite, fontSize = 8.5.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (selected) GoldAccent else Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (selected) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(GoldAccent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, tint = Carbon950, modifier = Modifier.size(12.dp))
                    }
                }
            }
            Column {
                Text(c.name, color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${c.totalYogisJoined / 1000}k yogis · $target", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** CategoryHeroBanner: 165dp, 18dp inset and radius; photo over the track gradient. */
@Composable
private fun CategoryBanner(c: YogaCategory) {
    val colors = CategoryGradients.forTheme(c.bannerTheme.name)
    Box(Modifier.padding(horizontal = ThLayout.Gutter).padding(top = 16.dp).fillMaxWidth().height(165.dp).clip(RoundedCornerShape(18.dp))) {
        FeedImage(c.imageUrl, gradient(colors[0], colors[1], GradientDir.HORIZONTAL), gradient(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.88f), GradientDir.VERTICAL), Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TagPill("Targeted specialty", TagTone.GOLD)
                Text("${groupThousands(c.totalYogisJoined)}+ Yogis", color = PaperWhite, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            }
            Column {
                Text(c.name, color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.tagline, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(
                    Modifier.padding(top = 8.dp).clip(ThShapes.Sm).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.Filled.Favorite, null, tint = CoralBrand, modifier = Modifier.size(13.dp))
                    Text("Body Target: ${c.bodyTargetSummary}", color = PaperWhite, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** YogaSessionCard: photo with status pills, then the practice summary and two actions. */
@Composable
private fun SessionCard(s: YogaSession, completed: Boolean, saved: Boolean, onOpen: () -> Unit, onToggleSave: () -> Unit, onStart: () -> Unit) {
    Column(
        Modifier.padding(horizontal = ThLayout.Gutter, vertical = 8.dp).fillMaxWidth().shadow(2.dp, ThShapes.Lg).clip(ThShapes.Lg).background(PaperWhite)
            .clickable(onClick = onOpen),
    ) {
        Box(Modifier.fillMaxWidth().height(150.dp)) {
            val fallback = sessionFallback(s.categoryId)
            FeedImage(s.imageUrl, gradient(fallback[0], fallback[1], GradientDir.HORIZONTAL), gradient(Color.Black.copy(alpha = 0.28f), Color.Black.copy(alpha = 0.85f), GradientDir.VERTICAL), Modifier.matchParentSize())
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (s.isLive) TagPill("Live now", TagTone.LIVE)
                        TagPill("Est: ${s.durationMinutes} mins", TagTone.NEUTRAL)
                        if (s.isFree) TagPill("Free pass", TagTone.EMERALD)
                        if (completed) TagPill("Completed ✓", TagTone.SAGE)
                    }
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(role = Role.Button, onClick = onToggleSave),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            if (saved) "Remove from saved" else "Save session",
                            tint = if (saved) GoldAccent else PaperWhite, modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.Groups, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(13.dp))
                        Text("${groupThousands(s.joinedCountTillDate)} Yogis Joined Till Date", color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp)
                    }
                    Text(
                        s.intensity, color = PaperWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.2f)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Column(Modifier.padding(14.dp)) {
            Text(s.title, color = TextPrimary, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InstructorAvatar(s.instructor.name, s.instructor.avatarUrl, 26)
                Text(s.instructor.name, color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("· ${"%.1f".format(s.instructor.rating)} ★", color = GoldAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Row(
                Modifier.padding(top = 10.dp).fillMaxWidth().clip(ThShapes.Sm).background(PlumTint).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.FitnessCenter, null, tint = PlumDeep, modifier = Modifier.size(14.dp))
                Text("Target: ${s.bodyFocusTitle}", color = PlumDeep, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, BorderRule, RoundedCornerShape(10.dp)).background(SurfaceSand.copy(alpha = 0.4f))
                        .clickable(role = Role.Button, onClick = onOpen),
                    contentAlignment = Alignment.Center,
                ) { Text("Details & Benefits", color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
                Row(
                    Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(10.dp)).background(if (s.isLive) LiveEmerald else CoralBrand)
                        .clickable(role = Role.Button, onClick = onStart),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = PaperWhite, modifier = Modifier.size(16.dp))
                    Text(if (s.isLive) "Join Live" else "Start Session", color = PaperWhite, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
