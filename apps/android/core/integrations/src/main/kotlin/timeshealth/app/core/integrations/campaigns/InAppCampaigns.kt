package timeshealth.app.core.integrations.campaigns

import javax.inject.Inject

/**
 * PLUG-IN POINT — in-app campaigns (pop-ups, banners) run from a marketing
 * panel. The tech team runs these through GrowthRx.
 *
 * The app tells the campaign system where the user is ([onScreen]) and what
 * they just did ([onEvent]); the campaign system decides whether to show
 * something. Screens never call a campaign SDK directly.
 *
 * TODAY: [NoOpInAppCampaigns] — nothing is shown.
 *
 * HOW TO PLUG IN GROWTHRX IN-APP
 *  1. Add the GrowthRx SDK to app/build.gradle.kts.
 *  2. Write `class GrowthRxCampaigns @Inject constructor(...) : InAppCampaigns`.
 *  3. In app/.../wiring/IntegrationsModule.kt change ONE line:
 *       `@Binds fun campaigns(impl: GrowthRxCampaigns): InAppCampaigns`
 */
interface InAppCampaigns {
    /** The user opened [screen] (the same names as AnalyticsEvents.screenView). */
    fun onScreen(screen: String)

    /** The user did [event] — a campaign may be triggered by it. */
    fun onEvent(event: String, params: Map<String, Any> = emptyMap())
}

/** Shows nothing. The interim implementation until GrowthRx is added. */
class NoOpInAppCampaigns @Inject constructor() : InAppCampaigns {
    override fun onScreen(screen: String) = Unit
    override fun onEvent(event: String, params: Map<String, Any>) = Unit
}
