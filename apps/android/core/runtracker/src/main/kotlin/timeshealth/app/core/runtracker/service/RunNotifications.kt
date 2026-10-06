package timeshealth.app.core.runtracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import java.util.Locale
import timeshealth.app.core.domain.DistanceUnits
import timeshealth.app.core.runtracker.R
import timeshealth.app.core.runtracker.db.RunEntity

/**
 * What the ongoing notification shows. Distance is text; time is a system
 * chronometer counting from [chronometerBase], so the notification ticks every
 * second without this app waking up to redraw it (battery, §8.6).
 */
internal data class TrackingNotice(val distance: String, val chronometerBase: Long) {
    companion object {
        fun of(run: RunEntity, units: DistanceUnits) = TrackingNotice(
            distance = String.format(Locale.ROOT, "%.2f %s", units.dist(run.distanceM / 1000.0), units.short),
            // While running, moving time = now - startedAt - pausedMs.
            chronometerBase = run.startedAt + run.pausedMs,
        )
    }
}

/**
 * The foreground service's notification: Android's price for recording with
 * the screen off. Low importance (no sound, no heads-up: it is there for the
 * whole run), live distance and moving time, and a Pause action.
 *
 * The small icon and every string are resources prefixed `runtracker_`; the
 * app overrides them by defining resources with the same names.
 */
internal class RunNotifications(private val context: Context) {

    private val manager = context.getSystemService<NotificationManager>()

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.runtracker_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.runtracker_channel_description)
            setShowBadge(false)
        }
        manager?.createNotificationChannel(channel)
    }

    /** [notice] null: GPS is starting and nothing is known yet. */
    fun build(notice: TrackingNotice?): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.runtracker_ic_notification)
            .setContentTitle(context.getString(R.string.runtracker_notification_title))
            .setContentText(notice?.distance ?: context.getString(R.string.runtracker_notification_starting))
            .setShowWhen(notice != null)
            .setUsesChronometer(notice != null)
            .apply { if (notice != null) setWhen(notice.chronometerBase) }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setColor(BRAND_COLOR)
            // Android 12+ may hold a new FGS notification back for 10 s; the
            // runner should see at once that recording (and Pause) is live.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .addAction(
                R.drawable.runtracker_ic_pause,
                context.getString(R.string.runtracker_action_pause),
                PendingIntent.getService(
                    context,
                    REQUEST_PAUSE,
                    Intent(context, RunTrackerService::class.java).setAction(RunTrackerService.ACTION_PAUSE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    /** Silently dropped by the OS without POST_NOTIFICATIONS; recording carries on. */
    fun show(notice: TrackingNotice) {
        manager?.notify(NOTIFICATION_ID, build(notice))
    }

    /** Back into the app (its launcher activity, which shows the live run). */
    private fun openAppIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_ID = "run_tracking"
        const val NOTIFICATION_ID = 8_606 // §8.6
        private const val REQUEST_OPEN = 1
        private const val REQUEST_PAUSE = 2

        /** coralBrand, as the RN foreground-service notification used. */
        private const val BRAND_COLOR = 0xFFE8533A.toInt()
    }
}
