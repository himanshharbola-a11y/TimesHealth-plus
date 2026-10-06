package timeshealth.app.ui.checkout

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import timeshealth.app.core.domain.formatPaise
import timeshealth.app.core.integrations.subscription.PurchaseHost
import timeshealth.app.core.model.ProductType
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.components.ThSpinner
import timeshealth.app.ui.theme.AmberWarn
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.CrimsonAlert
import timeshealth.app.ui.theme.GoldAccent
import timeshealth.app.ui.theme.GoldTint
import timeshealth.app.ui.theme.LiveEmerald
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThShapes

/** What the buyer now has, in words that fit what they bought. */
private fun successLine(item: CheckoutItem): String = when (item.request.productType) {
    ProductType.MARATHON_REGISTRATION -> "You’re registered for ${item.title}. Your bib and race pass appear on the race page once allocated."
    ProductType.PREMIUM_UPGRADE -> "Your entry is now Premium VIP."
    ProductType.WORKSHOP -> "Your seat for ${item.title} is confirmed. The join link appears here before it starts."
    else -> "${item.title} is now active on your account."
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The shared confirm-and-pay sheet for every purchase (the design's
 * PaywallSheetKt look: PaperWhite, 28dp top corners, serif title, 52dp coral
 * CTA). Shown while [item] is non-null; [onSuccess] runs after "Continue" on
 * a granted purchase.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutSheet(item: CheckoutItem?, onClose: () -> Unit, onSuccess: () -> Unit = {}, viewModel: CheckoutViewModel = hiltViewModel()) {
    if (item == null) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    var code by rememberSaveable(item) { mutableStateOf(item.request.referralCode.orEmpty()) }
    LaunchedEffect(item) { viewModel.reset() }
    val context = LocalContext.current
    val host = object : PurchaseHost {
        override val activity: Activity? get() = context.findActivity()
    }
    val close = { if (viewModel.canClose) onClose() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { viewModel.canClose })

    ModalBottomSheet(onDismissRequest = close, sheetState = sheetState, containerColor = PaperWhite) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding().navigationBarsPadding().padding(bottom = 12.dp)) {
            when (val s = state) {
                CheckoutState.Granted -> ResultBlock(
                    icon = Icons.Filled.Check, iconBg = LiveEmerald, title = "You’re all set", body = successLine(item),
                    cta = "Continue", onCta = { onClose(); onSuccess() },
                )
                CheckoutState.RefundFlagged -> ResultBlock(
                    icon = Icons.Filled.CurrencyRupee, iconBg = AmberWarn, title = "Your refund is on its way",
                    body = "Payment received, but ${item.title} sold out or closed while you were paying. We’ve flagged a full refund — it reaches you in 5–7 working days.",
                    cta = "Done", onCta = onClose,
                )
                else -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        TagPill("Confirm purchase", TagTone.CORAL)
                        if (state != CheckoutState.Paying) {
                            IconButton(onClick = close) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextMuted) }
                        }
                    }
                    Text(item.title, color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    Text(item.subtitle, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))

                    // Refer & Win (§8.3): a friend's code, credited to them once this is paid.
                    val codeError = (s as? CheckoutState.Refused)?.takeIf { it.onReferralField }?.message
                    if (item.request.productType == ProductType.MARATHON_REGISTRATION && s !is CheckoutState.Confirming) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { t ->
                                code = t.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(16)
                                if (codeError != null) viewModel.reset()
                            },
                            label = { Text("Referral code (optional)") },
                            placeholder = { Text("A friend’s code, e.g. TH4F2KQ") },
                            isError = codeError != null,
                            supportingText = { Text(codeError ?: "Your friend earns a lucky-draw entry when you register.") },
                            singleLine = true,
                            enabled = s != CheckoutState.Paying,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        )
                    }

                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Total", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(formatPaise(item.displayPaise), color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Text("Inclusive of applicable taxes. Final amount is confirmed at payment.", color = TextMuted, fontSize = 11.sp)

                    when (s) {
                        is CheckoutState.Failed -> Notice(s.message, CrimsonAlert, Color(0xFFFDECEC))
                        is CheckoutState.Refused -> if (!s.onReferralField) Notice(s.message, CrimsonAlert, Color(0xFFFDECEC))
                        is CheckoutState.Confirming -> Notice(
                            if (s.refreshFailed) "Couldn’t check just now. Try Refresh in a moment." else "Confirming your payment… This can take a minute. You won’t be charged again.",
                            GoldAccent, GoldTint, busy = true,
                        )
                        else -> Unit
                    }

                    Spacer(Modifier.height(16.dp))
                    when (s) {
                        is CheckoutState.Confirming -> Cta(if (s.refreshing) null else "Refresh", onClick = viewModel::refresh)
                        CheckoutState.Paying -> Cta(null) {}
                        is CheckoutState.Failed -> Cta("Try again") { viewModel.pay(host, item, code) }
                        else -> Cta("Pay ${formatPaise(item.displayPaise)}") { viewModel.pay(host, item, code) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultBlock(icon: androidx.compose.ui.graphics.vector.ImageVector, iconBg: Color, title: String, body: String, cta: String, onCta: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(60.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = PaperWhite, modifier = Modifier.size(30.dp))
        }
        Text(title, color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        Text(body, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(18.dp))
        Cta(cta, onCta)
    }
}

@Composable
private fun Notice(text: String, fg: Color, bg: Color, busy: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(ThShapes.Md).background(bg).border(1.dp, fg.copy(alpha = 0.25f), ThShapes.Md).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (busy) ThSpinner(color = fg, size = 16.dp) else Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(text, color = if (busy) TextPrimary else fg, fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The 52dp R14 coral CTA; a null label shows the spinner (busy). */
@Composable
private fun Cta(label: String?, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(ThShapes.Option)
            .background(CoralBrand)
            .clickable(enabled = label != null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label == null) ThSpinner(color = PaperWhite, size = 20.dp) else Text(label, color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}
