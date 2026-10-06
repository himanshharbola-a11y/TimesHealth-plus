package timeshealth.app.ui.marathon

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong
import timeshealth.app.core.domain.IST
import timeshealth.app.core.domain.formatPaise
import timeshealth.app.core.domain.parseIsoInstant
import timeshealth.app.core.model.MarathonEvent
import timeshealth.app.core.model.RaceLifecycleStatus
import timeshealth.app.core.model.RaceTier
import timeshealth.app.core.model.ReferralState
import timeshealth.app.ui.components.SectionHeader
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.components.UiStateContent
import timeshealth.app.ui.feed.FeedImage
import timeshealth.app.ui.feed.GradientDir
import timeshealth.app.ui.feed.gradient
import timeshealth.app.ui.navigation.Route
import timeshealth.app.ui.state.UiState
import timeshealth.app.ui.theme.BorderRule
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.Carbon900
import timeshealth.app.ui.theme.Carbon950
import timeshealth.app.ui.theme.CoralBorder
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.CoralTint
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

private val ShortDate = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.forLanguageTag("en-IN")).withZone(IST)

internal fun shortDate(iso: String): String = parseIsoInstant(iso)?.let(ShortDate::format) ?: ""

/** Within India the distance helps pick an edition; from abroad ("12404 km away") it's noise. */
internal fun kmAway(event: MarathonEvent): String? =
    event.distanceFromUserKm?.takeIf { it <= 3000 }?.let { "${it.roundToLong()} km away" }

private fun statusPill(status: RaceLifecycleStatus): Pair<String, TagTone> = when (status) {
    RaceLifecycleStatus.RACE_DAY -> "Race day" to TagTone.LIVE
    RaceLifecycleStatus.COMPLETED -> "Race completed" to TagTone.CORAL
    else -> "Confirmed entry" to TagTone.CORAL
}

@Composable
fun MarathonRoute(viewModel: MarathonViewModel, openRoute: (Route) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MarathonScreen(state, viewModel::retry, viewModel::refresh, openRoute)
}

