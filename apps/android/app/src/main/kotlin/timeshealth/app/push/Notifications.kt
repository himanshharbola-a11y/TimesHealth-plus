package timeshealth.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import timeshealth.app.MainActivity
import timeshealth.app.R
import timeshealth.app.core.integrations.push.PushMessage
import timeshealth.app.ui.navigation.PendingDeepLink
import timeshealth.app.ui.navigation.appRouteToDestination

/**
 * The app's notification channels. The ids are part of the server contract:
 * apps/api/src/services/push.ts sends every reminder on [REMINDERS], and
 * Android shows a background push on that channel only if it exists.
 */
object NotificationChannels {
    const val REMINDERS = "reminders"

    /** Creates the channels (idempotent; call on every launch). */
    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(REMINDERS, "Class & race reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Live class start times, race-day information and results"
            },
        )
    }
}

/**
 * Shows a notification that opens [PushMessage.route] when tapped: the same
 * `route` extra MainActivity reads for pushes Android displays itself while
 * the app is in the background. Used for pushes that arrive in the
 * foreground and, later, for local class reminders.
 */
@Singleton
class NotificationPresenter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val ids = AtomicInteger(1000)

    /** Returns false when notifications are off (permission not granted). */
    fun show(message: PushMessage, channel: String = NotificationChannels.REMINDERS): Boolean {
        if (message.title.isNullOrBlank() && message.body.isNullOrBlank()) return false
        if (!canNotify()) return false

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // Only a route the app actually has; anything else just opens the app.
            message.route?.takeIf { appRouteToDestination(it) != null }
                ?.let { putExtra(PendingDeepLink.EXTRA_ROUTE, it) }
        }
        val id = ids.incrementAndGet()
        val tap = PendingIntent.getActivity(
            context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.th_coral_brand))
            .setContentTitle(message.title)
            .setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        @Suppress("MissingPermission") // checked by canNotify()
        NotificationManagerCompat.from(context).notify(id, notification)
        return true
    }

    fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
