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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import timeshealth.app.core.domain.formatDayAndTime
import timeshealth.app.core.domain.formatPaise
import timeshealth.app.core.domain.formatSessionDate
import timeshealth.app.core.domain.parseIsoInstant
import timeshealth.app.core.domain.secondsUntil
import timeshealth.app.core.model.Article
import timeshealth.app.core.model.LiveClassCard
import timeshealth.app.core.model.LiveClassState
import timeshealth.app.core.model.LiveWorkshop
import timeshealth.app.core.model.Reel
import timeshealth.app.core.model.UserQuote
import timeshealth.app.core.model.WorkshopCategory
import timeshealth.app.core.model.YogaSession
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.theme.BorderRule
import timeshealth.app.ui.theme.BorderSubtle
import timeshealth.app.ui.theme.Carbon700
import timeshealth.app.ui.theme.Carbon800
import timeshealth.app.ui.theme.Carbon900
import timeshealth.app.ui.theme.Carbon950
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.GoldAccent
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlumBrand
import timeshealth.app.ui.theme.PlumDeep
import timeshealth.app.ui.theme.PlumLine
import timeshealth.app.ui.theme.PlumTint
import timeshealth.app.ui.theme.SageBrand
import timeshealth.app.ui.theme.SageSecondary
import timeshealth.app.ui.theme.SurfaceSand
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThShapes

/*
 * The card templates of PRD §6.3, distinct by shape and behaviour, from the
 * design's CommonComponentsKt / HomeScreenKt via the RN port
 * (components/feed/Cards.tsx):
 *
 *   Video      landscape, horizontal scroll   → session (or paywall)
 *   LiveClass  landscape, with its live state → live class (or paywall)
 *   Reel       portrait, horizontal scroll    → in-app reel player
 *   Article    vertical, image over headline  → opens the article
 *   Quote      square                         → static, NO tap
 *   Workshop   wide card with seats and price → workshops
 *   EntryTile  wide, single, full-bleed       → one action
 *   PromoStrip wide, short, ad-like           → one action, server-controlled
 */

private val ChipBg = Color.Black.copy(alpha = 0.6f)
private val White80 = Color.White.copy(alpha = 0.8f)
private val Scrim25to75 = gradient(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.75f))

/** A placeholder gradient per id (`id.hashCode()`), so a rail without photos still reads as distinct cards. */
private val VideoFallbacks = listOf(
    gradient(PlumDeep, PlumBrand),
    gradient(SageBrand, SageSecondary),
    gradient(Carbon900, Carbon700),
    gradient(Color(0xFF8A4A12), GoldAccent),
)

private fun fallbackFor(id: String) = VideoFallbacks[Math.floorMod(id.hashCode(), VideoFallbacks.size)]

private fun Modifier.card(description: String, onClick: () -> Unit) = this
    .clickable(role = Role.Button, onClick = onClick)
    .semantics(mergeDescendants = true) { contentDescription = description }

// ── Video: VideoLandscapeCard ─────────────────────────────────────────────────

