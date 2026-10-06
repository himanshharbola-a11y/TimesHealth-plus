package timeshealth.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import timeshealth.app.core.domain.IST
import timeshealth.app.core.domain.formatDayAndTime
import timeshealth.app.core.domain.formatPaise
import timeshealth.app.core.domain.formatTimeOfDay
import timeshealth.app.core.domain.parseIsoInstant
import timeshealth.app.core.domain.secondsUntil
import timeshealth.app.core.model.HeroMyRace
import timeshealth.app.core.model.HeroRaceResult
import timeshealth.app.core.model.HeroSellMarathon
import timeshealth.app.core.model.HeroSellYoga
import timeshealth.app.core.model.HeroSessionState
import timeshealth.app.core.model.HeroYogaRenew
import timeshealth.app.core.model.HeroYogaSession
import timeshealth.app.core.model.KnownHeroSlot
import timeshealth.app.ui.components.RemoteImage
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.theme.BorderRule
import timeshealth.app.ui.theme.Carbon900
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlumBrand
import timeshealth.app.ui.theme.PlumDeep
import timeshealth.app.ui.theme.PlumLine
import timeshealth.app.ui.theme.PlumTint
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThShapes
import timeshealth.app.ui.theme.Tints

/*
 * Hero banners (PRD §6.1). Priority is resolved on the server; these only
 * render what they are handed. Visuals are the design's HeroYogaSlot1 /
 * HeroRaceSlot1 / HeroSellYogaSlot1 / HeroYogaExpiredSlot1 (HomeScreenKt), via
 * the RN port (components/feed/Hero.tsx): a 22dp photo card with a 1dp rule,
 * no shadow, a 35%→88% black scrim, a TagPill status line, a serif title and a
 * full-width 48dp CTA. Slot 2 is the compact white row ([HeroSecondaryCard]).
 */

private val YogaFallback = gradient(PlumBrand, PlumDeep)
private val RaceFallback = gradient(Color(0xFF1E284A), Color(0xFF11172E))
private val HeroScrim = gradient(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.88f))
private val White85 = Color.White.copy(alpha = 0.85f)

/** "Sun, 20 Oct 2026": the design's MarathonEvent.dateStr. */
private val RaceDate = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.forLanguageTag("en-IN")).withZone(IST)

fun formatRaceDate(iso: String): String = parseIsoInstant(iso)?.let(RaceDate::format) ?: ""

/** Slot 1. */
@Composable
fun HeroCard(slot: KnownHeroSlot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    when (slot) {
        is HeroYogaSession -> YogaSessionHero(slot, onClick, modifier)
        is HeroYogaRenew -> RenewHero(slot, onClick, modifier)
        is HeroMyRace -> MyRaceHero(slot, onClick, modifier)
        is HeroSellYoga -> PromoHero("Daily live programme", TagTone.CORAL, yoga = true, slot.title, slot.subtitle, slot.ctaLabel, slot.imageUrl, onClick, modifier)
        is HeroSellMarathon -> PromoHero("TimesHealth+ Marathon", TagTone.CORAL, yoga = false, slot.title, slot.subtitle, slot.ctaLabel, slot.imageUrl, onClick, modifier)
        is HeroRaceResult -> PromoHero("Race completed", TagTone.GOLD, yoga = false, slot.title, slot.subtitle, slot.ctaLabel, slot.imageUrl, onClick, modifier)
    }
}

/** The live/next state of a yoga hero at [nowMs]: what the pill, CTA and countdown say. */
internal data class YogaHeroState(val live: Boolean, val startingSoon: Boolean, val minutes: Int)

internal fun yogaHeroState(slot: HeroYogaSession, nowMs: Long): YogaHeroState {
    val seconds = if (slot.state == HeroSessionState.STARTING_SOON) {
        parseIsoInstant(slot.startsAt)?.let { secondsUntil(it.toEpochMilli(), nowMs) } ?: slot.secondsToStart?.toLong()
    } else {
        slot.secondsToStart?.toLong()
    }
    // The wait room counts down to the class: at zero the class IS live, without waiting for a refetch.
    val live = slot.state == HeroSessionState.LIVE || (slot.state == HeroSessionState.STARTING_SOON && seconds == 0L)
    val minutes = maxOf(1, ceil((seconds ?: 0L) / 60.0).toInt())
    return YogaHeroState(live, !live && slot.state == HeroSessionState.STARTING_SOON, minutes)
}

