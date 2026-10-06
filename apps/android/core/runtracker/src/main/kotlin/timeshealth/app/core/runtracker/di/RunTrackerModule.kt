package timeshealth.app.core.runtracker.di

import android.content.Context
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import timeshealth.app.core.runtracker.RunDisplayPreferences
import timeshealth.app.core.runtracker.WallClock
import timeshealth.app.core.runtracker.db.RunTrackerDatabase
import timeshealth.app.core.runtracker.service.ServiceTrackingControl
import timeshealth.app.core.runtracker.service.TrackingControl

/**
 * The module's own bindings. The app must still bind [timeshealth.app.core.runtracker.RunUploader]
 * and [timeshealth.app.core.runtracker.RunOwnerProvider]; [RunDisplayPreferences] is optional.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RunTrackerModule {

    @Binds
    abstract fun trackingControl(impl: ServiceTrackingControl): TrackingControl

    @BindsOptionalOf
    abstract fun displayPreferences(): RunDisplayPreferences

    companion object {
        /** One instance for the process: the service and the UI share it. */
        @Provides
        @Singleton
        fun database(@ApplicationContext context: Context): RunTrackerDatabase = RunTrackerDatabase.build(context)

        @Provides
        fun wallClock(): WallClock = WallClock { System.currentTimeMillis() }
    }
}
