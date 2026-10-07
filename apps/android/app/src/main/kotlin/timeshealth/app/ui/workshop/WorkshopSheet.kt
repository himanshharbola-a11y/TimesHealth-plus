package timeshealth.app.ui.workshop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timeshealth.app.BuildConfig
import timeshealth.app.core.data.repository.WorkshopsRepository
import timeshealth.app.core.domain.SUPPORT_WHATSAPP_URL
import timeshealth.app.core.domain.formatDayAndTime
import timeshealth.app.core.domain.isSafeExternalUrl
import timeshealth.app.core.domain.parseIsoInstant
import timeshealth.app.core.integrations.subscription.PurchaseRequest
import timeshealth.app.core.model.LiveWorkshop
import timeshealth.app.core.model.ProductType
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.ui.checkout.CheckoutItem
import timeshealth.app.ui.checkout.CheckoutSheet
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.components.ThSpinner
import timeshealth.app.ui.feed.FeedImage
import timeshealth.app.ui.feed.GradientDir
import timeshealth.app.ui.feed.gradient
import timeshealth.app.ui.feed.rememberServerNow
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.CrimsonAlert
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlumBrand
import timeshealth.app.ui.theme.PlumDeep
import timeshealth.app.ui.theme.SageBrand
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThShapes

/** What the workshop sheet can do with a seat. */
interface WorkshopGateway {
    suspend fun setRegistered(workshopId: String, registered: Boolean)
}

class RepositoryWorkshopGateway @Inject constructor(private val workshops: WorkshopsRepository) : WorkshopGateway {
    override suspend fun setRegistered(workshopId: String, registered: Boolean) {
        workshops.setWorkshopRegistration(workshopId, registered)
    }
}

/** The seat as the sheet shows it, updated in place after booking / cancelling. */
data class SeatState(
    val registered: Boolean,
    val busy: Boolean = false,
    val error: String? = null,
    /** Bought through checkout in this sheet (the workshop object predates it). */
    val paid: Boolean = false,
)

/** How the main button behaves for a workshop. */
internal enum class SeatAction { JOIN, CANCEL_FREE, SUPPORT_TO_CANCEL, REGISTER_FREE, BUY, FULL, ENDED }

internal fun seatAction(w: LiveWorkshop, registered: Boolean, nowMs: Long, paid: Boolean = w.paidSeat): SeatAction {
    val start = parseIsoInstant(w.startsAt)?.toEpochMilli()
    val ended = start != null && nowMs > start + w.durationMinutes * 60_000L
    return when {
        ended -> SeatAction.ENDED
        registered && w.joinUrl != null -> SeatAction.JOIN
        // A paid seat is cancelled (and refunded) by support, together.
        registered && paid -> SeatAction.SUPPORT_TO_CANCEL
        registered -> SeatAction.CANCEL_FREE
        w.spotsRemaining <= 0 -> SeatAction.FULL
        w.pricePaise == null -> SeatAction.REGISTER_FREE
        else -> SeatAction.BUY
    }
}

/** Free seats (and seats included with the membership) are booked here; paid ones go through checkout. */
@HiltViewModel
class WorkshopViewModel @Inject constructor(private val gateway: WorkshopGateway) : ViewModel() {
    private val _seat = MutableStateFlow<SeatState?>(null)
    val seat: StateFlow<SeatState?> = _seat.asStateFlow()

    fun show(w: LiveWorkshop) {
        _seat.value = SeatState(registered = w.isRegistered, paid = w.paidSeat)
    }

    /** The state the user WANTS (idempotent on the server), so a double tap can't undo itself. */
    fun setRegistered(w: LiveWorkshop, registered: Boolean) {
        val s = _seat.value ?: return
        if (s.busy) return
        _seat.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            _seat.value = try {
                gateway.setRegistered(w.id, registered)
                SeatState(registered = registered)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiRequestException) {
                s.copy(busy = false, error = WORKSHOP_ERRORS[e.code] ?: if (e.isNetworkFailure) NETWORK else e.error.message.ifBlank { NETWORK })
            } catch (e: Exception) {
                s.copy(busy = false, error = NETWORK)
            }
        }
    }

    fun purchased() {
        _seat.value = SeatState(registered = true, paid = true)
    }

    companion object {
        const val NETWORK = "Couldn’t update your seat. Check your connection and try again."
        val WORKSHOP_ERRORS = mapOf(
            "WORKSHOP_FULL" to "This workshop is full.",
            "WORKSHOP_ENDED" to "This workshop has already ended.",
            "PAYMENT_REQUIRED" to "This is a paid workshop — book a seat to join.",
        )
    }
}

