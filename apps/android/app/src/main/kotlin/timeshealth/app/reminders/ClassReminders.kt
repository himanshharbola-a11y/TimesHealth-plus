package timeshealth.app.reminders

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timeshealth.app.core.integrations.push.PushMessage
import timeshealth.app.push.NotificationPresenter

/**
 * "Remind me" for live classes: a local notification shortly before the
 * class starts, which opens the class (route /live/{id}). Works offline and
 * across restarts (WorkManager), independent of server push, which is still
 * sent for "class is live" and race-day messages (and, later, campaigns from
 * the GrowthRx panel).
 *
 * The set of reminded class ids is kept on the device so screens can show
 * "Reminder set" and the user can turn it off again.
 */
@Singleton
class ClassReminders @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _reminded = MutableStateFlow(prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().toSet())

    /** Ids of classes with a reminder set. */
    val reminded: StateFlow<Set<String>> = _reminded.asStateFlow()

    /**
     * Reminds [LEAD_MINUTES] before [startsAtMs] (or right away when that has
     * passed but the class hasn't started). [nowMs] is the server clock.
     */
    fun schedule(liveClassId: String, title: String, startsAtMs: Long, nowMs: Long) {
        val delayMs = (startsAtMs - LEAD_MINUTES * 60_000L - nowMs).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ClassReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_ID to liveClassId, KEY_TITLE to title, KEY_STARTS to startsAtMs))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(liveClassId), ExistingWorkPolicy.REPLACE, request)
        update { it + liveClassId }
    }

    fun cancel(liveClassId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(liveClassId))
        update { it - liveClassId }
    }

    /** Called by the worker once the reminder fired. */
    internal fun fired(liveClassId: String) = update { it - liveClassId }

    private fun update(change: (Set<String>) -> Set<String>) {
        synchronized(this) {
            val next = change(_reminded.value)
            prefs.edit().putStringSet(KEY_IDS, next).apply()
            _reminded.value = next
        }
    }

    companion object {
        /** How long before the start the reminder shows (the wait room opens an hour before). */
        const val LEAD_MINUTES = 10L
        private const val PREFS = "th_class_reminders"
        private const val KEY_IDS = "ids"
        internal const val KEY_ID = "id"
        internal const val KEY_TITLE = "title"
        internal const val KEY_STARTS = "startsAt"
        private const val TAG = "class-reminder"

        fun workName(liveClassId: String) = "class-reminder-$liveClassId"
    }
}

/** Shows the reminder notification; a tap opens the live class. */
@HiltWorker
class ClassReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val presenter: NotificationPresenter,
    private val reminders: ClassReminders,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(ClassReminders.KEY_ID) ?: return Result.success()
        val title = inputData.getString(ClassReminders.KEY_TITLE) ?: "Your live class"
        val startsAt = inputData.getLong(ClassReminders.KEY_STARTS, 0L)
        val minutes = ((startsAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0L)
        presenter.show(
            PushMessage(
                title = title,
                body = if (minutes > 0) "Starts in $minutes min. The wait room is open: tap to join." else "Starting now: tap to join.",
                data = mapOf("kind" to "SESSION_REMINDER", "route" to "/live/$id"),
            ),
        )
        reminders.fired(id)
        return Result.success()
    }
}