@Composable
fun VideoCard(session: YogaSession, locked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.width(200.dp).card(
            "${session.title}, ${session.durationMinutes} minutes${if (locked) ", members only" else ""}",
            onClick,
        ),
    ) {
        Box(Modifier.fillMaxWidth().height(115.dp).clip(ThShapes.Lg).background(SurfaceSand)) {
            FeedImage(session.imageUrl, fallbackFor(session.id), Scrim25to75, Modifier.matchParentSize())
            // Top-start badge: FREE wins; a paid session the user can't play says so.
            Box(Modifier.padding(8.dp)) {
                when {
                    session.isFree -> TagPill("Free", TagTone.CORAL)
                    locked -> LockChip()
                }
            }
            PlayDisc(Modifier.align(Alignment.Center))
            DurationChip("${session.durationMinutes} min", Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        Text(session.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text("${session.instructor.name} · ${session.level}", color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LockChip() {
    Row(
        Modifier.clip(ThShapes.Tag).background(ChipBg).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = PaperWhite, modifier = Modifier.size(10.dp))
        Text("MEMBERS", color = PaperWhite, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PlayDisc(modifier: Modifier = Modifier) {
    Box(modifier.size(36.dp).clip(CircleShape).background(Color(0xDDFFFFFF)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Carbon900, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun DurationChip(text: String, modifier: Modifier = Modifier) {
    Box(modifier) {
        Text(
            text, color = PaperWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(ChipBg).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ── Live class: a premiere scheduled in the admin dashboard ───────────────────

/** The pill a live class card shows at [nowMs] (its state ticks locally from the server clock). */
internal fun liveClassPill(card: LiveClassCard, nowMs: Long): Pair<String, TagTone> {
    val start = parseIsoInstant(card.startsAt)?.toEpochMilli()
    val end = parseIsoInstant(card.endsAt)?.toEpochMilli()
    return when {
        card.state == LiveClassState.CANCELLED -> "Cancelled" to TagTone.NEUTRAL
        end != null && nowMs >= end -> "Ended" to TagTone.NEUTRAL
        start != null && nowMs >= start -> "Live now" to TagTone.LIVE
        start != null && start - nowMs <= 60 * 60_000L -> {
            val mins = maxOf(1L, (secondsUntil(start, nowMs) + 59) / 60)
            "Starts in $mins min" to TagTone.CORAL
        }
        start != null -> formatDayAndTime(java.time.Instant.ofEpochMilli(start), nowMs) to TagTone.CORAL
        else -> "Live class" to TagTone.CORAL
    }
}

@Composable
fun LiveClassCardView(card: LiveClassCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val now = rememberServerNow(ticking = card.state == LiveClassState.STARTING_SOON || card.state == LiveClassState.LIVE)
    val (pill, tone) = liveClassPill(card, now)
    Column(modifier.width(220.dp).card("${card.title}. $pill${if (!card.canJoin) ", members only" else ""}", onClick)) {
        Box(Modifier.fillMaxWidth().height(124.dp).clip(ThShapes.Lg).background(SurfaceSand)) {
            FeedImage(card.imageUrl, fallbackFor(card.id), Scrim25to75, Modifier.matchParentSize())
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagPill(pill, tone)
                if (card.isFree) TagPill("Free", TagTone.CORAL) else if (!card.canJoin) LockChip()
            }
            PlayDisc(Modifier.align(Alignment.Center))
            DurationChip("${card.durationMinutes} min", Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        Text(card.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        card.instructorName?.let {
            Text("with $it", color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── Reel: ReelPortraitCard ────────────────────────────────────────────────────

/** 45 → "0:45", 125 → "2:05". */
internal fun formatReelDuration(seconds: Int): String =
    if (seconds <= 0) "" else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

@Composable
fun ReelCard(reel: Reel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(115.dp).card("Play reel by ${reel.instructorName}", onClick)) {
        Box(Modifier.fillMaxWidth().height(180.dp).clip(ThShapes.Lg).background(Carbon900)) {
            FeedImage(reel.thumbnailUrl, gradient(Carbon800, Carbon950, GradientDir.VERTICAL), Scrim25to75, Modifier.matchParentSize())
            Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                TagPill("Reel", TagTone.CORAL)
                Box(
                    Modifier.align(Alignment.CenterHorizontally).size(32.dp).clip(CircleShape).background(Color(0x44FFFFFF)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = PaperWhite, modifier = Modifier.size(18.dp)) }
                Text(formatReelDuration(reel.durationSeconds), color = White80, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(reel.instructorName, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(reel.instructorHandle, color = TextMuted, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── Article: ArticleWideCard ──────────────────────────────────────────────────

@Composable
fun ArticleCard(article: Article, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(250.dp)
            .clip(ThShapes.Lg)
            .background(PaperWhite)
            .border(1.dp, BorderSubtle, ThShapes.Lg)
            .card("${article.title}. ${article.source}, ${article.readTimeMinutes} minute read", onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(95.dp).background(SurfaceSand)) {
            FeedImage(
                article.imageUrl, gradient(Color(0xFF1F2A4D), Color(0xFF3A4A7A)),
                gradient(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.7f)), Modifier.matchParentSize(),
            )
            if (article.category.isNotBlank()) TagPill(article.category, TagTone.NEUTRAL, Modifier.padding(10.dp))
        }
        Column(Modifier.padding(12.dp)) {
            Text(article.source.uppercase(Locale.ROOT), color = CoralBrand, fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp, maxLines = 1)
            Text(
                article.title, color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 14.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
            Text("${article.readTimeMinutes} min read", color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ── Quote: QuoteSquareCard. Read-only: no link, no video, no tap (§6.3 rail 6) ─

@Composable
fun QuoteCard(quote: UserQuote, modifier: Modifier = Modifier) {
    Column(
        modifier
            .size(190.dp)
            .clip(ThShapes.Lg)
            .background(PlumTint)
            .border(1.dp, PlumLine, ThShapes.Lg)
            .semantics(mergeDescendants = true) {}
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "“${quote.quote}”", color = PlumDeep, fontFamily = ThFonts.Serif, fontSize = 13.5.sp, lineHeight = 18.sp,
            fontWeight = FontWeight.Medium, maxLines = 5, overflow = TextOverflow.Ellipsis,
        )
        Column {
            Text(quote.author, color = TextPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(quote.role, color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── Workshop ──────────────────────────────────────────────────────────────────

private const val LOW_SPOTS = 5

@Composable
fun WorkshopCard(workshop: LiveWorkshop, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val w = workshop
    val price = when {
        w.isRegistered -> "Registered"
        w.pricePaise == null -> "Free for Members"
        else -> formatPaise(w.pricePaise!!)
    }
    Column(
        modifier
            .width(280.dp)
            .clip(ThShapes.Lg)
            .background(PaperWhite)
            .border(1.dp, BorderRule, ThShapes.Lg)
            .card("${w.title}. $price", onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(130.dp)) {
            FeedImage(
                w.imageUrl, gradient(Color(0xFF1E284A), Color(0xFF0F1526), GradientDir.VERTICAL),
                gradient(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.82f), GradientDir.VERTICAL), Modifier.matchParentSize(),
            )
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TagPill(
                    when (w.category) { WorkshopCategory.YOGA -> "Yoga"; WorkshopCategory.MARATHON -> "Running"; WorkshopCategory.DIET -> "Nutrition"; else -> "Workshop" },
                    when (w.category) { WorkshopCategory.YOGA -> TagTone.PLUM; WorkshopCategory.MARATHON -> TagTone.CORAL; else -> TagTone.SAGE },
                )
                Text(
                    if (w.spotsRemaining > 0) "${w.spotsRemaining} spots left" else "Full / Waitlist",
                    color = PaperWhite, fontSize = 9.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(ThShapes.Tag).background(if (w.spotsRemaining <= LOW_SPOTS) CoralBrand else ChipBg).padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = GoldAccent, modifier = Modifier.size(13.dp))
                    Text(parseIsoInstant(w.startsAt)?.let { formatSessionDate(it) } ?: "", color = PaperWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Text("${w.durationMinutes} MINS", color = PaperWhite, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(w.title, color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InstructorAvatar(w.instructorName, w.instructorAvatarUrl, 26)
                Column(Modifier.weight(1f)) {
                    Text(w.instructorName, color = TextPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(w.instructorTitle, color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(price, color = if (w.isRegistered) SageBrand else CoralBrand, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

// ── Entry tile: RunTrackerEntryTile ───────────────────────────────────────────

@Composable
fun EntryTile(title: String, subtitle: String, imageUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(ThShapes.Xl)
            .background(Color(0xFF1E284A))
            .border(1.dp, BorderRule, ThShapes.Xl)
            .card("$title. $subtitle", onClick),
    ) {
        // Navy wash left → right: dense behind the text, lighter over the photo.
        FeedImage(
            imageUrl, gradient(Color(0xFF1E284A), Color(0xFF2C3B68)),
            gradient(Color(0xE611172E), Color(0xBF1E284A), GradientDir.HORIZONTAL), Modifier.matchParentSize(),
        )
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(CoralBrand), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.DirectionsRun, contentDescription = null, tint = PaperWhite, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = White80, fontSize = 11.5.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = White80, modifier = Modifier.size(24.dp))
        }
    }
}

// ── Promo strip: narrower and shorter than the hero, deliberately ad-like ─────

/** "#1B4931" → Color; null for anything that isn't a 6- or 8-digit hex colour. */
internal fun parseHexColor(hex: String?): Color? {
    val h = hex?.trim()?.removePrefix("#") ?: return null
    if (!h.matches(Regex("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}"))) return null
    val v = h.toLong(16)
    return if (h.length == 6) Color(0xFF000000 or v) else Color(((v and 0xFF) shl 24) or (v ushr 8))
}

@Composable
fun PromoStrip(
    title: String,
    subtitle: String?,
    ctaLabel: String,
    backgroundColor: String?,
    imageUrl: String?,
    gold: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Plum for everyone, gold for members holding BOTH products. A campaign colour (§6.2)
    // replaces the gradient's start so the brand end still ties it to the app.
    val end = if (gold) GoldAccent else PlumBrand
    val start = parseHexColor(backgroundColor) ?: if (gold) Color(0xFF7A4A12) else PlumDeep
    Box(
        modifier
            .fillMaxWidth()
            .clip(ThShapes.Option)
            .background(PlumDeep)
            .card("$title. $ctaLabel", onClick),
    ) {
        if (!imageUrl.isNullOrBlank()) {
            FeedImage(imageUrl, gradient(start, end), null, Modifier.matchParentSize())
        }
        Box(Modifier.matchParentSize().background(gradient(start.copy(alpha = if (imageUrl.isNullOrBlank()) 1f else 0.88f), end.copy(alpha = if (imageUrl.isNullOrBlank()) 1f else 0.88f))))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                // Explicit line heights: the inherited bodyLarge (22sp) leaves gaps between wrapped small lines.
                Text(title, color = PaperWhite, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, color = White80, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                ctaLabel, color = PaperWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.clip(ThShapes.Sm).background(Color(0x33FFFFFF)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