@Composable
private fun YogaSessionHero(slot: HeroYogaSession, onClick: () -> Unit, modifier: Modifier) {
    val now = rememberServerNow(ticking = slot.state == HeroSessionState.STARTING_SOON)
    val s = yogaHeroState(slot, now)
    val startsAt = parseIsoInstant(slot.startsAt)
    val (pill, tone) = when {
        // Never "Starting now" during the live hour: the class has started.
        s.live -> "Live now" to TagTone.LIVE
        s.startingSoon -> "Next session · Starts in ${s.minutes} min" to TagTone.CORAL
        slot.state == HeroSessionState.SCHEDULED_TODAY && startsAt != null -> "Next session · ${formatTimeOfDay(startsAt)}" to TagTone.CORAL
        // §6.1 edge case: nothing left today → the day, not a timer.
        startsAt != null -> "Next session · ${formatDayAndTime(startsAt, now)}" to TagTone.CORAL
        else -> "Next session" to TagTone.CORAL
    }
    val cta = when {
        s.live && slot.state != HeroSessionState.LIVE -> "Join Live Session"
        s.startingSoon -> "Starts in ${s.minutes} min — ${slot.ctaLabel}"
        else -> slot.ctaLabel
    }
    val meta = when {
        slot.instructorName.isNotBlank() -> "${slot.instructorName} · ${slot.durationMinutes} min"
        else -> slot.subtitle
    }
    PhotoShell(yoga = true, imageUrl = slot.imageUrl, onClick = onClick, description = "$pill. ${slot.title}. $cta", modifier = modifier) {
        TagPill(pill, tone)
        Text(
            slot.title, color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 23.sp, lineHeight = 27.sp,
            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
        )
        if (!meta.isNullOrBlank()) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (slot.instructorName.isNotBlank()) InstructorAvatar(slot.instructorName, slot.instructorAvatarUrl, 22)
                Text(meta, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(16.dp))
        // Only a live class or an open wait room is joinable; later ones open the schedule.
        HeroCta(cta, icon = if (s.live || s.startingSoon) Icons.Filled.PlayArrow else Icons.Filled.Event)
    }
}

