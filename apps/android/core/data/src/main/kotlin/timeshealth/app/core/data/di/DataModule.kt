package timeshealth.app.core.data.di

import android.content.Context
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timeshealth.app.core.data.AppConfig
import timeshealth.app.core.data.security.KeystoreSecureStore
import timeshealth.app.core.data.security.SecureStore
import timeshealth.app.core.data.session.SessionRepository
import timeshealth.app.core.network.NetworkFactory
import timeshealth.app.core.network.ServerClock
import timeshealth.app.core.network.TimesHealthApi
import timeshealth.app.core.network.TokenProvider

/**
 * A process-wide scope for work that must outlive any screen: following the identity provider,
 * ending a session after a 401. Provided by [DataModule]; the app may inject it too.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * The data layer's bindings. Everything else in this module is constructor-injected.
 *
 * The app module must bind:
 * - [AppConfig] (API base URL, debug flag) from its BuildConfig;
 * - [timeshealth.app.core.data.IdentityGateway] (Firebase today, Times SSO later);
 * - [timeshealth.app.core.data.TrackingSuspender] (the run tracker's suspendForSignOut).
 *
 * The network module's [ServerClock] is its own `@Singleton` (constructor-injected), so the clock
 * the API syncs is the one every screen reads.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    /**
     * The one API client. [SessionRepository] supplies its credential and hears its 401s, while
     * itself needing the API (to unregister push on sign-out): [Lazy] breaks that cycle. Both are
     * resolved on first request, not at graph creation.
     */
    @Provides
    @Singleton
    fun timesHealthApi(
        config: AppConfig,
        session: Lazy<SessionRepository>,
        serverClock: ServerClock,
    ): TimesHealthApi = NetworkFactory.createApi(
        baseUrl = config.apiBaseUrl,
        tokenProvider = object : TokenProvider {
            override suspend fun token(): String? = session.get().token()
        },
        // Idempotent and non-blocking, as the network layer requires: it may fire once per
        // in-flight request.
        onUnauthorized = { session.get().onUnauthorized() },
        debug = config.debug,
        serverClock = serverClock,
        logger = config::log,
    )

    /** DataStore allows one instance per file per process, hence the singleton. */
    @Provides
    @Singleton
    fun secureStore(@ApplicationContext context: Context): SecureStore = KeystoreSecureStore.create(context)

    /** SupervisorJob: one failed background task must not cancel the others. */
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
