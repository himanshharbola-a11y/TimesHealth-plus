package timeshealth.app.wiring

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import timeshealth.app.BuildConfig
import timeshealth.app.core.integrations.analytics.AnalyticsTracker
import timeshealth.app.core.integrations.analytics.LogcatAnalyticsTracker
import timeshealth.app.core.integrations.campaigns.InAppCampaigns
import timeshealth.app.core.integrations.campaigns.NoOpInAppCampaigns
import timeshealth.app.core.integrations.push.PushHandler
import timeshealth.app.core.integrations.subscription.ServerCheckoutSubscriptionProvider
import timeshealth.app.core.integrations.subscription.SubscriptionProvider
import timeshealth.app.core.integrations.video.DirectUrlResolver
import timeshealth.app.core.integrations.video.VideoSourceResolver
import timeshealth.app.ui.run.map.MapLibreRouteMap
import timeshealth.app.ui.run.map.RouteMapRenderer

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  THE ONE FILE WHERE OUTSIDE SYSTEMS ARE CHOSEN.
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Every outside system is behind an interface in :core:integrations (each
 * interface's KDoc has step-by-step "HOW TO PLUG IN" notes). To switch from
 * a mock to the real thing: add the real class, then change or add ONE line
 * here. No screen, ViewModel or repository changes.
 *
 * | Plug-in point          | Today                              | Real (tech team)           |
 * |------------------------|------------------------------------|----------------------------|
 * | SubscriptionProvider   | ServerCheckoutSubscriptionProvider | TIL Subscription SDK       |
 * | AnalyticsTracker (set) | Logcat (debug builds only)         | + GrowthRx, + Google Analytics |
 * | InAppCampaigns         | NoOpInAppCampaigns                 | GrowthRx in-app            |
 * | PushHandler (set)      | none: our own notification         | + GrowthRx push            |
 * | VideoSourceResolver(set)| DirectUrlResolver ("url")         | + Slike ("slike")          |
 * | IdentityGateway        | Firebase / QA personas (AppModule) | TIL SSO SDK                |
 * | RouteMapRenderer       | MapLibre + OpenFreeMap (no key)    | Google Maps SDK            |
 * | IpGeolocator           | Public geo-IP (ipapi.co, ipwho.is) | Company geo-IP / server    |
 *
 * "(set)" points take ANY number of implementations: add a line, keep the rest.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class IntegrationsModule {

    // ── Subscriptions & payments ── swap to: TilSubscriptionProvider
    @Binds abstract fun subscriptions(impl: ServerCheckoutSubscriptionProvider): SubscriptionProvider

    // ── Run map ── swap to: GoogleRouteMap (needs the team's Maps API key; see RouteMapRenderer)
    @Binds abstract fun routeMap(impl: MapLibreRouteMap): RouteMapRenderer

    // ── In-app campaigns ── swap to: GrowthRxCampaigns
    @Binds abstract fun campaigns(impl: NoOpInAppCampaigns): InAppCampaigns

    // ── Approximate location from IP (fallback for "races near you") ── swap for a company geo-IP here.
    @Binds abstract fun ipGeolocator(impl: timeshealth.app.location.PublicIpGeolocator): timeshealth.app.location.IpGeolocator

    // ── Video sources ── add: @Binds @IntoSet abstract fun slike(impl: SlikeResolver): VideoSourceResolver
    @Binds @IntoSet abstract fun directUrlVideos(impl: DirectUrlResolver): VideoSourceResolver

    // ── Analytics ── add: @Binds @IntoSet abstract fun growthRx(impl: GrowthRxTracker): AnalyticsTracker
    //                 add: @Binds @IntoSet abstract fun googleAnalytics(impl: GoogleAnalyticsTracker): AnalyticsTracker
    @Multibinds abstract fun analyticsTrackers(): Set<AnalyticsTracker>

    // ── Push ── add: @Binds @IntoSet abstract fun growthRxPush(impl: GrowthRxPushHandler): PushHandler
    @Multibinds abstract fun pushHandlers(): Set<PushHandler>

    companion object {
        /** Event logging to Logcat while developing; nothing in release builds. */
        @Provides
        @ElementsIntoSet
        fun debugTrackers(): Set<AnalyticsTracker> =
            if (BuildConfig.DEBUG) setOf(LogcatAnalyticsTracker()) else emptySet()
    }
}
