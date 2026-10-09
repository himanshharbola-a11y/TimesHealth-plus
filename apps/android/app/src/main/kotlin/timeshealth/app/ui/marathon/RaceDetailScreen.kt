package timeshealth.app.ui.marathon

import timeshealth.app.ui.theme.softShadow
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter
import java.util.Locale
import timeshealth.app.core.domain.IST
import timeshealth.app.core.domain.formatPaise
import timeshealth.app.core.domain.parseIsoInstant
import timeshealth.app.core.integrations.subscription.PurchaseRequest
import timeshealth.app.core.model.MarathonEvent
import timeshealth.app.core.model.ProductType
import timeshealth.app.core.model.RaceDetailResponse
import timeshealth.app.core.domain.formatPhone
import timeshealth.app.core.model.RaceLifecycleStatus
import timeshealth.app.core.model.RaceTier
import timeshealth.app.ui.checkout.CheckoutItem
import timeshealth.app.ui.checkout.CheckoutSheet
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.components.UiStateContent
import timeshealth.app.ui.feed.gradient
import timeshealth.app.ui.navigation.Route
import timeshealth.app.ui.theme.BorderRule
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.GoldAccent
import timeshealth.app.ui.theme.GoldTint
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlumBrand
import timeshealth.app.ui.theme.SurfaceSand
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThLayout
import timeshealth.app.ui.theme.ThShapes

private val LongDate = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.forLanguageTag("en-IN")).withZone(IST)

internal fun longDate(iso: String): String = parseIsoInstant(iso)?.let(LongDate::format) ?: ""

/** "Reserved parking, Kit delivery & Separate start wave". */
internal fun andList(items: List<String>): String =
    if (items.size <= 1) items.joinToString("") else items.dropLast(1).joinToString(", ") + " & " + items.last()

private val NavyBrush = gradient(Color(0xFF1E284A), Color(0xFF11172E))
private val GoldBrush = gradient(Color(0xFF7A4A12), GoldAccent)

@Composable
fun RaceDetailRoute(viewModel: RaceDetailViewModel, onBack: () -> Unit, openRoute: (Route) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val claiming by viewModel.claiming.collectAsStateWithLifecycle()
    var checkout by remember { mutableStateOf<CheckoutItem?>(null) }
    var confirmClaim by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val participantSaving by viewModel.participantSaving.collectAsStateWithLifecycle()
    val participantError by viewModel.participantError.collectAsStateWithLifecycle()
    val ready = (state as? timeshealth.app.ui.state.UiState.Ready)?.data
    // Only an entry that isn't finished can be edited.
    val editable = ready?.event?.registration?.let { it.status != RaceLifecycleStatus.COMPLETED } == true
    // From the profile's "Race participant details": open the editor once the page knows the entry.
    var openedFromProfile by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(editable) {
        if (viewModel.editRequested && editable && !openedFromProfile) {
            openedFromProfile = true
            editing = true
        }
    }

    Column(Modifier.fillMaxSize().background(CanvasBg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary) }
            Text("Race Details", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        UiStateContent(state, viewModel::retry, Modifier.fillMaxSize()) { data ->
            RaceDetailContent(
                data = data,
                initialDistance = viewModel.initialDistance,
                onCheckout = { checkout = it },
                onClaim = { confirmClaim = true },
                claiming = claiming,
                openRoute = openRoute,
                onEditParticipant = if (editable) {
                    {
                        viewModel.clearParticipantError()
                        editing = true
                    }
                } else {
                    null
                },
            )
        }
    }

    CheckoutSheet(item = checkout, onClose = { checkout = null }, onSuccess = viewModel::refresh)

    if (editing && ready != null) {
        ParticipantEditor(
            initial = ready.participant,
            saving = participantSaving,
            error = participantError,
            onSave = { size, name, phone -> viewModel.saveParticipant(size, name, phone, ready.participant) { editing = false } },
            onEdited = viewModel::clearParticipantError,
            onClose = { editing = false },
        )
    }

    val event = (state as? timeshealth.app.ui.state.UiState.Ready)?.data?.event
    if (confirmClaim && event != null) {
        AlertDialog(
            onDismissRequest = { confirmClaim = false },
            title = { Text("Claim your free Premium upgrade?") },
            text = { Text("Your ${event.name} entry moves to Premium VIP at no cost. The Refer & Win upgrade can be claimed once, on one race.") },
            confirmButton = { TextButton(onClick = { confirmClaim = false; viewModel.claimUpgrade(event.name) }) { Text("Claim upgrade") } },
            dismissButton = { TextButton(onClick = { confirmClaim = false }) { Text("Not now") } },
        )
    }
    message?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = viewModel::consumeMessage,
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = viewModel::consumeMessage) { Text("OK") } },
        )
    }
}

