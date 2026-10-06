package timeshealth.app.wiring

import android.content.Context
import com.google.firebase.FirebaseApp
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import timeshealth.app.BuildConfig
import timeshealth.app.core.data.AppConfig
import timeshealth.app.core.data.IdentityGateway
import timeshealth.app.core.data.TrackingSuspender
import timeshealth.app.core.runtracker.RunDisplayPreferences
import timeshealth.app.core.runtracker.RunOwnerProvider
import timeshealth.app.core.runtracker.RunTracker
import timeshealth.app.core.runtracker.RunUploader

/**
 * Everything the core modules ask the app to supply. The core modules provide
 * the API client, secure storage and app scope themselves (core/data DataModule).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds abstract fun runUploader(impl: ApiRunUploader): RunUploader

    @Binds abstract fun runOwner(impl: SessionRunOwner): RunOwnerProvider

    @Binds abstract fun runDisplay(impl: ProfileUnits): RunDisplayPreferences

    companion object {
        @Provides
        @Singleton
        fun appConfig(): AppConfig = object : AppConfig {
            override val apiBaseUrl: String = BuildConfig.API_BASE_URL
            override val debug: Boolean = BuildConfig.DEBUG
        }

        /**
         * Firebase only when this build was configured with it AND it actually
         * initialised; otherwise persona-only. Never calls into an
         * unconfigured Firebase, which throws.
         */
        @Provides
        @Singleton
        fun identity(@ApplicationContext context: Context): IdentityGateway =
            if (BuildConfig.FIREBASE_ENABLED && FirebaseApp.getApps(context).isNotEmpty()) {
                FirebaseIdentityGateway()
            } else {
                NoIdentityGateway()
            }

        /**
         * Sign-out stops GPS for someone who has left; their runs stay parked
         * under their name. Lazy: the tracker needs the session (whose run is
         * this?) and the session's sign-out needs the tracker — resolving the
         * tracker only when a sign-out actually happens breaks that cycle.
         */
        @Provides
        @Singleton
        fun trackingSuspender(tracker: Lazy<RunTracker>): TrackingSuspender =
            TrackingSuspender { tracker.get().suspendForSignOut() }
    }
}