@Composable
private fun MyRaceHero(slot: HeroMyRace, onClick: () -> Unit, modifier: Modifier) {
    val now = rememberServerNow(ticking = !slot.isRaceDay)
    val target = parseIsoInstant(slot.startsAt)
    val secs = if (target != null) secondsUntil(target.toEpochMilli(), now) else maxOf(0, slot.daysRemaining) * 86_400L
    val days = secs / 86_400
    val hours = (secs % 86_400) / 3600
    val mins = (secs % 3600) / 60
    val meta = listOf(slot.category, formatRaceDate(slot.startsAt), slot.flagOffTime).filter { it.isNotBlank() }.joinToString(" · ")
    PhotoShell(
        yoga = false, imageUrl = slot.imageUrl, onClick = onClick,
        description = "${slot.title}. ${if (slot.isRaceDay) "Race day" else "$days days to go"}. ${slot.ctaLabel}",
        modifier = modifier,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TagPill(if (slot.isRaceDay) "Race day" else "Registered", TagTone.CORAL)
            slot.bibNumber?.let { Text("BIB #$it", color = White85, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
        HeroTitle(slot.title, Modifier.padding(top = 8.dp))
        Text(meta, color = White85, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        if (slot.isRaceDay) {
            // Generic on purpose: gate numbers differ per venue and are on the bib pass.
            val pill = if (slot.flagOffTime.isNotBlank()) "Flag-off ${slot.flagOffTime} · Report at gate" else "Race day · Report at gate"
            TagPill(pill, TagTone.LIVE, Modifier.padding(bottom = 14.dp))
        } else {
            Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountdownBox(days.toString(), "DAYS")
                CountdownBox(hours.toString().padStart(2, '0'), "HOURS")
                CountdownBox(mins.toString().padStart(2, '0'), "MINS")
            }
        }
        HeroCta(slot.ctaLabel)
    }
}

@Composable
private fun PromoHero(
    pill: String,
    tone: TagTone,
    yoga: Boolean,
    title: String,
    subtitle: String?,
    cta: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    PhotoShell(yoga = yoga, imageUrl = imageUrl, onClick = onClick, description = "$pill. ${title.replace('\n', ' ')}. $cta", modifier = modifier) {
        TagPill(pill, tone)
        // SELL_YOGA's title carries the design's own "\n" line break.
        HeroTitle(title, Modifier.padding(top = 8.dp), maxLines = 3)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, color = White85, fontSize = 12.sp, lineHeight = 19.sp, maxLines = 3, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        } else {
            Spacer(Modifier.height(14.dp))
        }
        HeroCta(cta)
    }
}

/** HeroYogaExpiredSlot1: a light card, warm not cold (§5). */
@Composable
private fun RenewHero(slot: HeroYogaRenew, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(ThShapes.Hero)
            .background(PlumTint)
            .border(1.dp, PlumLine, ThShapes.Hero)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Membership expired. ${slot.title}. ${slot.ctaLabel}" }
            .padding(18.dp),
    ) {
        TagPill("Membership expired", TagTone.GOLD)
        Text(
            slot.title, color = PlumDeep, fontFamily = ThFonts.Serif, fontSize = 22.sp, lineHeight = 27.sp,
            fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.padding(top = 8.dp),
        )
        if (!slot.subtitle.isNullOrBlank()) {
            Text(slot.subtitle!!, color = TextSecondary, fontSize = 12.sp, lineHeight = 19.sp, maxLines = 3, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        } else {
            Spacer(Modifier.height(14.dp))
        }
        HeroCta(slot.ctaLabel, color = PlumBrand)
    }
}

// ── Shared pieces ─────────────────────────────────────────────────────────────

@Composable
private fun PhotoShell(
    yoga: Boolean,
    imageUrl: String?,
    onClick: () -> Unit,
    description: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(ThShapes.Hero)
            .background(Carbon900)
            .border(1.dp, if (yoga) PlumLine else BorderRule, ThShapes.Hero)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        FeedImage(imageUrl, if (yoga) YogaFallback else RaceFallback, HeroScrim, Modifier.matchParentSize())
        Column(Modifier.padding(18.dp)) { content() }
    }
}

@Composable
private fun HeroTitle(text: String, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Text(
        text, color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 24.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.Bold, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier,
    )
}