/** A live workshop's detail and seat (design WorkshopSection's sheet). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkshopSheet(workshop: LiveWorkshop, viewModel: WorkshopViewModel, onDismiss: () -> Unit) {
    val w = workshop
    LaunchedEffect(w.id) { viewModel.show(w) }
    val seat by viewModel.seat.collectAsStateWithLifecycle()
    val now = rememberServerNow(ticking = false)
    val uri = LocalUriHandler.current
    var checkout by remember { mutableStateOf(false) }
    val s = seat ?: SeatState(w.isRegistered)
    val action = seatAction(w, s.registered, now, paid = s.paid || w.paidSeat)
    val start = parseIsoInstant(w.startsAt)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = PaperWhite) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(160.dp).clip(ThShapes.Lg)) {
                FeedImage(w.imageUrl, gradient(PlumDeep, PlumBrand), gradient(Color.Transparent, Color.Black.copy(alpha = 0.7f), GradientDir.VERTICAL), Modifier.matchParentSize())
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagPill(w.category.name.replace('_', ' '), TagTone.GOLD)
                    if (s.registered) TagPill("Registered", TagTone.SAGE)
                }
            }
            Text(w.title, color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
            Text(
                listOfNotNull(start?.let { formatDayAndTime(it, now) }, "${w.durationMinutes} min", w.platform, w.level).joinToString(" · "),
                color = TextSecondary, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp),
            )
            Text("with ${w.instructorName}, ${w.instructorTitle}", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            Text(w.description, color = TextPrimary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
            Text(
                if (w.spotsRemaining > 0) "${w.spotsRemaining} of ${w.totalCapacity} seats left" else "Full",
                color = if (w.spotsRemaining in 1..5) CoralBrand else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp),
            )

            val (label, enabled) = when (action) {
                SeatAction.JOIN -> "Join workshop" to true
                SeatAction.CANCEL_FREE -> "Cancel my seat" to true
                SeatAction.SUPPORT_TO_CANCEL -> "Contact support to cancel" to true
                SeatAction.REGISTER_FREE -> "Register free" to true
                SeatAction.BUY -> "Book seat · ₹${"%,d".format((w.pricePaise ?: 0) / 100)}" to true
                SeatAction.FULL -> "Workshop full" to false
                SeatAction.ENDED -> "This workshop has ended" to false
            }
            Box(
                Modifier.padding(top = 16.dp).fillMaxWidth().height(50.dp).clip(ThShapes.Md)
                    .background(if (!enabled) TextMuted.copy(alpha = 0.3f) else if (action == SeatAction.CANCEL_FREE) PaperWhite else PlumBrand)
                    .clickable(enabled = enabled && !s.busy, role = Role.Button) {
                        when (action) {
                            SeatAction.JOIN -> w.joinUrl?.takeIf { isSafeExternalUrl(it, BuildConfig.DEBUG) }?.let { runCatching { uri.openUri(it) } }
                            SeatAction.CANCEL_FREE -> viewModel.setRegistered(w, false)
                            SeatAction.SUPPORT_TO_CANCEL -> runCatching { uri.openUri(SUPPORT_WHATSAPP_URL) }
                            SeatAction.REGISTER_FREE -> viewModel.setRegistered(w, true)
                            SeatAction.BUY -> checkout = true
                            else -> Unit
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (s.busy) ThSpinner(color = PlumBrand, size = 20.dp)
                else Text(label, color = if (action == SeatAction.CANCEL_FREE) CrimsonAlert else if (enabled) PaperWhite else TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            if (s.registered && w.joinUrl == null && action != SeatAction.ENDED) {
                Text("The join link appears here before it starts. We’ll remind you.", color = SageBrand, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            s.error?.let { Text(it, color = CrimsonAlert, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
    }

    CheckoutSheet(
        item = if (checkout) CheckoutItem(PurchaseRequest(ProductType.WORKSHOP, w.id), w.title, "Live workshop seat with ${w.instructorName}.", w.pricePaise ?: 0) else null,
        onClose = { checkout = false },
        onSuccess = viewModel::purchased,
    )
}
