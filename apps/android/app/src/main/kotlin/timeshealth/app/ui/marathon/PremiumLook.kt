package timeshealth.app.ui.marathon

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import timeshealth.app.ui.theme.ThShapes

/*
 * The Premium VIP look: a dark pass with gold accents, so a Premium entry is
 * unmistakable at a glance (and at the expo counter). Classic keeps the clean
 * white pass. One place, used by the digital bib and the race page.
 */

/** Gold that reads on a dark card (brighter than the GoldAccent used on white). */
val PremiumGold = Color(0xFFE8B95A)

/** The dark card: carbon into deep plum. */
val PremiumCard: Brush = Brush.linearGradient(listOf(Color(0xFF121417), Color(0xFF2A1F36), Color(0xFF3B0E4A)))

/** The gold foil strip across the top of a Premium pass. */
val PremiumFoil: Brush = Brush.horizontalGradient(listOf(Color(0xFFB8862B), Color(0xFFF3D27A), Color(0xFFB8862B)))

/** What Premium VIP includes; the same list the server sends (marathon.ts PREMIUM_BENEFITS). */
val PREMIUM_PERKS = listOf("Reserved parking", "Kit delivery to your door", "Separate start wave", "On-site physio support")

/** The perks as small gold-outlined chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PremiumPerks(modifier: Modifier = Modifier, perks: List<String> = PREMIUM_PERKS) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        perks.forEach { perk ->
            Row(
                Modifier.clip(ThShapes.Pill).border(1.dp, PremiumGold.copy(alpha = 0.6f), ThShapes.Pill).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = PremiumGold, modifier = Modifier.size(12.dp))
                Text(perk, color = Color.White.copy(alpha = 0.92f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