/**
 * The Marathon tab (PRD §8, design MarathonScreenKt): the title and the
 * Races / Run Tracker segment, present for every user, then the races.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarathonScreen(state: UiState<RacesUi>, onRetry: () -> Unit, onRefresh: () -> Unit, openRoute: (Route) -> Unit) {
    Column(Modifier.fillMaxSize().background(CanvasBg)) {
        UiStateContent(state, onRetry, Modifier.fillMaxSize()) { ui ->
            PullToRefreshBox(isRefreshing = (state as? UiState.Ready)?.refreshing == true, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                Races(ui, openRoute)
            }
        }
    }
}

@Composable
private fun Races(ui: RacesUi, openRoute: (Route) -> Unit) {
    // §8.4: once run, a race's box opens its result, not the registration page.
    val openRace = { e: MarathonEvent, distance: String? ->
        openRoute(if (e.registration?.status == RaceLifecycleStatus.COMPLETED) Route.RaceResults(e.id) else Route.RaceDetail(e.id, distance))
    }
    val openBib = { e: MarathonEvent -> openRoute(Route.Bib(e.id)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        item(key = "header") { Header(openTracker = { openRoute(Route.RunTracker) }) }
        ui.myNext?.let { e ->
            item(key = "next-${e.id}") { RegisteredRaceBox(e, onOpen = { openRace(e, null) }, onBib = { openBib(e) }, Modifier.padding(horizontal = ThLayout.Gutter, vertical = 6.dp)) }
        }
        items(ui.myLater, key = { "later-${it.id}" }) { e ->
            RaceRow(e, onOpen = { openRace(e, null) }, onAction = { openBib(e) }, Modifier.padding(horizontal = ThLayout.Gutter, vertical = 5.dp))
        }
        ui.referral?.let { ref ->
            item(key = "refer") {
                ReferAndWinCard(ref, ui.myNext?.name.orEmpty(), Modifier.padding(horizontal = ThLayout.Gutter, vertical = 8.dp))
            }
        }
        if (ui.past.isNotEmpty()) {
            item(key = "past-title") { SectionHeader("Your past races") }
            items(ui.past, key = { "past-${it.id}" }) { e ->
                RegisteredRaceBox(e, onOpen = { openRace(e, null) }, onBib = { openBib(e) }, Modifier.padding(horizontal = ThLayout.Gutter, vertical = 6.dp))
            }
        }
        ui.hero?.let { e ->
            item(key = "hero-${e.id}") { FreePrimaryRaceHero(e, onOpen = { d -> openRace(e, d) }, Modifier.padding(horizontal = ThLayout.Gutter, vertical = 6.dp)) }
        }
        if (ui.rest.isNotEmpty()) {
            item(key = "rest-title") { SectionHeader(if (ui.hasEntries) "Other upcoming editions" else "Upcoming editions across India") }
        }
        items(ui.rest, key = { "rest-${it.id}" }) { e ->
            RaceRow(e, onOpen = { openRace(e, null) }, onAction = { openRace(e, null) }, Modifier.padding(horizontal = ThLayout.Gutter, vertical = 5.dp))
        }
    }
}

/** Design lambda$10: "TimesHealth+ Marathon" serif 24, then the Races / Run Tracker segment. */
@Composable
private fun Header(openTracker: () -> Unit) {
    Column(Modifier.padding(horizontal = ThLayout.Gutter, vertical = 8.dp)) {
        Text("TimesHealth+ Marathon", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(ThShapes.Md).background(SurfaceSand).padding(4.dp)) {
            Box(Modifier.weight(1f).clip(ThShapes.Sm).background(PaperWhite).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text("Races", color = CoralBrand, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            // The tracker is full screen (it keeps recording with the screen off), so the segment opens it.
            Box(Modifier.weight(1f).clip(ThShapes.Sm).clickable(role = Role.Tab, onClick = openTracker).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text("Run Tracker", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ── Registered race box: design RegisteredRaceBox ─────────────────────────────

@Composable
private fun RegisteredRaceBox(event: MarathonEvent, onOpen: () -> Unit, onBib: () -> Unit, modifier: Modifier) {
    val reg = event.registration ?: return
    val (pill, tone) = statusPill(reg.status)
    val completed = reg.status == RaceLifecycleStatus.COMPLETED
    Column(
        modifier.fillMaxWidth().clip(ThShapes.Xl).background(PaperWhite).border(1.dp, BorderRule, ThShapes.Xl).clickable(role = Role.Button, onClick = onOpen),
    ) {
        Box(Modifier.fillMaxWidth().height(115.dp)) {
            FeedImage(event.imageUrl, gradient(Color(0xFF2C1338), Carbon950, GradientDir.VERTICAL), gradient(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.8f), GradientDir.VERTICAL), Modifier.matchParentSize())
            Row(Modifier.fillMaxWidth().padding(12.dp).align(Alignment.BottomStart), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagPill(pill, tone)
                    if (event.rescheduledFrom != null) TagPill("Rescheduled", TagTone.GOLD)
                }
                Text(
                    "REF: ${reg.bibNumber ?: reg.registrationRef}", color = PaperWhite, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, modifier = Modifier.clip(ThShapes.Tag).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
        Column(Modifier.padding(18.dp)) {
            Text(event.name, color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text("${reg.category} · ${shortDate(event.startsAt)} · Flag-off: ${event.flagOffTime}", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            if (completed) {
                SolidButton("Check Result & Certificate", PlumDeep, onOpen, Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SolidButton("View Digital Bib", Carbon900, onBib, Modifier.weight(1f))
                    OutlineButton("Race Details", onOpen, Modifier.weight(1f))
                }
            }
        }
    }
}

// ── Free primary box: design FreePrimaryRaceHero ──────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FreePrimaryRaceHero(event: MarathonEvent, onOpen: (String?) -> Unit, modifier: Modifier) {
    val options = event.distanceOptions
    var picked by rememberSaveable(event.id) { mutableStateOf((options.firstOrNull { it.code == "21K" } ?: options.lastOrNull())?.code) }
    val selected = options.firstOrNull { it.code == picked }
    val open = event.registrationOpen && (selected?.registrationOpen ?: true)
    val price = selected?.pricePaise?.get(RaceTier.CLASSIC)?.takeIf { it > 0 } ?: fromPaise(event)
    val year = parseIsoInstant(event.startsAt)?.atZone(IST)?.year
    Column(
        modifier.fillMaxWidth().clip(ThShapes.Hero).background(PaperWhite).border(1.dp, BorderRule, ThShapes.Hero).clickable(role = Role.Button) { onOpen(picked) },
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 230.dp)) {
            FeedImage(event.imageUrl, gradient(Color(0xFF1E284A), Color(0xFF11172E)), gradient(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.82f), GradientDir.VERTICAL), Modifier.matchParentSize())
            Column(Modifier.fillMaxWidth().heightIn(min = 230.dp).padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (event.registrationOpen) TagPill("Registrations open${year?.let { " · $it" } ?: ""}", TagTone.CORAL) else TagPill("Registrations closed", TagTone.NEUTRAL)
                        if (event.rescheduledFrom != null) TagPill("Rescheduled", TagTone.GOLD)
                    }
                    kmAway(event)?.let { Text(it.uppercase(Locale.ROOT), color = PaperWhite, fontSize = 9.5.sp, fontWeight = FontWeight.Bold) }
                }
                Column {
                    Text(event.name, color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text("${event.city} · ${shortDate(event.startsAt)} · ${event.venue}", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 2)
                }
            }
        }
        Column(Modifier.padding(18.dp)) {
            Text("CHOOSE YOUR DISTANCE", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { d -> DistanceChip(d.code, d.code == picked) { picked = d.code } }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("From ${formatPaise(price)}", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Classic Entry with chip & t-shirt", color = TextMuted, fontSize = 11.sp)
                }
                if (open) SolidButton("Register", CoralBrand, { onOpen(picked) }) else TagPill(if (event.registrationOpen) "${picked ?: "Distance"} closed" else "Entries closed", TagTone.NEUTRAL)
            }
        }
    }
}

@Composable
internal fun DistanceChip(code: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        code,
        color = if (selected) CoralBrand else TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(ThShapes.Md)
            .background(if (selected) CoralTint else SurfaceSand)
            .border(1.dp, if (selected) CoralBorder else Color.Transparent, ThShapes.Md)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

// ── Compact row: design OtherRaceEditionCard ──────────────────────────────────

@Composable
private fun RaceRow(event: MarathonEvent, onOpen: () -> Unit, onAction: () -> Unit, modifier: Modifier) {
    val reg = event.registration
    Row(
        modifier.fillMaxWidth().clip(ThShapes.Lg).background(PaperWhite).border(1.dp, BorderRule, ThShapes.Lg).clickable(role = Role.Button, onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(62.dp).clip(ThShapes.Md)) {
            FeedImage(event.imageUrl, gradient(CoralBrand, Color(0xFF8A1E14)), null, Modifier.matchParentSize())
        }
        Column(Modifier.weight(1f)) {
            if (reg != null || event.rescheduledFrom != null) {
                Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    reg?.let { val (p, t) = statusPill(it.status); TagPill(p, t) }
                    if (event.rescheduledFrom != null) TagPill("Rescheduled", TagTone.GOLD)
                }
            }
            Text(event.name, color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 14.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (reg != null) "${reg.category} · ${shortDate(event.startsAt)}" else "${shortDate(event.startsAt)} · ${event.distanceOptions.joinToString(" / ") { it.code }}",
                color = TextMuted, fontSize = 11.5.sp, maxLines = 1,
            )
            if (reg == null) {
                Text(
                    (if (event.registrationOpen) "From ${formatPaise(fromPaise(event))}" else "Registrations closed") + (kmAway(event)?.let { "  ·  $it" } ?: ""),
                    color = if (event.registrationOpen) CoralBrand else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                )
            }
        }
        // §8.3: a closed edition shows a closed state, never a register action.
        if (reg != null || event.registrationOpen) {
            Text(
                if (reg != null) "View Bib" else "Register", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(ThShapes.Md).background(SurfaceSand).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 12.dp, vertical = 10.dp),
            )
        } else {
            TagPill("Closed", TagTone.NEUTRAL)
        }
    }
}

// ── Refer & Win ───────────────────────────────────────────────────────────────

/** §8.3: share your code; 5 friends registering earns a guaranteed Premium upgrade, each one a lucky-draw entry. */
@Composable
internal fun ReferAndWinCard(referral: ReferralState, eventName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val count = referral.confirmedReferrals.coerceAtMost(5)
    Column(modifier.fillMaxWidth().clip(ThShapes.Xl).background(PlumTint).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = PlumDeep)
            Text("Refer & Win", color = PlumDeep, fontFamily = ThFonts.Serif, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            when {
                referral.upgradeClaimed -> "Your free Premium upgrade has been claimed. Every friend still earns you a lucky-draw entry."
                referral.guaranteedUpgradeUnlocked -> "5 friends registered — your free Premium upgrade is ready to claim on the race page."
                else -> "Get 5 friends to register for $eventName with your code and your entry becomes Premium VIP, free."
            },
            color = TextSecondary, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(5) { i -> Box(Modifier.weight(1f).height(6.dp).clip(ThShapes.Tag).background(if (i < count) CoralBrand else Color.White)) }
        }
        Text("$count of 5 friends · ${referral.luckyDrawEntries} lucky-draw entries", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(referral.code, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(ThShapes.Md).background(PlumDeep).clickable(role = Role.Button) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Run $eventName with me! Register with my code ${referral.code}: ${referral.shareUrl}")
                    }
                    context.startActivity(Intent.createChooser(send, "Share your code"))
                }.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, tint = PaperWhite, modifier = Modifier.size(16.dp))
                Text("Share", color = PaperWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Buttons ───────────────────────────────────────────────────────────────────

@Composable
internal fun SolidButton(label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(44.dp).clip(ThShapes.Md).background(color).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = PaperWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
internal fun OutlineButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(44.dp).clip(ThShapes.Md).border(1.dp, BorderRule, ThShapes.Md).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}
