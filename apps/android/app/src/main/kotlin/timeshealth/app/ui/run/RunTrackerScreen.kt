package timeshealth.app.ui.run

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import timeshealth.app.core.domain.formatDayAndTime
import timeshealth.app.core.domain.parseIsoInstant
import timeshealth.app.core.model.RunHistoryResponse
import timeshealth.app.core.domain.IST
import timeshealth.app.core.domain.PacePoint
import timeshealth.app.core.domain.Split
import timeshealth.app.core.domain.formatDuration
import timeshealth.app.core.runtracker.RunTracker
import timeshealth.app.core.runtracker.LatLng
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.components.ThSpinner
import timeshealth.app.ui.run.map.LocalRouteMap
import timeshealth.app.ui.run.map.RouteMapState
import timeshealth.app.ui.theme.AmberWarn
import timeshealth.app.ui.theme.BorderRule
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.Carbon900
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.CoralTint
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThShapes

/** Tabular figures: digits keep their width, so a ticking time doesn't jiggle. */
private val TabularNums = TextStyle(fontFeatureSettings = "tnum")

/** Where the map opens before the first fix: New Delhi. */
private val DefaultCenter = LatLng(28.6139, 77.2090)

@Composable
fun RunTrackerRoute(viewModel: RunTrackerViewModel, onClose: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val needsPermission by viewModel.needsPermission.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        viewModel.permissionResult(result[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }
    LaunchedEffect(needsPermission) {
        if (needsPermission) {
            // The run notification (pause / finish from the shade) needs this on Android 13+.
            val extra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
            permissions.launch(RunTracker.LOCATION_PERMISSIONS + extra)
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize().background(CanvasBg)) {
        when (val state = ui) {
            RunUi.Loading -> ThSpinner(Modifier.align(Alignment.Center), size = 28.dp)
            RunUi.NoOwner -> Text("Sign in to track a run.", Modifier.align(Alignment.Center), color = TextSecondary)
            RunUi.Ready -> ReadyScreen(history, viewModel.imperial, onStart = viewModel::start, onClose = onClose, onOpenRun = viewModel::openPast)
            is RunUi.Active -> ActiveScreen(state, viewModel.imperial, viewModel::nowMs, viewModel::pause, viewModel::resume, viewModel::finish)
            is RunUi.Summary -> SummaryScreen(state, viewModel.imperial, onDone = viewModel::done, onClose = onClose)
            RunUi.TooShort -> TooShortScreen(onDone = viewModel::done)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

// ── Ready ─────────────────────────────────────────────────────────────────────

@Composable
private fun ReadyScreen(history: RunHistoryResponse?, imperial: Boolean, onStart: () -> Unit, onClose: () -> Unit, onOpenRun: (timeshealth.app.core.model.RunRecord) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        LocalRouteMap.current.RouteMap(RouteMapState(emptyList(), RouteMapState.Mode.FOLLOW, DefaultCenter), Modifier.fillMaxSize())
        CloseButton(onClose, Modifier.align(Alignment.TopStart))
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(ThShapes.SheetTop)
                .background(PaperWhite)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.DirectionsRun, contentDescription = null, tint = CoralBrand)
                Text("Outdoor run", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.GpsFixed, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
                Text("GPS tracks distance, pace & route, even with the screen off.", color = TextMuted, fontSize = 12.sp)
            }
            history?.takeIf { it.totals.runs > 0 }?.let { RunHistory(it, imperial, onOpenRun) }
            Spacer(Modifier.height(18.dp))
            RoundButton("Start", Icons.Filled.PlayArrow, CoralBrand, size = 84, onClick = onStart)
        }
    }
}

/** Totals and the last few runs, so the start sheet shows progress (Strava's "You" strip). */
@Composable
private fun RunHistory(h: RunHistoryResponse, imperial: Boolean, onOpen: (timeshealth.app.core.model.RunRecord) -> Unit) {
    val unit = unitLabel(imperial)
    Column(Modifier.padding(top = 14.dp).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(ThShapes.Md).background(CanvasBg).padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            HistoryStat("${h.totals.runs}", "Runs")
            HistoryStat(distanceText(h.totals.distanceKm * 1000, imperial), "Total $unit")
            HistoryStat(distanceText(h.totals.monthDistanceKm * 1000, imperial), "This month")
            HistoryStat(distanceText(h.totals.longestKm * 1000, imperial), "Longest")
        }
        h.runs.take(3).forEach { r ->
            val started = parseIsoInstant(r.startedAt)
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(ThShapes.Sm)
                    .clickable(role = Role.Button) { onOpen(r) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(started?.let { runName(it.toEpochMilli()) } ?: "Run", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(started?.let { formatDayAndTime(it, System.currentTimeMillis()) }.orEmpty(), color = TextMuted, fontSize = 11.sp)
                }
                Text("${distanceText(r.distanceKm * 1000, imperial)} $unit", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, style = TabularNums)
                Text("  ${paceText(r.avgPaceSecPerKm.toDouble(), imperial)} /$unit", color = TextMuted, fontSize = 11.5.sp, style = TabularNums)
                Icon(Icons.Filled.ChevronRight, contentDescription = "Open run", tint = TextMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun HistoryStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, style = TabularNums)
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}

// ── Recording ─────────────────────────────────────────────────────────────────

/** "5:42" per km (or mile): Strava's pace style. "--:--" with no distance yet. */
internal fun paceText(secPerKm: Double, imperial: Boolean): String {
    val perUnit = if (imperial) secPerKm * 1.609344 else secPerKm
    if (!perUnit.isFinite() || perUnit <= 0 || perUnit > 99 * 60) return "--:--"
    val total = perUnit.roundToLong()
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

internal fun distanceText(meters: Double, imperial: Boolean): String =
    String.format(Locale.ROOT, "%.2f", if (imperial) meters / 1609.344 else meters / 1000.0)

private fun unitLabel(imperial: Boolean) = if (imperial) "mi" else "km"

@Composable
private fun ActiveScreen(
    state: RunUi.Active,
    imperial: Boolean,
    nowMs: () -> Long,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
) {
    val run = state.run
    // Moving time ticks here once a second; nothing in the database changes every second.
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(run.isPaused) {
        while (true) {
            now = nowMs()
            delay(1_000 - now % 1_000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LocalRouteMap.current.RouteMap(RouteMapState(state.route, RouteMapState.Mode.FOLLOW, DefaultCenter), Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TagPill(if (run.isPaused) "Paused" else "Recording", if (run.isPaused) TagTone.GOLD else TagTone.LIVE)
                if (run.hasWeakSignal) TagPill("Weak GPS signal", TagTone.CORAL)
            }
        }
        Column(
            Modifier.fillMaxWidth().background(PaperWhite).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Stat("Time", formatDuration(run.movingSeconds(now)), big = true)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Stat("Distance (${unitLabel(imperial)})", distanceText(run.distanceM, imperial), Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(48.dp).background(BorderRule).align(Alignment.CenterVertically))
                Stat("Avg pace (/${unitLabel(imperial)})", paceText(run.paceSecPerKm(now), imperial), Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            if (run.isPaused) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton("Resume", Icons.Filled.PlayArrow, CoralBrand, size = 72, onClick = onResume)
                    RoundButton("Finish", Icons.Filled.Stop, Carbon900, size = 72, onClick = onFinish)
                }
            } else {
                RoundButton("Pause", Icons.Filled.Pause, CoralBrand, size = 72, onClick = onPause)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, big: Boolean = false) {
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            // Tabular figures: the digits don't jiggle as the time ticks (monospace spaced out the colon).
            value, color = TextPrimary, fontWeight = FontWeight.Bold, style = TabularNums,
            fontSize = if (big) 56.sp else 32.sp, lineHeight = if (big) 60.sp else 36.sp,
        )
        Text(label.uppercase(Locale.ROOT), color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    }
}

@Composable
private fun RoundButton(label: String, icon: ImageVector, color: Color, size: Int, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = PaperWhite, modifier = Modifier.size((size * 0.42).dp))
        }
        Text(label, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .statusBarsPadding()
            .padding(12.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(PaperWhite)
            .border(1.dp, BorderRule, CircleShape)
            .clickable(role = Role.Button, onClick = onClose)
            .semantics { contentDescription = "Close" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, contentDescription = null, tint = TextPrimary)
    }
}

// ── Summary ───────────────────────────────────────────────────────────────────

/** "Morning Run", "Evening Run"…: Strava's default activity names, by the start hour in IST. */
internal fun runName(startedAtMs: Long): String = when (Instant.ofEpochMilli(startedAtMs).atZone(IST).hour) {
    in 5..11 -> "Morning Run"
    in 12..16 -> "Afternoon Run"
    in 17..20 -> "Evening Run"
    else -> "Night Run"
}

private val SummaryDate = DateTimeFormatter.ofPattern("EEE, d MMM yyyy · h:mm a", Locale.forLanguageTag("en-IN")).withZone(IST)

@Composable
private fun SummaryScreen(state: RunUi.Summary, imperial: Boolean, onDone: () -> Unit, onClose: () -> Unit) {
    val run = state.run
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            LocalRouteMap.current.RouteMap(RouteMapState(state.route, RouteMapState.Mode.FIT, DefaultCenter), Modifier.fillMaxSize())
            CloseButton(onClose, Modifier.align(Alignment.TopStart))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(runName(run.startedAt), color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(SummaryDate.format(Instant.ofEpochMilli(run.startedAt)), color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                SummaryStat("Distance", "${distanceText(run.distanceM, imperial)} ${unitLabel(imperial)}", Modifier.weight(1f))
                SummaryStat("Avg pace", "${paceText(run.avgPaceSecPerKm.toDouble(), imperial)} /${unitLabel(imperial)}", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                SummaryStat("Moving time", formatDuration(run.durationSeconds.toLong()), Modifier.weight(1f))
                SummaryStat("Calories (est.)", "${run.caloriesBurned} kcal", Modifier.weight(1f))
            }
            if (run.hasAccuracyWarning) {
                Text(
                    "Weak GPS during this run: the distance may be a little short.",
                    color = AmberWarn, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp),
                )
            }
            if (state.pace.size >= 3) {
                SectionTitle("Pace")
                PaceChart(state.pace, imperial, Modifier.fillMaxWidth().height(140.dp))
            }
            if (state.splits.isNotEmpty()) {
                SectionTitle("Splits")
                Splits(state.splits, imperial)
            }
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(ThShapes.Md)
                    .background(CoralBrand)
                    .clickable(role = Role.Button, onClick = onDone),
                contentAlignment = Alignment.Center,
            ) { Text("Done", color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
            Text(
                "Saved to your profile. It uploads automatically when you're online.",
                color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp).navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label.uppercase(Locale.ROOT), color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text(value, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
}

/** Strava-style splits: number, pace, and a bar that is longer the faster the split. */
@Composable
private fun Splits(splits: List<Split>, imperial: Boolean) {
    val fastest = splits.minOf { it.paceSecPerKm }.coerceAtLeast(1.0)
    val slowest = splits.maxOf { it.paceSecPerKm }
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(unitLabel(imperial).uppercase(Locale.ROOT), color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
        Text("PACE", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
    }
    splits.forEach { split ->
        // The fastest split fills the bar; slower ones shrink towards 35%.
        val span = (slowest - fastest).coerceAtLeast(1.0)
        val fraction = 1.0 - 0.65 * ((split.paceSecPerKm - fastest) / span)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val label = if (split.partial) String.format(Locale.ROOT, "%.1f", split.distanceM / (if (imperial) 1609.344 else 1000.0)) else split.index.toString()
            Text(label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
            Text(paceText(split.paceSecPerKm, imperial), color = TextPrimary, style = TabularNums, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp))
            Box(Modifier.weight(1f).height(14.dp)) {
                Box(Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0.05f, 1f)).fillMaxHeight().clip(ThShapes.Tag).background(if (split.paceSecPerKm == fastest) CoralBrand else CoralBrand.copy(alpha = 0.55f)))
            }
        }
    }
}

/** Pace over distance, faster higher (as Strava draws it), filled under the line. */
@Composable
private fun PaceChart(points: List<PacePoint>, imperial: Boolean, modifier: Modifier) {
    val fastest = points.minOf { it.paceSecPerKm }
    val slowest = points.maxOf { it.paceSecPerKm }
    val maxKm = points.last().distanceKm.coerceAtLeast(0.01)
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Fastest ${paceText(fastest, imperial)}", color = TextMuted, fontSize = 10.sp)
            Text("Slowest ${paceText(slowest, imperial)}", color = TextMuted, fontSize = 10.sp)
        }
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp).clip(ThShapes.Sm).background(CoralTint)) {
            val span = (slowest - fastest).coerceAtLeast(1.0)
            fun x(km: Double) = (km / maxKm * size.width).toFloat()
            // Faster (smaller) pace plots higher.
            fun y(pace: Double) = (8f + (pace - fastest) / span * (size.height - 16f)).toFloat()
            val line = Path().apply {
                points.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.distanceKm), y(p.paceSecPerKm)) else lineTo(x(p.distanceKm), y(p.paceSecPerKm)) }
            }
            val area = Path().apply {
                addPath(line)
                lineTo(x(points.last().distanceKm), size.height)
                lineTo(x(points.first().distanceKm), size.height)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(CoralBrand.copy(alpha = 0.35f), CoralBrand.copy(alpha = 0.05f))))
            drawPath(line, CoralBrand, style = Stroke(width = 4f))
            drawCircle(CoralBrand, 5f, Offset(x(points.last().distanceKm), y(points.last().paceSecPerKm)))
        }
    }
}

// ── Too short ─────────────────────────────────────────────────────────────────

@Composable
private fun TooShortScreen(onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.DirectionsRun, contentDescription = null, tint = CoralBrand, modifier = Modifier.size(40.dp))
        Text("Too short to save", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Text("Runs under 10 m aren't saved. Head out and try again!", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(20.dp))
        RoundButton("OK", Icons.Filled.PlayArrow, CoralBrand, size = 56, onClick = onDone)
    }
}
