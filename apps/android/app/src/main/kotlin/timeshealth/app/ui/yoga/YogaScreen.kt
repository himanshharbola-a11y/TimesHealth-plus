package timeshealth.app.ui.yoga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import timeshealth.app.BuildConfig
import timeshealth.app.core.domain.IST
import timeshealth.app.core.domain.formatDayAndTime
import timeshealth.app.core.domain.formatTimeOfDay
import timeshealth.app.core.domain.isJoinOpen
import timeshealth.app.core.domain.isSafeExternalUrl
import timeshealth.app.core.domain.parseIsoInstant
import timeshealth.app.core.model.LiveClassState
import timeshealth.app.core.model.YogaAttendance
import timeshealth.app.core.model.YogaBatch
import timeshealth.app.core.model.YogaCategory
import timeshealth.app.core.model.YogaSession
import timeshealth.app.ui.components.ErrorState
import timeshealth.app.ui.components.SectionHeader
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.components.ThSpinner
import timeshealth.app.ui.components.UiStateContent
import timeshealth.app.ui.feed.FeedImage
import timeshealth.app.ui.feed.GradientDir
import timeshealth.app.ui.feed.InstructorAvatar
import timeshealth.app.ui.feed.LiveClassCardView
import timeshealth.app.ui.feed.LocalServerNow
import timeshealth.app.ui.feed.VideoCard
import timeshealth.app.ui.feed.WorkshopCard
import timeshealth.app.ui.workshop.WorkshopSheet
import timeshealth.app.core.model.LiveWorkshop
import androidx.hilt.navigation.compose.hiltViewModel
import timeshealth.app.ui.feed.gradient
import timeshealth.app.ui.feed.rememberServerNow
import timeshealth.app.ui.home.FeedTarget
import timeshealth.app.ui.home.YOGA_PLAN_ID
import timeshealth.app.ui.home.targetFor
import timeshealth.app.ui.navigation.Route
import timeshealth.app.ui.state.UiState
import timeshealth.app.ui.theme.BorderRule
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.CrimsonAlert
import timeshealth.app.ui.theme.GoldAccent
import timeshealth.app.ui.theme.GoldTint
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlumBrand
import timeshealth.app.ui.theme.PlumDeep
import timeshealth.app.ui.theme.PlumLine
import timeshealth.app.ui.theme.PlumTint
import timeshealth.app.ui.theme.SageBrand
import timeshealth.app.ui.theme.SageTint
import timeshealth.app.ui.theme.SurfaceSand
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThLayout
import timeshealth.app.ui.theme.ThShapes

private const val HERO_IMAGE = "https://images.unsplash.com/photo-1544367567-0f2fcb009e0b?auto=format&fit=crop&w=800&q=80"