@Composable
private fun RaceDetailContent(
    data: RaceDetailResponse,
    initialDistance: String?,
    onCheckout: (CheckoutItem) -> Unit,
    onClaim: () -> Unit,
    claiming: Boolean,
    openRoute: (Route) -> Unit,
    onEditParticipant: (() -> Unit)?,
) {
    val event = data.event
    val reg = event.registration
    val completed = reg?.status == RaceLifecycleStatus.COMPLETED
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ThLayout.Gutter, vertical = 12.dp).navigationBarsPadding()) {
        if (reg != null) {
            Column(Modifier.fillMaxWidth().clip(ThShapes.Xl).background(NavyBrush).padding(18.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagPill("REG: ${reg.registrationRef}", TagTone.CORAL)
                    TagPill(if (reg.tier == RaceTier.PREMIUM) "Premium" else "Classic", TagTone.GOLD)
                }
                Text(event.name, color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                Text(
                    "${reg.category} · ${longDate(event.startsAt)}${reg.bibNumber?.let { " · Bib $it" } ?: ""}",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                )
                when {
                    completed -> SolidButton("Check Result & Certificate", CoralBrand, { openRoute(Route.RaceResults(event.id)) }, Modifier.fillMaxWidth())
                    data.bib != null -> SolidButton("Open Digital Bib & QR Pass", CoralBrand, { openRoute(Route.Bib(event.id)) }, Modifier.fillMaxWidth())
                    // No dead button: until a bib exists, say when it will.
                    else -> Text("Your digital bib and QR pass appear here as soon as your bib number is allocated.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                }
            }
        } else {
            RegisterCard(event, initialDistance, onCheckout)
        }

        // §8.3 edge case: a rescheduled edition says so.
        event.rescheduledFrom?.let { from ->
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).clip(ThShapes.Md).background(GoldTint).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.EventRepeat, contentDescription = null, tint = GoldAccent, modifier = Modifier.size(18.dp))
                Text(
                    "This edition was rescheduled${longDate(from).takeIf { it.isNotEmpty() }?.let { " from $it" } ?: ""}. Your registration transfers automatically with no penalty.",
                    color = TextPrimary, fontSize = 12.5.sp, lineHeight = 18.sp,
                )
            }
        }

        // Only when the server says an upgrade is actually available (§8.3: sold out = absent).
        val offer = data.upgradeOffer
        if (offer?.available == true && reg != null) {
            Column(
                Modifier.fillMaxWidth().padding(top = 12.dp).clip(ThShapes.Xl).background(GoldBrush)
                    .clickable(role = Role.Button) {
                        if (offer.freeClaim) {
                            onClaim()
                        } else {
                            onCheckout(
                                CheckoutItem(
                                    PurchaseRequest(ProductType.PREMIUM_UPGRADE, event.id, eventId = event.id),
                                    "Premium VIP Upgrade", "${event.name} · you pay only the net difference", offer.netDifferencePaise,
                                ),
                            )
                        }
                    }
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        TagPill("Premium upgrade", TagTone.GOLD)
                        Text(
                            if (offer.freeClaim) "Your free Premium upgrade" else "Make it a VIP race morning",
                            color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            if (offer.freeClaim) "Earned through Refer & Win — 5 friends registered with your code. ${andList(offer.benefits)}, at no cost."
                            else "Pay net difference ${formatPaise(offer.netDifferencePaise)}: ${andList(offer.benefits)}.",
                            color = Color.White.copy(alpha = 0.9f), fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (!offer.freeClaim) Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = PaperWhite)
                }
                if (offer.freeClaim) {
                    Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(44.dp).clip(ThShapes.Md).background(PaperWhite), contentAlignment = Alignment.Center) {
                        Text(if (claiming) "Claiming…" else "Claim free Premium upgrade", color = Color(0xFF7A4A12), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Logistics(data, onEditParticipant)

        // §8.3: Refer & Win, while the race is ahead.
        data.referral?.let { ref ->
            if (reg != null && !completed) ReferAndWinCard(ref, event.name, Modifier.padding(top = 18.dp), alreadyPremium = reg.tier == RaceTier.PREMIUM)
        }

        if (data.faqs.isNotEmpty()) {
            BlockTitle("Race FAQs")
            data.faqs.forEach { Faq(it.question, it.answer) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Not registered yet: distance chips, the Classic / Premium VIP tier, the price and Register. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegisterCard(event: MarathonEvent, initialDistance: String?, onCheckout: (CheckoutItem) -> Unit) {
    val options = event.distanceOptions
    var code by rememberSaveable(event.id) {
        mutableStateOf(
            (options.firstOrNull { it.code == initialDistance }
                ?: options.firstOrNull { it.code == "21K" && it.registrationOpen }
                ?: options.firstOrNull { it.registrationOpen }
                ?: options.firstOrNull())?.code,
        )
    }
    var tier by rememberSaveable(event.id) { mutableStateOf(RaceTier.CLASSIC) }
    val d = options.firstOrNull { it.code == code }
    val premiumGone = tier == RaceTier.PREMIUM && d?.premiumSoldOut == true
    val open = event.registrationOpen && d?.registrationOpen == true && !premiumGone
    val price = d?.pricePaise?.get(tier) ?: 0L
    val was = d?.wasPricePaise?.get(tier)

    Column(Modifier.fillMaxWidth().clip(ThShapes.Xl).background(NavyBrush).padding(18.dp)) {
        if (event.registrationOpen) TagPill("Registrations open · ${parseIsoInstant(event.startsAt)?.atZone(IST)?.year ?: ""}", TagTone.CORAL) else TagPill("Registrations closed", TagTone.NEUTRAL)
        Text(event.name, color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Text("${event.city} · ${longDate(event.startsAt)} · ${event.venue}", color = Color.White.copy(alpha = 0.85f), fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
    }
    val premium = tier == RaceTier.PREMIUM
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).softShadow(ThShapes.Xl).clip(ThShapes.Xl).background(PaperWhite).padding(18.dp)) {
        Eyebrow("CHOOSE YOUR DISTANCE")
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> DistanceChip(o.code, o.code == code) { code = o.code } }
        }
        Eyebrow("ENTRY TYPE", Modifier.padding(top = 14.dp))
        Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(ThShapes.Md).background(SurfaceSand).padding(4.dp)) {
            listOf(RaceTier.CLASSIC, RaceTier.PREMIUM).forEach { t ->
                val on = tier == t
                val vip = t == RaceTier.PREMIUM
                Row(
                    Modifier.weight(1f).clip(ThShapes.Sm)
                        .then(if (on && vip) Modifier.background(PremiumCard) else Modifier.background(if (on) PaperWhite else Color.Transparent))
                        .clickable(role = Role.RadioButton) { tier = t }.padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (vip) Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = if (on) PremiumGold else GoldAccent, modifier = Modifier.size(15.dp))
                    Text(
                        if (vip) "Premium VIP" else "Classic",
                        color = when { on && vip -> PremiumGold; on -> CoralBrand; else -> TextSecondary },
                        fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
        // Premium shows what it adds, on the dark VIP card.
        androidx.compose.animation.AnimatedVisibility(premium) {
            Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(ThShapes.Md).background(PremiumCard).border(1.dp, PremiumGold.copy(alpha = 0.5f), ThShapes.Md).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = PremiumGold, modifier = Modifier.size(18.dp))
                    Text("Premium VIP includes", color = PremiumGold, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                }
                PremiumPerks(Modifier.padding(top = 10.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(formatPaise(price), color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    if (was != null && was > price) Text(formatPaise(was), color = TextMuted, fontSize = 13.sp, textDecoration = TextDecoration.LineThrough)
                }
                Text(if (tier == RaceTier.PREMIUM) "Premium VIP entry · race-morning perks" else "Classic Entry with chip & t-shirt", color = TextMuted, fontSize = 11.sp)
            }
            if (open && d != null) {
                SolidButton(if (premium) "Go VIP" else "Register", if (premium) Color(0xFFB8862B) else CoralBrand, {
                    onCheckout(
                        CheckoutItem(
                            request = PurchaseRequest(
                                productType = ProductType.MARATHON_REGISTRATION,
                                productId = "${event.id}:${d.code}:${tier.name}",
                                eventId = event.id,
                                category = d.code,
                                tier = tier,
                            ),
                            title = "${event.name} · ${d.label}",
                            subtitle = "${if (tier == RaceTier.PREMIUM) "Premium VIP" else "Classic"} entry · ${event.city}",
                            displayPaise = price,
                        ),
                    )
                })
            } else {
                TagPill(if (!event.registrationOpen) "Entries closed" else if (premiumGone) "Premium sold out" else "Closed", TagTone.NEUTRAL)
            }
        }
    }
}

/** Event logistics: one outlined card of label / value rows. */
@Composable
private fun Logistics(data: RaceDetailResponse, onEdit: (() -> Unit)?) {
    val event = data.event
    val expo = event.expo
    val registered = event.registration != null
    BlockTitle("Event logistics")
    Column(Modifier.fillMaxWidth().clip(ThShapes.Lg).background(PaperWhite).border(1.dp, BorderRule, ThShapes.Lg).padding(horizontal = 14.dp, vertical = 8.dp)) {
        DetailRow("Flag-off Time", event.flagOffTime)
        DetailRow("Venue", event.venue)
        expo?.venue?.takeIf { it.isNotBlank() }?.let { DetailRow("Expo Venue", it) }
        expo?.pickupWindow?.takeIf { it.isNotBlank() }?.let { DetailRow("Expo Date", it) }
        expo?.instructions?.takeIf { it.isNotBlank() }?.let { DetailRow("Bib Pickup", it) }
        expo?.requiredDocuments?.takeIf { it.isNotEmpty() }?.let { DetailRow("Mandatory", it.joinToString(", ")) }
        if (registered) {
            DetailRow("T-shirt Size", data.participant?.tshirtSize ?: data.kit?.tshirtSize ?: "Not chosen yet")
            val contact = listOfNotNull(data.participant?.emergencyContactName, data.participant?.emergencyContactPhone?.let(::formatPhone)).joinToString(" · ")
            DetailRow("Emergency Contact", contact.ifEmpty { "Not added yet" })
        }
        data.kit?.let { DetailRow("Runner Kit", it.status.name.replace('_', ' ').lowercase().replaceFirstChar { c -> c.uppercase() }) }
        listOfNotNull(data.kit?.courierName, data.kit?.trackingRef?.let { "#$it" }).joinToString(" ").takeIf { it.isNotBlank() }?.let { DetailRow("Courier", it) }
        onEdit?.let {
            Box(
                Modifier.padding(top = 8.dp, bottom = 6.dp).fillMaxWidth().height(42.dp).clip(ThShapes.Md).border(1.dp, BorderRule, ThShapes.Md)
                    .clickable(role = Role.Button, onClick = it),
                contentAlignment = Alignment.Center,
            ) { Text("Edit Participant Details", color = PlumBrand, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextMuted, fontSize = 12.sp, modifier = Modifier.width(120.dp))
        Text(value, color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Faq(question: String, answer: String) {
    var open by rememberSaveable(question) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(ThShapes.Md).background(PaperWhite).border(1.dp, BorderRule, ThShapes.Md)
            .clickable(role = Role.Button) { open = !open }.padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(question, color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = TextMuted)
        }
        if (open) Text(answer, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun BlockTitle(text: String) {
    Text(text, color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
}

@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, modifier = modifier)
}
