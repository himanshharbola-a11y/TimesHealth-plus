package timeshealth.app.core.runtracker.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timeshealth.app.core.domain.GeoFix
import timeshealth.app.core.domain.unitsFor
import timeshealth.app.core.runtracker.RunBatchProcessor
import timeshealth.app.core.runtracker.RunDisplayPreferences
import timeshealth.app.core.runtracker.RunState
import timeshealth.app.core.runtracker.RunTracker
import timeshealth.app.core.runtracker.db.RunTrackerDatabase

/**
 * Records GPS for the RUNNING run (PRD §8.6 "background tracking and
 * screen-off must keep recording").
 *
 * A foreground service of type "location", started by RunTracker while the
 * app is on screen. That is what keeps fixes coming with the screen off; the
 * ongoing notification is what Android asks in exchange. No background
 * location permission is needed or requested.
 *
 * The database is the source of truth and the service follows it:
 * - it records into whichever run is RUNNING, through [RunBatchProcessor];
 * - it stops itself as soon as there is no RUNNING run (paused, finished,
 *   discarded, sign-out), wherever that change came from. RunTracker never
 *   calls stopService(), which crashes the app if it lands before this
 *   service has reached startForeground().
 *
 * Not sticky. If the process dies, the run is recovered as PAUSED by
 * RunTracker.recover() and the runner taps Resume. A restart by the system in
 * the background could not get while-in-use location anyway.
 */
class RunTrackerService : LifecycleService() {

    private lateinit var deps: RunTrackerServiceEntryPoint
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var notifications: RunNotifications
    private var wakeLock: PowerManager.WakeLock? = null

