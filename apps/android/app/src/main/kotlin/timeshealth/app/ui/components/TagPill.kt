package timeshealth.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import timeshealth.app.ui.theme.PREVIEW_CANVAS_ARGB
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.Spacing
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.ThShapes
import timeshealth.app.ui.theme.TimesHealthTheme

/**
 * The design's one status chip: CommonComponentsKt.TagPill.
 *
 * 6dp corners, padding 7×3, 4dp gap, the label upper-cased at 9sp ExtraBold
 * with +0.6 tracking. [TagTone.LIVE] is solid emerald with a 5dp white dot.
 * Used for hero status ("LIVE NOW", "MEMBERSHIP EXPIRED"), FREE / REEL /
 * category labels, REGISTER / EXPLORE actions, "SAVE 58%", "STEP 2 OF 4"…
 *
 * [tone] is required on purpose: the design defaults to NEUTRAL, the RN port
 * defaulted to CORAL, and a silent default would carry one of them over wrong.
 *
 * @param icon optional 10dp glyph before the label (the RN `icon` prop).
 */
@Composable
fun TagPill(
    text: String,
    tone: TagTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val tint = tone.tint
    Row(
        modifier = modifier
            .clip(ThShapes.Tag)
            .background(tint.bg)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tone == TagTone.LIVE) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(PaperWhite))
        }
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(10.dp), tint = tint.fg)
        }
        // Same call as the design (no explicit style): the rest of the style
        // comes from MaterialTheme's LocalTextStyle, as it does there.
        Text(
            text = text.uppercase(Locale.ROOT),
            color = tint.fg,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.6.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(showBackground = true, backgroundColor = PREVIEW_CANVAS_ARGB)
@Composable
private fun TagPillPreview() {
    TimesHealthTheme {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TagTone.entries.forEach { TagPill(text = it.name, tone = it) }
            TagPill(text = "Live now", tone = TagTone.LIVE)
            TagPill(text = "Save 58%", tone = TagTone.CORAL, icon = Icons.Filled.Bolt)
        }
    }
}
