package timeshealth.app.ui.yoga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import timeshealth.app.ui.feed.FeedImage
import timeshealth.app.ui.feed.GradientDir
import timeshealth.app.ui.feed.gradient
import timeshealth.app.ui.theme.Carbon950
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.GoldAccent
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.ThLayout
import timeshealth.app.ui.theme.ThShapes

private const val PASS_PHOTO = "https://images.unsplash.com/photo-1545205597-3d9d02c29597?auto=format&fit=crop&w=800&q=80"

/** 12345 → "12,345". */
internal fun groupThousands(n: Int): String = "%,d".format(n)

/** "TimesHealth+ Pass" pitch (design SalesMembershipBanner), for non-members only. */
@Composable
fun MembershipBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.padding(horizontal = ThLayout.Gutter).fillMaxWidth().clip(ThShapes.Lg)
            .border(1.dp, GoldAccent.copy(alpha = 0.4f), ThShapes.Lg),
    ) {
        FeedImage(
            PASS_PHOTO,
            gradient(Carbon950, Color(0xFF2A2033), GradientDir.HORIZONTAL),
            gradient(Color(0xEB121417), Color(0xD92A1F36), GradientDir.HORIZONTAL),
            Modifier.matchParentSize(),
        )
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "TIMESHEALTH+ PASS", color = Carbon950, fontSize = 8.5.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(GoldAccent).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Text("Unlimited Live Access", color = PaperWhite, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "Switch freely between all 8 live daily batches + 1-on-1 diet consults.",
                    color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                "Try Free", color = PaperWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(CoralBrand).clickable(role = Role.Button, onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
