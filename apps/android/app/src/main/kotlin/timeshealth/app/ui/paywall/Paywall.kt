package timeshealth.app.ui.paywall

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timeshealth.app.core.domain.IST
import timeshealth.app.core.domain.parseIsoInstant
import timeshealth.app.core.integrations.subscription.PurchaseRequest
import timeshealth.app.core.model.ProductType
import timeshealth.app.core.model.YogaEntitlement
import timeshealth.app.core.network.ServerClock
import timeshealth.app.ui.checkout.CheckoutItem
import timeshealth.app.ui.checkout.CheckoutSheet
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.session.AccountGateway
import timeshealth.app.ui.state.Loadable
import timeshealth.app.ui.state.UiState
import timeshealth.app.ui.theme.BorderRule
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.CoralTint
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlumDeep
import timeshealth.app.ui.theme.SageBrand
import timeshealth.app.ui.theme.SageTint
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThLayout
import timeshealth.app.ui.theme.ThShapes

/**
 * A yoga plan as the paywall shows it. The price is a DISPLAY estimate: the
 * server prices the order (and the TIL Subscription SDK will, once plugged in
 * behind SubscriptionProvider). Keep in step with the server's plan table.
 */
@Immutable
data class YogaPlan(val id: String, val label: String, val paise: Long, val months: Int, val sub: String, val saving: Boolean)

object YogaPlans {
    private const val ANNUAL_PAISE = 499_900L
    private const val MONTHLY_PAISE = 99_900L

    /** "SAVE 58%": the annual plan against twelve monthly ones. */
    val annualSavingPct: Int = ((1 - ANNUAL_PAISE.toDouble() / (MONTHLY_PAISE * 12)) * 100).toInt()

    val all: List<YogaPlan> = listOf(
        YogaPlan("yoga_annual", "Annual Membership", ANNUAL_PAISE, 12, "₹${ANNUAL_PAISE / 100 / 12}/month · One payment for 12 months", saving = true),
        YogaPlan("yoga_monthly", "Monthly Plan", MONTHLY_PAISE, 1, "Try it for a month", saving = false),
    )

    fun byId(id: String?): YogaPlan = all.firstOrNull { it.id == id } ?: all.first()
}

private val BENEFITS = listOf(
    "8 live batches daily (join any slot, morning or evening)",
    "Full library of past session recordings",
    "Single-source attendance tracker & streaks",
    "Pranayama, mobility & masterclasses with certified masters",
)

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("en-IN"))

internal fun formatDate(day: LocalDate): String = DATE.format(day)

/**
 * The expiry a purchase would give, as the server grants it: an unexpired
 * membership extends from its CURRENT expiry, a lapsed one starts today (IST).
 * Display only; the server computes the real date.
 */
internal fun expiryAfter(currentExpiryIso: String?, months: Int, nowMs: Long): LocalDate {
    val now = Instant.ofEpochMilli(nowMs)
    val current = currentExpiryIso?.let(::parseIsoInstant)
    val base = if (current != null && current.isAfter(now)) current else now
    return base.atZone(IST).toLocalDate().plusMonths(months.toLong())
}

/** The membership the paywall is talking to: none, active (extend) or lapsed (renew). */
@HiltViewModel
class PaywallViewModel @Inject constructor(account: AccountGateway, private val clock: ServerClock) : ViewModel() {
    private val session = Loadable(viewModelScope, { r -> account.session(r) }, account.sessionChanges)

    /** Null while unknown or never a member. */
    val yoga: StateFlow<YogaEntitlement?> = session.state
        .map { (it as? UiState.Ready)?.data?.entitlements?.yoga }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun nowMs(): Long = clock.now()
}

/**
 * The yoga paywall (design PaywallSheetKt): benefits, the two plans, and the
 * confirm-and-pay sheet. It is the content of a bottom sheet over whatever
 * opened it. Copy is true to this build: one payment, nothing renews.
 */
