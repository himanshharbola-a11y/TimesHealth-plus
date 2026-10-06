package timeshealth.app.core.runtracker.sync

import kotlin.coroutines.cancellation.CancellationException
import timeshealth.app.core.runtracker.PermanentUploadException
import timeshealth.app.core.runtracker.RunOwnerProvider
import timeshealth.app.core.runtracker.RunTracker
import timeshealth.app.core.runtracker.RunUploader

internal sealed interface SyncResult {
    val uploaded: Int

    /** Nothing left to upload for the owner. */
    data class Done(override val uploaded: Int, val rejected: Int = 0) : SyncResult

    /** An upload failed; the rest stay on the phone for the next attempt. */
    data class Failed(override val uploaded: Int, val cause: Exception) : SyncResult

    // Runs the server refused for good are counted in [rejected] on Done.

    /** Someone else is signed in now; the owner's runs wait for them. */
    data class OwnerChanged(override val uploaded: Int) : SyncResult
}

/**
 * Uploads one owner's unsynced runs: one at a time, each marked synced as it
 * lands, stopping at the first failure (offline again: the rest go next time).
 *
 * RN lesson: firing the uploads together and marking them in one callback
 * kept only the LAST call's success, so earlier runs were re-uploaded forever
 * and their GPS points never pruned. Uploads are idempotent on the run id, so
 * a retry after a lost response is harmless.
 */
internal class RunSyncer(
    private val tracker: RunTracker,
    private val uploader: RunUploader,
    private val owners: RunOwnerProvider,
) {
    suspend fun sync(owner: String): SyncResult {
        var uploaded = 0
        var rejected = 0
        val attempted = HashSet<String>()
        // Re-read once the list is done: a run finished during the uploads
        // would otherwise wait for the next sync (KEEP ignores the enqueue).
        while (true) {
            val pending = tracker.unsyncedRuns(owner).filter { it.id !in attempted }
            if (pending.isEmpty()) return SyncResult.Done(uploaded, rejected)
            for (run in pending) {
                // RunUploader sends whoever is signed in NOW. On a shared phone
                // that may no longer be the owner, and their run must never be
                // filed under someone else's account. Checked before each upload.
                if (owners.currentOwner() != owner) return SyncResult.OwnerChanged(uploaded)
                attempted += run.id
                try {
                    uploader.upload(run)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PermanentUploadException) {
                    // Refused for good: stop retrying THIS run so it can't block
                    // the queue behind it, and carry on with the next one.
                    tracker.markSynced(run.id)
                    rejected += 1
                    continue
                } catch (e: Exception) {
                    return SyncResult.Failed(uploaded, e)
                }
                tracker.markSynced(run.id)
                uploaded += 1
            }
        }
    }
}
