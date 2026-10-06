package timeshealth.app.core.runtracker.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import timeshealth.app.core.runtracker.RunOwnerProvider
import timeshealth.app.core.runtracker.RunTracker
import timeshealth.app.core.runtracker.RunUploader

/**
 * Uploads one owner's finished runs once there is a network (§8.6: a run is
 * never lost to being offline; docs/03 "every network write goes through an
 * outbox that survives app kill"). The runs themselves are the outbox: they
 * stay in Room, unsynced, until the server has accepted each one.
 *
 * Enqueue with [RunSync.enqueue]. Needs the app's HiltWorkerFactory, which
 * TimesHealthApp already installs.
 */
@HiltWorker
class RunUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tracker: RunTracker,
    private val uploader: RunUploader,
    private val owners: RunOwnerProvider,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val owner = inputData.getString(RunSync.KEY_OWNER)?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        return when (val result = RunSyncer(tracker, uploader, owners).sync(owner)) {
            // Exponential backoff from RunSync.request; the runs are safe meanwhile.
            is SyncResult.Failed -> {
                Log.w(TAG, "Run upload failed after ${result.uploaded}; will retry", result.cause)
                Result.retry()
            }
            // Signed out or switched user: nothing to do until the owner is
            // back, and their next sign-in enqueues again.
            is SyncResult.OwnerChanged, is SyncResult.Done -> Result.success()
        }
    }

    private companion object {
        const val TAG = "RunUpload"
    }
}

/** Queues the upload of a signed-in user's finished runs. */
object RunSync {
    internal const val KEY_OWNER = "owner"
    private const val BACKOFF_SECONDS = 30L

    /**
     * Call after [RunTracker.finish] saves a run, and on sign-in / app start
     * for the signed-in user (runs left from offline sessions).
     *
     * Unique per owner with KEEP: a second call while one is queued or running
     * doesn't start a parallel upload of the same runs. A run finished during
     * a sync is still picked up: the worker re-reads the list before it ends.
     */
    fun enqueue(context: Context, owner: String) {
        require(owner.isNotBlank()) { "RunSync.enqueue needs the signed-in profile id" }
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueWorkName(owner), ExistingWorkPolicy.KEEP, request(owner))
    }

    /** For observing progress: `WorkManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName(owner))`. */
    fun uniqueWorkName(owner: String): String = "run-upload:$owner"

    internal fun request(owner: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<RunUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_OWNER to owner))
            .build()
}
