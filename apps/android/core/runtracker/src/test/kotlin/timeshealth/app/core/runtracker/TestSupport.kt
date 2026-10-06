package timeshealth.app.core.runtracker

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import timeshealth.app.core.domain.GeoFix
import timeshealth.app.core.runtracker.db.RunTrackerDatabase
import timeshealth.app.core.runtracker.service.TrackingControl

internal const val T0 = 1_790_000_000_000L
internal const val SEC = 1_000L
internal const val MIN = 60_000L

/** Metres per degree of latitude at R = 6,371 km, so `fix(10, …)` is 10 m north. */
private const val M_PER_DEG_LAT = 111_195.0

/** A fix [metres] north of the start line at [atMs] (epoch ms). */
internal fun fix(metres: Double, atMs: Long, accuracy: Double = 5.0) = GeoFix(
    lat = 28.6139 + metres / M_PER_DEG_LAT,
    lng = 77.209,
    accuracy = accuracy,
    timestamp = atMs,
)

/** What [block] threw, or null. */
internal suspend fun thrownBy(block: suspend () -> Unit): Throwable? =
    try {
        block()
        null
    } catch (e: Exception) {
        e
    }

internal fun inMemoryDb(): RunTrackerDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RunTrackerDatabase::class.java)
        .allowMainThreadQueries()
        .build()

internal class TestClock(var now: Long = T0) : WallClock {
    override fun nowMs(): Long = now
}

internal class TestOwners(var owner: String? = null) : RunOwnerProvider {
    override suspend fun currentOwner(): String? = owner
}

/** Stands in for the foreground service: records what RunTracker asked of it. */
internal class FakeTracking : TrackingControl {
    override var isActive = false
    var permission = true
    var refuseStart: RuntimeException? = null
    var starts = 0
    var stops = 0

    override fun hasLocationPermission() = permission

    override fun start() {
        refuseStart?.let { throw it }
        starts += 1
        isActive = true
    }

    override fun stop() {
        stops += 1
        isActive = false
    }

    /** The app process died: nothing is recording any more. */
    fun processKilled() {
        isActive = false
    }
}

/** A tracker, its store and its fakes, wired as Hilt would wire them. */
internal class Harness {
    val db = inMemoryDb()
    val dao = db.runDao()
    val clock = TestClock()
    val owners = TestOwners()
    val tracking = FakeTracking()
    val tracker = RunTracker(db, tracking, owners, clock)
    val processor = RunBatchProcessor(db)

    /** Signs [owner] in and starts their run at the clock's time. */
    suspend fun startAs(owner: String): ActiveRun {
        owners.owner = owner
        return tracker.start(owner)
    }

    /** Feeds one batch: fixes [metres] north, [stepMs] apart, the first at [fromMs]. */
    suspend fun runTo(vararg metres: Double, fromMs: Long, stepMs: Long = 3 * SEC) {
        val fixes = metres.mapIndexed { i, m -> fix(m, fromMs + i * stepMs) }
        processor.ingest(fixes)
    }

    fun close() = db.close()
}