@Composable
fun YogaRoute(viewModel: YogaViewModel, openRoute: (Route) -> Unit, onTarget: (FeedTarget) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val attendance by viewModel.attendance.collectAsStateWithLifecycle()
    val busy by viewModel.busyBatch.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }
    var workshop by remember { mutableStateOf<LiveWorkshop?>(null) }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    val join = { batchId: String ->
        viewModel.join(batchId) { target ->
            when (target) {
                is JoinTarget.InApp -> openRoute(Route.LiveClass(target.liveClassId))
                is JoinTarget.External -> if (isSafeExternalUrl(target.url, BuildConfig.DEBUG)) runCatching { uri.openUri(target.url) }
            }
        }
    }
    CompositionLocalProvider(LocalServerNow provides viewModel::nowMs) {
        Box(Modifier.fillMaxSize().background(CanvasBg)) {
            YogaScreen(
                state = state,
                attendance = attendance,
                busyBatch = busy,
                onRetry = viewModel::retry,
                onRefresh = viewModel::refresh,
                onTracker = { viewModel.loadAttendance() },
                onJoin = join,
                onRemind = viewModel::setReminderSlot,
                openRoute = openRoute,
                onTarget = onTarget,
                onWorkshop = { workshop = it },
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            workshop?.let {
                WorkshopSheet(it, hiltViewModel(), onDismiss = {
                    workshop = null
                    viewModel.refresh()
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YogaScreen(
    state: UiState<YogaUi>,
    attendance: UiState<YogaAttendance>?,
    busyBatch: String?,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onTracker: () -> Unit,
    onJoin: (String) -> Unit,
    onRemind: (String) -> Unit,
    openRoute: (Route) -> Unit,
    onTarget: (FeedTarget) -> Unit,
    onWorkshop: (LiveWorkshop) -> Unit = {},
) {
    var trackerTab by rememberSaveable { mutableStateOf(false) }
    // Also after the tab is restored (rotation, process death), not only on a tap.
    LaunchedEffect(trackerTab) { if (trackerTab) onTracker() }
    UiStateContent(state, onRetry, Modifier.fillMaxSize()) { ui ->
        PullToRefreshBox(isRefreshing = (state as? UiState.Ready)?.refreshing == true, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
                if (ui.member) {
                    item(key = "header") {
                        Header(trackerTab) { trackerTab = it }
                    }
                    if (trackerTab) trackerItems(attendance, onTracker) else memberItems(ui, busyBatch, onJoin, onRemind, openRoute, onTarget, onWorkshop)
                } else {
                    freeItems(ui, openRoute, onTarget)
                }
            }
        }
    }
}

@Composable
private fun Header(tracker: Boolean, onTab: (Boolean) -> Unit) {
    Column(Modifier.padding(horizontal = ThLayout.Gutter, vertical = 8.dp)) {
        Text("TimesHealth+ Yoga", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(ThShapes.Md).background(SurfaceSand).padding(4.dp)) {
            listOf(false to "Sessions", true to "Tracker").forEach { (isTracker, label) ->
                val on = tracker == isTracker
                Box(
                    Modifier.weight(1f).clip(ThShapes.Sm).background(if (on) PaperWhite else Color.Transparent).clickable(role = Role.Tab) { onTab(isTracker) }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = if (on) CoralBrand else TextSecondary, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium) }
            }
        }
    }
}

// ── Member: Sessions ──────────────────────────────────────────────────────────

private fun LazyListScope.memberItems(
    ui: YogaUi,
    busyBatch: String?,
    onJoin: (String) -> Unit,
    onRemind: (String) -> Unit,
    openRoute: (Route) -> Unit,
    onTarget: (FeedTarget) -> Unit,
    onWorkshop: (LiveWorkshop) -> Unit,
) {
    val today = ui.today
    val next = today.batches.firstOrNull { it.id == (today.liveBatchId ?: today.nextBatchId) }
    next?.let { batch ->
        item(key = "hero") {
            // After the last class, the next one is tomorrow's: the response carries that instant separately.
            val startsAt = if (batch.isLiveNow) batch.startsAt else today.nextSessionStartsAt ?: batch.startsAt
            NextSessionHero(batch, startsAt, busyBatch == batch.id, onJoin, onRemind)
        }
    }
    liveClassesRail(ui, onTarget, title = "Live classes this week")
    ui.catalog?.categories?.takeIf { it.isNotEmpty() }?.let { categories ->
        item(key = "tracks-title") { SectionHeader("Programme tracks", actionText = "Library", onAction = { openRoute(Route.YogaExplorer()) }) }
        item(key = "tracks") {
            LazyRow(contentPadding = PaddingValues(horizontal = ThLayout.Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(categories, key = { it.id }) { c -> TrackCard(c) { openRoute(Route.YogaExplorer(c.id)) } }
            }
        }
    }
    item(key = "batches-title") {
        Column {
            SectionHeader("All ${today.batches.size} daily batches")
            Text(
                "Tap any slot to get its reminders. One live link works for all ${today.batches.size} batches.",
                color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = ThLayout.Gutter).padding(bottom = 6.dp),
            )
        }
    }
    items(today.batches, key = { "batch-${it.id}" }) { b ->
        BatchRow(b, busyBatch == b.id, onJoin, onRemind, Modifier.padding(horizontal = ThLayout.Gutter, vertical = 4.dp))
    }
    if (ui.workshops.isNotEmpty()) {
        item(key = "ws-title") { SectionHeader("Live workshops & masterclasses") }
        item(key = "ws") {
            LazyRow(contentPadding = PaddingValues(horizontal = ThLayout.Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(ui.workshops, key = { it.id }) { w -> WorkshopCard(w, onClick = { onWorkshop(w) }) }
            }
        }
    }
    ui.catalog?.sessions?.takeIf { it.isNotEmpty() }?.let { sessions ->
        item(key = "rec-title") { SectionHeader("Past session recordings", actionText = "Library", onAction = { openRoute(Route.YogaExplorer()) }) }
        items(sessions.take(12), key = { "rec-${it.id}" }) { s ->
            RecordingRow(s, Modifier.padding(horizontal = ThLayout.Gutter, vertical = 4.dp)) { openRoute(Route.SessionDetail(s.id)) }
        }
    }
}

private fun LazyListScope.liveClassesRail(ui: YogaUi, onTarget: (FeedTarget) -> Unit, title: String, freeOnly: Boolean = false) {
    val cards = ui.liveClasses?.items.orEmpty()
        .filter { it.state != LiveClassState.CANCELLED && it.state != LiveClassState.ENDED && (!freeOnly || it.isFree) }
    if (cards.isEmpty()) return
    item(key = "live-title-$freeOnly") { SectionHeader(title) }
    item(key = "live-$freeOnly") {
        LazyRow(contentPadding = PaddingValues(horizontal = ThLayout.Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cards, key = { it.id }) { card -> LiveClassCardView(card, onClick = { onTarget(targetFor(card)) }) }
        }
    }
}

/** NextSessionHero: the live or next class with Join (wait room open) or Remind me. */
@Composable
private fun NextSessionHero(batch: YogaBatch, startsAt: String, busy: Boolean, onJoin: (String) -> Unit, onRemind: (String) -> Unit) {
    val now = rememberServerNow()
    val start = parseIsoInstant(startsAt)
    val open = isJoinOpen(startsAt, now)
    val live = start != null && now >= start.toEpochMilli() && batch.isLiveNow
    Box(Modifier.padding(horizontal = ThLayout.Gutter, vertical = 6.dp).fillMaxWidth().clip(ThShapes.Hero).border(1.dp, PlumLine, ThShapes.Hero)) {
        FeedImage(HERO_IMAGE, gradient(PlumBrand, PlumDeep), gradient(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.88f)), Modifier.matchParentSize())
        Column(Modifier.padding(18.dp)) {
            TagPill(
                when {
                    live -> "Live now"
                    start != null -> "Next session · ${if (isToday(start, now)) formatTimeOfDay(start) else formatDayAndTime(start, now)}"
                    else -> "Next session"
                },
                if (live) TagTone.LIVE else TagTone.CORAL,
            )
            Text(batch.title, color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 23.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InstructorAvatar(batch.instructorName, null, 22)
                Text("${batch.instructorName} · 60 min", color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            when {
                open -> HeroButton(if (busy) "Joining…" else if (live) "Join Live Session" else "Join Wait Room", CoralBrand, Icons.Filled.PlayArrow) { onJoin(batch.id) }
                batch.isUserReminderSlot -> HeroButton("Your reminder slot", Color.White.copy(alpha = 0.18f), Icons.Filled.NotificationsActive, onClick = null)
                else -> HeroButton(if (busy) "Saving…" else "Remind me at this slot", Color.White.copy(alpha = 0.18f), Icons.Filled.NotificationsActive) { onRemind(batch.id) }
            }
        }
    }
}

private fun isToday(start: Instant, nowMs: Long): Boolean =
    start.atZone(IST).toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(IST).toLocalDate()

@Composable
private fun HeroButton(label: String, color: Color, icon: ImageVector, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(ThShapes.Md).background(color)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = PaperWhite, modifier = Modifier.size(18.dp))
        Text(label, color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
    }
}

@Composable
private fun TrackCard(c: YogaCategory, onClick: () -> Unit) {
    Box(Modifier.width(170.dp).height(110.dp).clip(ThShapes.Lg).clickable(role = Role.Button, onClick = onClick)) {
        FeedImage(c.imageUrl, gradient(PlumDeep, PlumBrand), gradient(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.78f), GradientDir.VERTICAL), Modifier.matchParentSize())
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(c.name, color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${c.sessionCount} sessions", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun BatchRow(b: YogaBatch, busy: Boolean, onJoin: (String) -> Unit, onRemind: (String) -> Unit, modifier: Modifier) {
    val now = rememberServerNow(ticking = false)
    val open = isJoinOpen(b.startsAt, now)
    val start = parseIsoInstant(b.startsAt)
    Row(
        modifier.fillMaxWidth().clip(ThShapes.Md).background(if (b.isUserReminderSlot) PlumTint else PaperWhite)
            .border(1.dp, if (b.isUserReminderSlot) PlumLine else BorderRule, ThShapes.Md)
            // An open class joins; any other row picks that batch for reminders.
            .clickable(role = Role.Button) { if (open) onJoin(b.id) else onRemind(b.id) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(start?.let { formatTimeOfDay(it) } ?: b.time, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(80.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(b.instructorName, color = TextMuted, fontSize = 11.5.sp, maxLines = 1)
        }
        when {
            busy -> Text("…", color = TextMuted)
            b.isLiveNow -> TagPill("Live", TagTone.LIVE)
            open -> TagPill("Join", TagTone.CORAL)
            b.isUserReminderSlot -> TagPill("Your slot", TagTone.PLUM)
        }
    }
}

@Composable
private fun RecordingRow(s: YogaSession, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(ThShapes.Md).background(PaperWhite).border(1.dp, BorderRule, ThShapes.Md).clickable(role = Role.Button, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(96.dp).aspectRatio(16f / 10f).clip(ThShapes.Sm)) {
            FeedImage(s.imageUrl, gradient(PlumDeep, PlumBrand), gradient(Color.Transparent, Color.Black.copy(alpha = 0.4f), GradientDir.VERTICAL), Modifier.matchParentSize())
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = PaperWhite, modifier = Modifier.align(Alignment.Center).size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(s.title, color = TextPrimary, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${s.instructor.name} · ${s.durationMinutes} min · ${s.level}", color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ── Member: Tracker ───────────────────────────────────────────────────────────

private fun LazyListScope.trackerItems(attendance: UiState<YogaAttendance>?, onRetry: () -> Unit) {
    item(key = "tracker") {
        when (attendance) {
            null, UiState.Loading -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { ThSpinner(size = 24.dp) }
            is UiState.Failed -> ErrorState(attendance.error, Modifier.fillMaxWidth().height(260.dp), onRetry = onRetry)
            is UiState.Ready -> Tracker(attendance.data)
        }
    }
}

@Composable
private fun Tracker(a: YogaAttendance) {
    val now = rememberServerNow(ticking = false)
    Column(Modifier.padding(horizontal = ThLayout.Gutter, vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth().clip(ThShapes.Xl).background(GoldTint).padding(18.dp)) {
            TagPill("Current streak", TagTone.GOLD)
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
                Text("${a.currentStreak}", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text(if (a.currentStreak == 1) " day in a row" else " days in a row", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
            }
            Text("Keep it going. One live practice today maintains your streak.", color = TextSecondary, fontSize = 12.5.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat("${a.classesAttended}", "Classes\nattended", Modifier.weight(1f))
            MiniStat("${a.currentStreak}", "Current\nstreak", Modifier.weight(1f))
            MiniStat("${a.bestStreak}", "Best\nstreak", Modifier.weight(1f))
            MiniStat("${a.attendanceRate}%", "Attendance\nrate", Modifier.weight(1f))
        }
        MonthCalendar(a.attendedDates.toSet(), a.trackingSince, Modifier.padding(top = 14.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(ThShapes.Md).background(SageTint).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🌿")
            Text("Single-source attendance: joining from the app or WhatsApp updates your streak instantly.", color = SageBrand, fontSize = 12.sp, lineHeight = 17.sp)
        }
        if (a.upcomingSessions.isNotEmpty()) {
            SectionHeader("Coming up", modifier = Modifier.padding(top = 4.dp))
            a.upcomingSessions.forEach { s ->
                val start = parseIsoInstant(s.startsAt)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(ThShapes.Md).background(PaperWhite).border(1.dp, BorderRule, ThShapes.Md).padding(12.dp)) {
                    Text(start?.let { formatDayAndTime(it, now) } ?: s.date, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(150.dp))
                    Text(s.title, color = TextMuted, fontSize = 12.5.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(ThShapes.Md).background(PaperWhite).border(1.dp, BorderRule, ThShapes.Md).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = TextMuted, fontSize = 10.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
    }
}

/** §7.1 month calendar in IST: attended, missed (inside the membership), today, future. */
@Composable
private fun MonthCalendar(attended: Set<String>, trackingSince: String?, modifier: Modifier) {
    val now = rememberServerNow(ticking = false)
    val today = Instant.ofEpochMilli(now).atZone(IST).toLocalDate()
    val first = today.withDayOfMonth(1)
    val lead = first.dayOfWeek.value % 7
    val days = today.lengthOfMonth()
    val monthName = first.month.name.lowercase().replaceFirstChar { it.uppercase() }
    Column(modifier.fillMaxWidth().clip(ThShapes.Lg).background(PaperWhite).border(1.dp, BorderRule, ThShapes.Lg).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("$monthName ${first.year}", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Legend(PlumBrand, "Attended")
                Legend(Color(0xFFF6E6E9), "Missed")
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach { Text(it, color = TextMuted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        val cells = List(lead) { null } + (1..days).toList()
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (0 until 7).forEach { i ->
                    val d = week.getOrNull(i)
                    if (d == null) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val date = first.withDayOfMonth(d)
                        val (bg, fg) = calendarCell(date, today, attended, trackingSince)
                        val said = when {
                            date == today -> ", today"
                            bg == PlumTint -> ", attended"
                            fg == CrimsonAlert -> ", missed"
                            else -> ""
                        }
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(ThShapes.Sm).background(bg)
                                .clearAndSetSemantics { contentDescription = "$monthName $d$said" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$d", color = fg, fontSize = 12.sp, fontWeight = if (bg == SurfaceSand) FontWeight.Medium else FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

internal fun calendarCell(date: LocalDate, today: LocalDate, attended: Set<String>, trackingSince: String?): Pair<Color, Color> = when {
    date == today -> PlumBrand to PaperWhite
    date.isAfter(today) -> SurfaceSand to TextMuted
    date.toString() in attended -> PlumTint to PlumBrand
    // Only days inside the membership can be missed.
    trackingSince != null && date.toString() >= trackingSince -> Color(0xFFFCEEEF) to CrimsonAlert
    else -> SurfaceSand to TextMuted
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(ThShapes.Pill).background(color))
        Text(label, color = TextMuted, fontSize = 10.5.sp)
    }
}

// ── Not a member: the sales page with content in it (§7.2) ────────────────────

private val PROGRAMME = listOf(
    "Daily Asana & Flow" to "365 days a year with senior instructors",
    "Pranayama & Yogic Breath" to "52 deep breathing & vitality sessions",
    "Joint Health & Mobility" to "Injury-prevention for desk workers and runners",
    "Yogic Meditation Masterclasses" to "12 monthly deep-dive intensives with Dr. Goswami",
    "Unified WhatsApp Class Link" to "Convenient reminders and 1-tap joining",
)

private fun LazyListScope.freeItems(ui: YogaUi, openRoute: (Route) -> Unit, onTarget: (FeedTarget) -> Unit) {
    item(key = "sell") {
        Column(Modifier.padding(horizontal = ThLayout.Gutter, vertical = 8.dp)) {
            TagPill(if (ui.expired) "Previous member" else "Yoga programme", TagTone.CORAL)
            Text(
                if (ui.expired) "Welcome back to your practice" else "Eight live classes,\nevery single day",
                color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 28.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                "A full morning and evening schedule taught by master instructors from The Yoga Institute. Join any batch on any day.",
                color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Box(
                Modifier.padding(top = 14.dp).fillMaxWidth().height(50.dp).clip(ThShapes.Md).background(CoralBrand)
                    .clickable(role = Role.Button) { openRoute(Route.Paywall(YOGA_PLAN_ID)) },
                contentAlignment = Alignment.Center,
            ) { Text(if (ui.expired) "Renew Yoga Membership" else "Subscribe — Explore Membership", color = PaperWhite, fontWeight = FontWeight.Bold) }
        }
    }
    liveClassesRail(ui, onTarget, title = "Free live classes", freeOnly = true)
    ui.catalog?.sessions?.filter { it.isFree }?.takeIf { it.isNotEmpty() }?.let { free ->
        item(key = "free-title") { SectionHeader("Watch sample sessions (Free)") }
        item(key = "free") {
            LazyRow(contentPadding = PaddingValues(horizontal = ThLayout.Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(free, key = { it.id }) { s -> VideoCard(s, locked = false, onClick = { openRoute(Route.SessionDetail(s.id)) }) }
            }
        }
    }
    item(key = "includes-title") { SectionHeader("What the programme includes") }
    items(PROGRAMME, key = { it.first }) { (title, sub) ->
        Column(Modifier.padding(horizontal = ThLayout.Gutter, vertical = 4.dp).fillMaxWidth().clip(ThShapes.Md).background(PaperWhite).border(1.dp, BorderRule, ThShapes.Md).padding(14.dp)) {
            Text(title, color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = TextMuted, fontSize = 12.sp)
        }
    }
    if (ui.today.batches.isNotEmpty()) {
        item(key = "tt-title") {
            SectionHeader(
                "${ui.today.batches.size} live classes, every day",
                actionText = ui.catalog?.categories?.size?.takeIf { it > 0 }?.let { "$it programme tracks" },
                onAction = { openRoute(Route.YogaExplorer()) },
            )
        }
        item(key = "timetable") {
            Column(Modifier.padding(horizontal = ThLayout.Gutter).fillMaxWidth().clip(ThShapes.Lg).background(PaperWhite).border(1.dp, BorderRule, ThShapes.Lg)) {
                ui.today.batches.forEachIndexed { i, b ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(BorderRule))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(parseIsoInstant(b.startsAt)?.let { formatTimeOfDay(it) } ?: b.time, color = GoldAccent, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, modifier = Modifier.width(80.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(b.instructorName, color = TextMuted, fontSize = 11.5.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
