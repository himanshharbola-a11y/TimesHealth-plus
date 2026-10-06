package timeshealth.app.wiring

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import timeshealth.app.AppBuildInfo
import timeshealth.app.BuildConfig
import timeshealth.app.ui.home.HomeGateway
import timeshealth.app.ui.home.RepositoryHomeGateway
import timeshealth.app.ui.login.InteractiveSignIn
import timeshealth.app.ui.login.UnavailableSignIn
import timeshealth.app.ui.session.AccountGateway
import timeshealth.app.ui.session.InboxGateway
import timeshealth.app.ui.session.RepositoryAccountGateway
import timeshealth.app.ui.session.RepositoryInboxGateway
import timeshealth.app.ui.session.RepositorySessionGateway
import timeshealth.app.ui.session.SessionGateway

/**
 * What the UI layer's ViewModels are given: the gateways over core:data (see
 * ui/session/Gateways.kt for why they exist), the interactive sign-in, and
 * the build facts.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UiWiring {

    @Binds abstract fun sessionGateway(impl: RepositorySessionGateway): SessionGateway

    @Binds abstract fun accountGateway(impl: RepositoryAccountGateway): AccountGateway

    @Binds abstract fun inboxGateway(impl: RepositoryInboxGateway): InboxGateway

    @Binds abstract fun homeGateway(impl: RepositoryHomeGateway): HomeGateway

    /**
     * Google / phone OTP / email on the login card. Unavailable until the
     * Firebase flows are wired: then bind a FirebaseInteractiveSignIn here when
     * BuildConfig.FIREBASE_ENABLED (as AppModule does for the IdentityGateway).
     */
    @Binds abstract fun interactiveSignIn(impl: UnavailableSignIn): InteractiveSignIn

    companion object {
        @Provides
        fun buildInfo(): AppBuildInfo = AppBuildInfo(
            versionName = BuildConfig.VERSION_NAME,
            applicationId = BuildConfig.APPLICATION_ID,
            debug = BuildConfig.DEBUG,
            devSignIn = BuildConfig.DEV_SIGNIN,
            firebaseEnabled = BuildConfig.FIREBASE_ENABLED,
        )
    }
}
