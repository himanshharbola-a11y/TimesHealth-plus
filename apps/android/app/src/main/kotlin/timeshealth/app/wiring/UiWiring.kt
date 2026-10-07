package timeshealth.app.wiring

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import timeshealth.app.AppBuildInfo
import timeshealth.app.BuildConfig
import timeshealth.app.ui.diet.DietGateway
import timeshealth.app.ui.diet.RepositoryDietGateway
import timeshealth.app.ui.home.HomeGateway
import timeshealth.app.ui.home.RepositoryHomeGateway
import timeshealth.app.ui.inbox.InboxListGateway
import timeshealth.app.ui.inbox.RepositoryInboxListGateway
import timeshealth.app.ui.live.LiveClassGateway
import timeshealth.app.ui.live.RepositoryLiveClassGateway
import timeshealth.app.ui.login.DemoSignIn
import timeshealth.app.ui.login.InteractiveSignIn
import timeshealth.app.ui.marathon.MarathonGateway
import timeshealth.app.ui.onboarding.OnboardingGateway
import timeshealth.app.ui.onboarding.RepositoryOnboardingGateway
import timeshealth.app.ui.marathon.RepositoryMarathonGateway
import timeshealth.app.ui.profile.ProfileGateway
import timeshealth.app.ui.profile.RepositoryProfileGateway
import timeshealth.app.ui.run.RunGateway
import timeshealth.app.ui.run.TrackerRunGateway
import timeshealth.app.ui.login.UnavailableSignIn
import timeshealth.app.ui.session.AccountGateway
import timeshealth.app.ui.session.InboxGateway
import timeshealth.app.ui.session.RepositoryAccountGateway
import timeshealth.app.ui.session.RepositoryInboxGateway
import timeshealth.app.ui.session.RepositorySessionGateway
import timeshealth.app.ui.session.SessionGateway
import timeshealth.app.ui.workshop.RepositoryWorkshopGateway
import timeshealth.app.ui.workshop.WorkshopGateway
import timeshealth.app.ui.yoga.RepositoryYogaGateway
import timeshealth.app.ui.yoga.RepositoryYogaSessionsGateway
import timeshealth.app.ui.yoga.YogaGateway
import timeshealth.app.ui.yoga.YogaSessionsGateway

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

    @Binds abstract fun liveClassGateway(impl: RepositoryLiveClassGateway): LiveClassGateway

    @Binds abstract fun runGateway(impl: TrackerRunGateway): RunGateway

    @Binds abstract fun marathonGateway(impl: RepositoryMarathonGateway): MarathonGateway

    @Binds abstract fun yogaGateway(impl: RepositoryYogaGateway): YogaGateway

    @Binds abstract fun yogaSessionsGateway(impl: RepositoryYogaSessionsGateway): YogaSessionsGateway

    @Binds abstract fun profileGateway(impl: RepositoryProfileGateway): ProfileGateway

    @Binds abstract fun inboxListGateway(impl: RepositoryInboxListGateway): InboxListGateway

    @Binds abstract fun dietGateway(impl: RepositoryDietGateway): DietGateway

    @Binds abstract fun onboardingGateway(impl: RepositoryOnboardingGateway): OnboardingGateway

    @Binds abstract fun workshopGateway(impl: RepositoryWorkshopGateway): WorkshopGateway

    companion object {
        /**
         * Google / phone OTP / email on the login card. PLUG-IN POINT for the TIL SSO SDK:
         * bind its InteractiveSignIn here. Until then debug / test builds use the DemoSignIn
         * stand-in (OTP 123456) and release builds say sign-in isn't available.
         */
        @Provides
        fun interactiveSignIn(demo: javax.inject.Provider<DemoSignIn>): InteractiveSignIn =
            if (BuildConfig.DEBUG || BuildConfig.DEV_SIGNIN) demo.get() else UnavailableSignIn()

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
