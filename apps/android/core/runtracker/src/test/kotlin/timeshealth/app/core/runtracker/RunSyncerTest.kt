package timeshealth.app.core.runtracker

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timeshealth.app.core.runtracker.sync.RunSync
import timeshealth.app.core.runtracker.sync.RunSyncer
import timeshealth.app.core.runtracker.sync.SyncResult

@RunWith(RobolectricTestRunner::class)
class RunSyncerTest {

    private val h = Harness()

    @After fun tearDown() = h.close()

    /** Records each upload; [failOn] ids throw; [during] runs inside an upload. */
    private class FakeUploader : RunUploader {
        val uploaded = mutableListOf<String>()
        val failOn = mutableSetOf<String>()
        val rejectOn = mutableSetOf<String>()
        var during: (suspend (FinishedRun) -> Unit)? = null

        override suspend fun upload(run: FinishedRun) {
            if (run.id in failOn) throw IOException("offline")
            if (run.id in rejectOn) throw PermanentUploadException("400 INVALID_BODY")
            during?.invoke(run)
            uploaded += run.id
        }
    }

    private val uploader = FakeUploader()
    private val syncer = RunSyncer(h.tracker, uploader, h.owners)

    /** Records and finishes a 20 m run for [owner], starting at [at]. */
    private suspend fun recordRun(owner: String, at: Long): String {
        h.clock.now = at
        h.startAs(owner)
        h.runTo(0.0, 10.0, 20.0, fromMs = at + SEC)
        h.clock.now = at + MIN
        return h.tracker.finish()!!.id
    }

    @Test
    fun `a run the server refuses for good is skipped, not left blocking the queue`() = runTest {
        val bad = recordRun("alice", T0)
        val good = recordRun("alice", T0 + 10 * MIN)
        uploader.rejectOn += bad

        assertThat(syncer.sync("alice")).isEqualTo(SyncResult.Done(uploaded = 1, rejected = 1))
        assertThat(uploader.uploaded).containsExactly(good)
        // Neither is retried on the next sync.
        assertThat(h.tracker.unsyncedRuns("alice")).isEmpty()
    }

    @Test
    fun `uploads oldest first, one at a time, each marked synced as it lands`() = runTest {
        val a = recordRun("alice", T0)
        val b = recordRun("alice", T0 + 10 * MIN)
        // When b uploads, a must already be marked: a crash now must not re-send a.
        uploader.during = { run -> if (run.id == b) assertThat(h.dao.byId(a)!!.synced).isTrue() }

        assertThat(syncer.sync("alice")).isEqualTo(SyncResult.Done(2))
        assertThat(uploader.uploaded).containsExactly(a, b).inOrder()
        // Synced runs give up their raw points.
        assertThat(h.dao.pointCount(a)).isEqualTo(0)
        assertThat(h.dao.pointCount(b)).isEqualTo(0)
        assertThat(h.tracker.unsyncedRuns("alice")).isEmpty()
    }

    @Test
    fun `stops at the first failure and keeps the rest for next time`() = runTest {
        val a = recordRun("alice", T0)
        val b = recordRun("alice", T0 + 10 * MIN)
        val c = recordRun("alice", T0 + 20 * MIN)
        uploader.failOn += b

        val result = syncer.sync("alice")
        assertThat(result).isInstanceOf(SyncResult.Failed::class.java)
        assertThat(result.uploaded).isEqualTo(1)
        assertThat(uploader.uploaded).containsExactly(a)
        assertThat(h.tracker.unsyncedRuns("alice").map { it.id }).containsExactly(b, c).inOrder()
        // The unsent runs keep their routes for the retry.
        assertThat(h.dao.pointCount(b)).isEqualTo(3)
    }

    @Test
    fun `never uploads one person's runs while someone else is signed in`() = runTest {
        val a = recordRun("alice", T0)
        h.owners.owner = "bob"
        assertThat(syncer.sync("alice")).isEqualTo(SyncResult.OwnerChanged(0))
        assertThat(uploader.uploaded).isEmpty()
        assertThat(h.tracker.unsyncedRuns("alice").map { it.id }).containsExactly(a)
    }

    @Test
    fun `a sign-out mid-sync stops before the next upload`() = runTest {
        val a = recordRun("alice", T0)
        recordRun("alice", T0 + 10 * MIN)
        uploader.during = { h.owners.owner = null }

        assertThat(syncer.sync("alice")).isEqualTo(SyncResult.OwnerChanged(1))
        assertThat(uploader.uploaded).containsExactly(a)
    }

    @Test
    fun `only the owner's runs are uploaded`() = runTest {
        recordRun("bob", T0)
        val a = recordRun("alice", T0 + 10 * MIN)
        assertThat(syncer.sync("alice")).isEqualTo(SyncResult.Done(1))
        assertThat(uploader.uploaded).containsExactly(a)
        assertThat(h.tracker.unsyncedRuns("bob")).hasSize(1)
    }

    @Test
    fun `a run finished during the sync goes up in the same sync`() = runTest {
        val a = recordRun("alice", T0)
        var late: String? = null
        uploader.during = { if (late == null) late = recordRun("alice", T0 + 10 * MIN) }

        assertThat(syncer.sync("alice")).isEqualTo(SyncResult.Done(2))
        assertThat(uploader.uploaded).containsExactly(a, late).inOrder()
    }

    @Test
    fun `the upload waits for a network and backs off exponentially`() {
        val spec = RunSync.request("alice").workSpec
        assertThat(spec.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(spec.backoffPolicy).isEqualTo(BackoffPolicy.EXPONENTIAL)
        assertThat(spec.input.getString("owner")).isEqualTo("alice")
        assertThat(RunSync.uniqueWorkName("alice")).isNotEqualTo(RunSync.uniqueWorkName("bob"))
    }
}