    /** Fixes are delivered and written here, one batch at a time, in order. */
    private var gpsThread: HandlerThread? = null
    private var requesting = false
    private var observer: Job? = null
    /** What the notification shows now; reused when a repeat start re-posts it. */
    private var lastNotice: TrackingNotice? = null
    private var generation = 0L
    private var lastStartId = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // Runs on gpsThread. The wake lock keeps a screen-off phone from
            // sleeping between receiving the fixes and committing them.
            val fixes = result.locations.map { it.toGeoFix() }
            wakeLock?.acquire(BATCH_WAKE_LOCK_TIMEOUT_MS)
            try {
                runBlocking { deps.batchProcessor().ingest(fixes) }
            } catch (e: Exception) {
                // One lost batch costs a few metres; a crash would cost the run.
                Log.w(TAG, "Could not record ${fixes.size} fixes", e)
            } finally {
                wakeLock?.takeIf { it.isHeld }?.release()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        deps = EntryPointAccessors.fromApplication(applicationContext, RunTrackerServiceEntryPoint::class.java)
        fused = LocationServices.getFusedLocationProviderClient(this)
        notifications = RunNotifications(this).also { it.ensureChannel() }
        wakeLock = getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        when (intent?.action) {
            ACTION_TRACK -> track(intent.getLongExtra(EXTRA_GENERATION, 0L))
            ACTION_PAUSE -> lifecycleScope.launch {
                deps.runTracker().pause()
                // While tracking, the observer sees PAUSED and stops the
                // service. If this command started a fresh instance, stop here.
                if (!requesting) stopSelf(startId)
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun track(generation: Long) {
        this.generation = generation
        // Foreground FIRST: after startForegroundService() the app has a few
        // seconds to get here, or Android kills it.
        try {
            ServiceCompat.startForeground(
                this,
                RunNotifications.NOTIFICATION_ID,
                notifications.build(lastNotice),
                FGS_TYPE,
            )
        } catch (e: RuntimeException) {
            // SecurityException: Android 14+ refuses a location service without
            // location permission. IllegalStateException: started from the
            // background (ForegroundServiceStartNotAllowedException).
            Log.w(TAG, "Could not start location tracking", e)
            giveUp()
            return
        }
        if (!hasFineLocation(this)) {
            giveUp()
            return
        }
        if (!requesting) requestUpdates()
        if (observer == null) observeRun()
    }

    /**
     * High accuracy (GPS-grade fixes; the 25 m gate drops anything worse),
     * every ~2 s, and only after 5 m of movement: standing at a signal costs
     * no fixes at all. No batching delay, so the live distance stays live.
     */
    @SuppressLint("MissingPermission") // checked by track() just before
    private fun requestUpdates() {
        val thread = gpsThread ?: HandlerThread("RunTrackerGps").also {
            it.start()
            gpsThread = it
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, thread.looper)
                .addOnFailureListener { e ->
                    Log.w(TAG, "Location updates refused", e)
                    giveUp()
                }
            requesting = true
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission missing", e)
            giveUp()
        }
    }

    /**
     * Keeps the notification's distance current and stops the service the
     * moment the run is no longer RUNNING, whoever changed it.
     */
    private fun observeRun() {
        observer = lifecycleScope.launch {
            val units = unitsFor(imperial())
            val dao = deps.runDatabase().runDao()
            dao.observeUnfinished().first { run ->
                if (run?.state == RunState.RUNNING) {
                    val notice = TrackingNotice.of(run, units)
                    if (notice != lastNotice) {
                        lastNotice = notice
                        notifications.show(notice)
                    }
                    false
                } else {
                    // Stop only if it is STILL not running. This emission can be
                    // stale: after Pause then a quick Resume, the Resume's start
                    // command may already have been handled here, and stopping
                    // now would leave a RUNNING run with no GPS behind it.
                    dao.unfinished()?.state != RunState.RUNNING
                }
            }
            observer = null
            stopTracking()
        }
    }

    private suspend fun imperial(): Boolean =
        deps.displayPreferences().orElse(null)?.let { runCatching { it.imperial() }.getOrNull() } ?: false

    /**
     * GPS can't record (permission gone, OS refused). Park the run from its
     * last fix rather than let the clock run on with no distance: the same
     * outcome as an app kill, and Resume asks for permission again.
     */
    private fun giveUp() {
        deps.trackingSession().end(generation)
        lifecycleScope.launch {
            runCatching { deps.runTracker().pause() }
            stopTracking()
        }
    }

    private fun stopTracking() {
        observer?.cancel()
        observer = null
        lastNotice = null
        if (requesting) {
            fused.removeLocationUpdates(callback)
            requesting = false
        }
        deps.trackingSession().end(generation)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Only if no newer start is queued (Pause then a quick Resume):
        // stopping past one still on its way to startForeground() would crash.
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        if (requesting) fused.removeLocationUpdates(callback)
        requesting = false
        gpsThread?.quitSafely()
        gpsThread = null
        wakeLock?.takeIf { it.isHeld }?.release()
        // Destroyed without going through stopTracking(): nothing records now,
        // so recover() must treat a RUNNING run as orphaned.
        deps.trackingSession().end(generation)
        super.onDestroy()
    }

    internal companion object {
        private const val TAG = "RunTracker"
        private const val WAKE_LOCK_TAG = "TimesHealth:RunTrackerBatch"
        private const val BATCH_WAKE_LOCK_TIMEOUT_MS = 30_000L

        private const val INTERVAL_MS = 2_000L
        private const val MIN_INTERVAL_MS = 1_000L
        private const val MIN_DISTANCE_M = 5f

        /** Unknown accuracy is treated as bad: dropped by the gate, never trusted (TS `?? 999`). */
        private const val UNKNOWN_ACCURACY_M = 999.0

        const val ACTION_TRACK = "timeshealth.app.runtracker.TRACK"
        const val ACTION_PAUSE = "timeshealth.app.runtracker.PAUSE"
        private const val EXTRA_GENERATION = "generation"

        private val FGS_TYPE =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

        fun trackIntent(context: Context, generation: Long): Intent =
            Intent(context, RunTrackerService::class.java)
                .setAction(ACTION_TRACK)
                .putExtra(EXTRA_GENERATION, generation)

        fun Location.toGeoFix(): GeoFix = GeoFix(
            lat = latitude,
            lng = longitude,
            accuracy = if (hasAccuracy()) accuracy.toDouble() else UNKNOWN_ACCURACY_M,
            altitude = if (hasAltitude()) altitude else null,
            speed = if (hasSpeed()) speed.toDouble() else null,
            // Wall-clock UTC, the same clock RunTracker stamps start/resume
            // with, so the stale-fix rule compares like with like.
            timestamp = time,
        )
    }
}

/** How the service reaches the Hilt graph without @AndroidEntryPoint. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RunTrackerServiceEntryPoint {
    fun runTracker(): RunTracker
    fun batchProcessor(): RunBatchProcessor
    fun trackingSession(): TrackingSession
    fun runDatabase(): RunTrackerDatabase
    fun displayPreferences(): Optional<RunDisplayPreferences>
}
