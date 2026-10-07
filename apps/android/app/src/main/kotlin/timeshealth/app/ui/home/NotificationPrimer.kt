package timeshealth.app.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlumBrand
import timeshealth.app.ui.theme.PlumLine
import timeshealth.app.ui.theme.PlumTint
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThLayout
import timeshealth.app.ui.theme.ThShapes

private const val PREFS = "notification_primer"
private const val KEY_DISMISSED = "dismissed"

/**
 * "Never miss a class": asks for notification permission (Android 13+) in
 * context, before the system prompt, so class reminders and race-day updates
 * can arrive. Shown once; "Not now" is remembered on this phone.
 */
@Composable
fun NotificationPrimer(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var hidden by remember {
        mutableStateOf(
            prefs.getBoolean(KEY_DISMISSED, false) ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        prefs.edit().putBoolean(KEY_DISMISSED, true).apply()
        hidden = true
    }
    if (hidden) return
    Column(
        modifier.padding(horizontal = ThLayout.Gutter, vertical = 8.dp).fillMaxWidth().clip(ThShapes.Lg).background(PlumTint)
            .border(1.dp, PlumLine, ThShapes.Lg).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(PaperWhite), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.NotificationsActive, null, tint = PlumBrand, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Never miss a class", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Reminders before your batch, and race-day updates.", color = TextSecondary, fontSize = 12.sp)
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).height(40.dp).clip(ThShapes.Md).background(PlumBrand)
                    .clickable(role = Role.Button) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                contentAlignment = Alignment.Center,
            ) { Text("Turn on notifications", color = PaperWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            Box(
                Modifier.height(40.dp).clip(ThShapes.Md).clickable(role = Role.Button) {
                    prefs.edit().putBoolean(KEY_DISMISSED, true).apply()
                    hidden = true
                }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Not now", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}