/** M3 Button as the design sizes it: full width, 48dp, 12dp corners. */
@Composable
private fun HeroCta(label: String, color: Color = CoralBrand, icon: ImageVector? = null) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(ThShapes.Md).background(color).padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = PaperWhite, modifier = Modifier.size(18.dp))
        Text(label, color = PaperWhite, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CountdownBox(value: String, label: String) {
    Column(
        Modifier
            .clip(ThShapes.Sm)
            .background(Color.Black.copy(alpha = 0.2f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), ThShapes.Sm)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = PaperWhite, fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/** CommonComponentsKt.InstructorAvatarImage: initials on plum→coral, the photo over it. */
@Composable
fun InstructorAvatar(name: String, url: String?, sizeDp: Int) {
    val initials = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(gradient(PlumDeep, CoralBrand)), contentAlignment = Alignment.Center) {
        Text(initials, color = PaperWhite, fontSize = (sizeDp * 0.38).sp, fontWeight = FontWeight.Bold)
        if (!url.isNullOrBlank()) {
            RemoteImage(url, contentDescription = null, modifier = Modifier.fillMaxSize(), placeholder = gradient(Color.Transparent, Color.Transparent))
        }
    }
}

// ── Slot 2: the compact white row (HomeScreenKt Secondary*Card) ───────────────

@Composable
fun HeroSecondaryCard(slot: KnownHeroSlot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    when (slot) {
        is HeroMyRace -> SecondaryRow(14, onClick, { IconTile(Icons.Filled.DirectionsRun, Tints.Coral.bg, Tints.Coral.fg) }, slot.title, raceLine(slot), { Chevron() }, modifier)
        is HeroSellMarathon -> {
            val from = if (slot.fromPricePaise > 0) "From ${formatPaise(slot.fromPricePaise)}" else null
            val line = listOfNotNull(formatRaceDate(slot.startsAt).takeIf { it.isNotEmpty() }, from).joinToString(" · ").ifEmpty { slot.subtitle.orEmpty() }
            SecondaryRow(12, onClick, { Thumb(slot.imageUrl, Icons.Filled.DirectionsRun) }, slot.title, line, { TagPill("Register", TagTone.CORAL) }, modifier)
        }
        // As the runner-up the design pitches yoga as an add-on to running, in its own words.
        is HeroSellYoga -> SecondaryRow(
            12, onClick, { Thumb(slot.imageUrl, Icons.Filled.SelfImprovement) }, "Add Yoga to your running",
            "Mobility and recovery sessions, 8 daily batches", { TagPill("Explore", TagTone.SAGE) }, modifier,
        )
        is HeroRaceResult -> SecondaryRow(14, onClick, { IconTile(Icons.Filled.EmojiEvents, Tints.Gold.bg, Tints.Gold.fg) }, slot.title, slot.subtitle, { Chevron() }, modifier)
        is HeroYogaSession -> {
            val now = rememberServerNow(ticking = slot.state == HeroSessionState.STARTING_SOON)
            val s = yogaHeroState(slot, now)
            val startsAt = parseIsoInstant(slot.startsAt)
            val whenText = when {
                s.live -> "Live now"
                s.startingSoon -> "Starts in ${s.minutes} min"
                startsAt != null -> formatDayAndTime(startsAt, now)
                else -> ""
            }
            val line = listOf(whenText, slot.instructorName).filter { it.isNotBlank() }.joinToString(" · ")
            SecondaryRow(
                14, onClick, { IconTile(Icons.Filled.SelfImprovement, Tints.Plum.bg, Tints.Plum.fg) }, slot.title, line,
                { if (s.live) TagPill("Live", TagTone.LIVE) else Chevron() }, modifier,
            )
        }
        is HeroYogaRenew -> SecondaryRow(14, onClick, { IconTile(Icons.Filled.SelfImprovement, Tints.Plum.bg, Tints.Plum.fg) }, slot.title, slot.subtitle, { TagPill("Renew", TagTone.PLUM) }, modifier)
    }
}

/** "24 days to go · Bib #DEL-8892A": the bib half drops out until one is issued. */
internal fun raceLine(slot: HeroMyRace): String {
    val whenText = when {
        slot.isRaceDay -> "Race day"
        slot.daysRemaining == 1 -> "1 day to go"
        else -> "${slot.daysRemaining} days to go"
    }
    return slot.bibNumber?.let { "$whenText · Bib #$it" } ?: whenText
}

@Composable
private fun SecondaryRow(
    paddingDp: Int,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    trailing: @Composable () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(ThShapes.Lg)
            .background(PaperWhite)
            .border(1.dp, BorderRule, ThShapes.Lg)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(title, subtitle).joinToString(". ") }
            .padding(paddingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            leading()
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, color = TextMuted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

@Composable
private fun IconTile(icon: ImageVector, bg: Color, fg: Color) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun Thumb(url: String?, icon: ImageVector) {
    Box(Modifier.size(52.dp).clip(ThShapes.Md), contentAlignment = Alignment.Center) {
        FeedImage(url, gradient(PlumDeep, Carbon900), gradient(Color.Transparent, Color.Black.copy(alpha = 0.5f), GradientDir.VERTICAL), Modifier.matchParentSize())
        if (url.isNullOrBlank()) Icon(icon, contentDescription = null, tint = White85, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun Chevron() {
    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = CoralBrand, modifier = Modifier.size(24.dp))
}
