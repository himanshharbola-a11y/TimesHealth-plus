package timeshealth.app.ui.components

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.PREVIEW_CANVAS_ARGB
import timeshealth.app.ui.theme.Spacing
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThLayout
import timeshealth.app.ui.theme.TimesHealthTheme

/**
 * A rail / section title: CommonComponentsKt.SectionHeader.
 *
 * Full width, padding 18×12; the title in the serif at 18sp SemiBold, and an
 * optional coral 12sp Bold action ("See all") at the end. The action shows only
 * when BOTH [actionText] and [onAction] are given, as in the design.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ThLayout.Gutter, vertical = Spacing.Xl),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Two lines: the design's own headings ("Sessions for neck & shoulder release")
        // don't fit one line beside "See all" on a normal phone.
        Text(
            text = title,
            modifier = Modifier.weight(1f, fill = false),
            color = TextPrimary,
            fontSize = 19.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.3).sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionText != null && onAction != null) {
            // A soft pill reads as a button, unlike a bare coral word.
            Row(
                modifier = Modifier
                    .padding(start = Spacing.Xl)
                    .clip(CircleShape)
                    .background(CoralBrand.copy(alpha = 0.10f))
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = actionText, color = CoralBrand, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = CoralBrand, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = PREVIEW_CANVAS_ARGB)
@Composable
private fun SectionHeaderPreview() {
    TimesHealthTheme {
        Column {
            SectionHeader(title = "Live today")
            SectionHeader(title = "Instructor reels", actionText = "See all", onAction = {})
        }
    }
}
