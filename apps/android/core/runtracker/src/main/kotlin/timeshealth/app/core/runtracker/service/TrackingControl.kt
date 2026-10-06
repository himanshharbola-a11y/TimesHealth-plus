package timeshealth.app.core.runtracker.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts GPS recording for the run in the database. A seam so RunTracker's
 * rules are tested on the JVM without a real foreground service.
 */
internal interface TrackingControl {
    /**
     * Whether recording was started in THIS process and not stopped since.
     * False after the app was killed: the service died with the process, so a
     * run still marked RUNNING is not actually recording (see RunTracker.recover).
     */
    val isActive: Boolean

    fun hasLocationPermission(): Boolean

    /**
     * Starts (or keeps) the foreground location service. Call only after the
     * run is RUNNING in the database: the service records into whatever run is.
     * Throws if the OS refuses, e.g. ForegroundServiceStartNotAllowedException
     * when called while the app is in the background.
     */
    fun start()

    /**
     * Marks recording as stopped. The service itself stops when it sees the
     * run is no longer RUNNING in the database (see RunTrackerService).
     */
    fun stop()
}

/**
 * The process-wide "is recording" flag. It lives in memory on purpose: it
 * must read false after the process dies, which is exactly when the GPS
 * service died too (TS `hasStartedLocationUpdatesAsync`).
 *
 * Generations stop a late clean-up by an old service instance from clearing
 * the flag a newer start has just set (Pause then quickly Resume).
 */
@Singleton
internal class TrackingSession @Inject constructor() {
    private val counter = AtomicLong(0)
    private val active = AtomicLong(0)

    val isActive: Boolean get() = active.get() != 0L

    /** A new recording; returns its generation, which the service carries. */
    fun begin(): Long = counter.incrementAndGet().also { active.set(it) }

    /** Recording stopped by RunTracker (pause, finish, discard, sign-out). */
    fun end() = active.set(0)

    /** The service stopping itself: only if no newer recording has begun. */
    fun end(generation: Long) {
        active.compareAndSet(generation, 0)
    }
}

/** True when precise location is granted; see RunTracker.LOCATION_PERMISSIONS. */
internal fun hasFineLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

@Singleton
internal class ServiceTrackingControl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: TrackingSession,
) : TrackingControl {

    override val isActive: Boolean get() = session.isActive

    override fun hasLocationPermission(): Boolean = hasFineLocation(context)

    override fun start() {
        // Set before the service exists: a recover() racing the service's
        // start-up must already see this run as recording.
        val generation = session.begin()
        try {
            ContextCompat.startForegroundService(context, RunTrackerService.trackIntent(context, generation))
        } catch (e: RuntimeException) {
            session.end(generation)
            throw e
        }
    }

    // No stopService() here. Stopping a service that was started with
    // startForegroundService() before it has called startForeground() crashes
    // the app ("did not then call startForeground"), and Pause can follow
    // Start that closely. The service watches the run and stops itself.
    override fun stop() = session.end()
}