@Composable
fun PaywallSheetContent(
    productId: String?,
    viewModel: PaywallViewModel,
    onClose: () -> Unit,
    onPurchased: () -> Unit,
) {
    val yoga by viewModel.yoga.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf(YogaPlans.byId(productId).id) }
    // What the open checkout is for, frozen when it opens: the membership refreshes the moment
    // the purchase lands, and a changed item would reset the sheet off its success screen.
    var checkoutPlan by rememberSaveable { mutableStateOf<String?>(null) }
    var checkoutSubtitle by rememberSaveable { mutableStateOf("") }
    val selected = YogaPlans.byId(selectedId)
    val member = yoga?.active == true
    val lapsed = yoga != null && !member
    fun newExpiry(months: Int) = formatDate(expiryAfter(yoga?.expiresAt, months, viewModel.nowMs()))
    val period = if (selected.months == 1) "1 month" else "${selected.months} months"

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = ThLayout.Gutter).padding(bottom = 16.dp).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TagPill("MEMBER ACCESS", TagTone.CORAL)
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = TextMuted) }
        }
        Text(
            when {
                member -> "Extend your membership"
                lapsed -> "Renew TimesHealth+"
                else -> "Join TimesHealth+"
            },
            color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            "Full access to 8 daily live batches, certified teachers from The Yoga Institute, and single-source attendance.",
            color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp),
        )
        yoga?.let { y ->
            val ends = parseIsoInstant(y.expiresAt)?.atZone(IST)?.toLocalDate()?.let(::formatDate)
            if (ends != null) {
                TagPill(if (member) "✓ Active until $ends" else "Ended $ends", if (member) TagTone.SAGE else TagTone.NEUTRAL, Modifier.padding(top = 10.dp))
            }
        }
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BENEFITS.forEach { b ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(SageTint), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, tint = SageBrand, modifier = Modifier.size(12.dp))
                    }
                    Text(b, color = TextPrimary, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            YogaPlans.all.forEach { p ->
                val on = p.id == selected.id
                Row(
                    Modifier.fillMaxWidth().clip(ThShapes.Lg).background(if (on) CoralTint else PaperWhite)
                        .border(if (on) 2.dp else 1.dp, if (on) CoralBrand else BorderRule, ThShapes.Lg)
                        .selectable(selected = on, role = Role.RadioButton) { selectedId = p.id }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(p.label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            if (p.saving) TagPill("SAVE ${YogaPlans.annualSavingPct}%", TagTone.GOLD)
                        }
                        Text(p.sub, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        if (member) Text("New expiry: ${newExpiry(p.months)}", color = SageBrand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                    }
                    Text(rupees(p.paise), color = if (p.saving) PlumDeep else TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        Box(
            Modifier.padding(top = 18.dp).fillMaxWidth().height(52.dp).clip(ThShapes.Md).background(CoralBrand)
                .clickable(role = Role.Button) {
                    checkoutSubtitle = if (member) {
                        "Adds $period to your membership — new expiry ${newExpiry(selected.months)}."
                    } else {
                        "8 live daily batches, full recording library and attendance streaks."
                    }
                    checkoutPlan = selected.id
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (member) "Extend by $period" else "Subscribe to ${selected.label}",
                color = PaperWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            )
        }
        // True to this build: a single payment, no automatic renewal (no recurring rail yet).
        // Change it when renewals are real.
        Text(
            "One payment for $period of access. Nothing renews automatically — renew from your profile when it ends.",
            color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 10.dp),
        )
    }

    CheckoutSheet(
        item = checkoutPlan?.let(YogaPlans::byId)?.let { plan ->
            CheckoutItem(
                request = PurchaseRequest(productType = ProductType.YOGA_SUBSCRIPTION, productId = plan.id),
                title = "TimesHealth+ Yoga · ${plan.label}",
                subtitle = checkoutSubtitle,
                displayPaise = plan.paise,
            )
        },
        onClose = { checkoutPlan = null },
        onSuccess = onPurchased,
    )
}

/** 499900 → "₹4,999". */
internal fun rupees(paise: Long): String = "₹" + "%,d".format(Locale.forLanguageTag("en-IN"), paise / 100)
