package timeshealth.app.ui.marathon

import timeshealth.app.ui.theme.softShadow
import timeshealth.app.ui.theme.Carbon950
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timeshealth.app.core.domain.parseIsoInstant
import timeshealth.app.core.model.RaceExpoInfo
import timeshealth.app.core.model.RaceTier
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.components.ThSpinner
import timeshealth.app.ui.theme.BorderRule
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.Carbon900
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.SurfaceSand
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThLayout
import timeshealth.app.ui.theme.ThShapes

/** What the pass shows, from the server or (no signal) from the copy saved on the phone. */
@Immutable
data class BibPass(
    val bibNumber: String,
    val participantName: String,
    val category: String,
    val tier: RaceTier,
    val eventName: String,
    val qrValue: String,
    /** True while the QR is the 7-day offline signature rather than a live short-lived token. */
    val offline: Boolean,
    val flagOffTime: String?,
    val expo: RaceExpoInfo?,
)

@Immutable
sealed interface BibUi {
    data object Loading : BibUi
    data class Ready(val pass: BibPass) : BibUi
    /** Registered, but no bib number allocated yet. */
    data object NotAllocated : BibUi
    data object Unreachable : BibUi
}

/**
 * The digital bib + QR (PRD §8.3, docs/04 T1). The QR is a SIGNED, short-lived
 * token, refreshed every ~45 s while the screen is open; a screenshot of
 * someone else's pass stops scanning within a minute. With no signal (a packed
 * stadium), the 7-day offline signature saved on the phone stands in, so the
 * pass always opens at the gate.
 */
@HiltViewModel
class BibViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val gateway: MarathonGateway,
) : ViewModel() {
    val eventId: String = checkNotNull(savedStateHandle.get<String>("eventId"))

    private val _ui = MutableStateFlow<BibUi>(BibUi.Loading)
    val ui: StateFlow<BibUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { run() }
    }

    private suspend fun run() {
        // The saved copy first: it opens instantly and offline.
        gateway.offlinePass(eventId)?.let { saved ->
            _ui.value = BibUi.Ready(
                BibPass(saved.bibNumber, saved.participantName, saved.category, saved.tier, saved.eventName, saved.offlinePayload, true, saved.flagOffTime, saved.expo),
            )
        }
        val detail = try {
            gateway.raceDetail(eventId, refresh = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_ui.value == BibUi.Loading) _ui.value = BibUi.Unreachable
            return
        }
        // Once the server answers, it decides: a withdrawn bib stays withdrawn.
        val bib = detail.bib ?: run {
            _ui.value = BibUi.NotAllocated
            return
        }
        var token = bib.qrToken
        var expiresAt = parseIsoInstant(bib.qrExpiresAt)?.toEpochMilli() ?: 0L
        while (true) {
            val live = expiresAt - gateway.nowMs() > 10_000
            _ui.value = BibUi.Ready(
                BibPass(
                    bib.bibNumber, bib.participantName, bib.category, bib.tier, bib.eventName,
                    qrValue = if (live) token else bib.offlinePayload,
                    offline = !live,
                    flagOffTime = detail.event.flagOffTime,
                    expo = detail.event.expo,
                ),
            )
            delay(REFRESH_MS)
            try {
                val fresh = gateway.bibToken(eventId)
                token = fresh.qrToken
                expiresAt = parseIsoInstant(fresh.qrExpiresAt)?.toEpochMilli() ?: 0L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No signal: the last token while it lives, then the offline signature.
            }
        }
    }

    companion object {
        const val REFRESH_MS = 45_000L
    }
}

@Composable
fun BibRoute(viewModel: BibViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(CanvasBg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary) }
            Text("Digital Bib & Pass", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        when (val s = ui) {
            BibUi.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ThSpinner(size = 28.dp) }
            BibUi.NotAllocated -> Centered("Your bib will appear here once it is allocated.")
            BibUi.Unreachable -> Centered("We couldn’t load your pass. Check your connection and try again.")
            is BibUi.Ready -> Pass(s.pass)
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Pass(pass: BibPass) {
    val premium = pass.tier == RaceTier.PREMIUM
    val ink = if (premium) PaperWhite else TextPrimary
    val sub = if (premium) Color.White.copy(alpha = 0.72f) else TextMuted
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ThLayout.Gutter, vertical = 8.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (premium) TagPill("Premium VIP pass · expo scan", TagTone.GOLD) else TagPill("Official race pass · expo scan", TagTone.CORAL)
        Column(
            Modifier.padding(top = 12.dp).fillMaxWidth()
                .softShadow(ThShapes.Hero, if (premium) 18.dp else 10.dp)
                .clip(ThShapes.Hero)
                .then(if (premium) Modifier.background(PremiumCard).border(1.dp, PremiumGold.copy(alpha = 0.55f), ThShapes.Hero) else Modifier.background(PaperWhite)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header strip: gold foil for Premium, carbon for Classic.
            Text(
                "${if (premium) "PREMIUM VIP · " else ""}${pass.eventName.uppercase()} · ${pass.category}",
                color = if (premium) Carbon950 else PaperWhite, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp,
                textAlign = TextAlign.Center, maxLines = 2,
                modifier = Modifier.fillMaxWidth().then(if (premium) Modifier.background(PremiumFoil) else Modifier.background(Carbon900))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            if (premium) {
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = PremiumGold, modifier = Modifier.size(18.dp))
                    Text("VIP RUNNER", color = PremiumGold, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
                }
            }
            Text(
                pass.bibNumber, color = if (premium) PremiumGold else TextPrimary, fontFamily = ThFonts.Serif, fontSize = 56.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, modifier = Modifier.padding(top = if (premium) 4.dp else 14.dp),
            )
            Text(pass.participantName.ifBlank { "Registered runner" }, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "${if (premium) "Premium VIP" else "Classic"} Runner${pass.flagOffTime?.let { " · $it" } ?: ""}",
                color = sub, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
            )
            // The QR always sits on white: scanners need the contrast.
            Box(Modifier.padding(16.dp).clip(ThShapes.Md).background(PaperWhite).border(if (premium) 2.dp else 1.dp, if (premium) PremiumGold else BorderRule, ThShapes.Md).padding(10.dp)) {
                QrCode(pass.qrValue, Modifier.size(180.dp))
            }
            if (premium) PremiumPerks(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp))
            if (pass.offline) {
                Text("Offline pass · valid at the gate without signal", color = if (premium) PremiumGold else CoralBrand, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                pass.expo?.instructions?.takeIf { it.isNotBlank() } ?: "Show this QR code at the expo bib counter to collect your race timing chip, bib, and t-shirt.",
                color = if (premium) Color.White.copy(alpha = 0.8f) else TextSecondary, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().background(if (premium) Color.White.copy(alpha = 0.06f) else SurfaceSand).padding(14.dp),
            )
        }
        pass.expo?.let { expo ->
            Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(ThShapes.Lg).background(PaperWhite).border(1.dp, BorderRule, ThShapes.Lg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (expo.venue.isNotBlank()) ExpoRow("Expo Venue", expo.venue)
                if (expo.pickupWindow.isNotBlank()) ExpoRow("Pickup Window", expo.pickupWindow)
                if (expo.requiredDocuments.isNotEmpty()) ExpoRow("Mandatory", expo.requiredDocuments.joinToString(", "), accent = true)
            }
        }
        Text("This pass is saved on your phone and works without signal.", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp, bottom = 20.dp))
    }
}

@Composable
private fun ExpoRow(label: String, value: String, accent: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Text(value, color = if (accent) CoralBrand else TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.padding(start = 12.dp))
    }
}

