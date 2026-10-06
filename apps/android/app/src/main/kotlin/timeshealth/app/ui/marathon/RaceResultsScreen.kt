package timeshealth.app.ui.marathon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import timeshealth.app.BuildConfig
import timeshealth.app.core.domain.isSafeExternalUrl
import timeshealth.app.core.model.RaceDetailResponse
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.components.UiStateContent
import timeshealth.app.ui.feed.gradient
import timeshealth.app.ui.theme.BorderRule
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlumBrand
import timeshealth.app.ui.theme.PlumDeep
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThLayout
import timeshealth.app.ui.theme.ThShapes

/**
 * Race results (PRD §8.4). A result the timing partner hasn't published yet is a
 * pending STATE, never an empty screen.
 */
@Composable
fun RaceResultsRoute(viewModel: RaceDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(CanvasBg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary) }
            Text("Your Result", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        UiStateContent(state, viewModel::retry, Modifier.fillMaxSize()) { data -> Results(data) }
    }
}

@Composable
private fun Results(data: RaceDetailResponse) {
    val uri = LocalUriHandler.current
    val result = data.result
    val event = data.event
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ThLayout.Gutter, vertical = 8.dp).navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().clip(ThShapes.Xl).background(gradient(PlumDeep, PlumBrand)).padding(18.dp)) {
            TagPill("Race completed", TagTone.GOLD)
            Text(event.name, color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Text("${event.registration?.category ?: result?.category ?: ""} · ${longDate(event.startsAt)}", color = Color.White.copy(alpha = 0.85f), fontSize = 12.5.sp)
            if (result?.published == true) {
                Text("OFFICIAL FINISH TIME", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, modifier = Modifier.padding(top = 16.dp))
                Text(result.chipTime ?: result.finishTime ?: "—", color = PaperWhite, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                result.medalStatus?.let { TagPill("Medal: $it", TagTone.GOLD, Modifier.padding(top = 6.dp)) }
            }
        }

        if (result == null || !result.published) {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp).clip(ThShapes.Lg).background(PaperWhite).border(1.dp, BorderRule, ThShapes.Lg).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.HourglassTop, contentDescription = null, tint = CoralBrand, modifier = Modifier.size(32.dp))
                Text("Results are being published", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                Text(
                    "The timing partner is verifying every chip time. Yours appears here — with your certificate — as soon as it’s official. We’ll notify you.",
                    color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                )
            }
            return@Column
        }

        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Avg pace", result.avgPace ?: "—", Modifier.weight(1f))
            Stat("Overall rank", result.overallRank?.let { "#$it" } ?: "—", Modifier.weight(1f))
            Stat("Age group", result.ageGroupRank?.let { "#$it" } ?: "—", Modifier.weight(1f))
        }
        if (result.splits.isNotEmpty()) {
            Text("Timing splits", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            Column(Modifier.fillMaxWidth().clip(ThShapes.Lg).background(PaperWhite).border(1.dp, BorderRule, ThShapes.Lg).padding(horizontal = 14.dp, vertical = 6.dp)) {
                result.splits.forEach { split ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(split.label, color = TextMuted, fontSize = 13.sp)
                        Text(split.time, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        result.certificateUrl?.takeIf { isSafeExternalUrl(it, BuildConfig.DEBUG) }?.let { url ->
            Spacer(Modifier.height(18.dp))
            SolidButton("Download Certificate", CoralBrand, { runCatching { uri.openUri(url) } }, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(ThShapes.Md).background(PaperWhite).border(1.dp, BorderRule, ThShapes.Md).padding(12.dp)) {
        Text(label.uppercase(), color = TextMuted, fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp)
        Text(value, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
    }
}
