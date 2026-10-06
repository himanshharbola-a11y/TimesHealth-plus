package timeshealth.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import timeshealth.app.push.NotificationChannels
import timeshealth.app.push.PushTokenSync
import timeshealth.app.ui.session.ForegroundRefresh

@HiltAndroidApp
class TimesHealthApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var foregroundRefresh: ForegroundRefresh

    @Inject lateinit var pushTokenSync: PushTokenSync

    // Workers (run uploads) get their dependencies from Hilt.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Back after >= 60 s in the background: re-read anything entitlement-shaped.
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundRefresh)
        // Before any push can arrive: Android only shows a background push on a channel that exists.
        NotificationChannels.ensure(this)
        // Registers this device for the signed-in user's pushes (no-op without Firebase).
        pushTokenSync.start()
    }
}
