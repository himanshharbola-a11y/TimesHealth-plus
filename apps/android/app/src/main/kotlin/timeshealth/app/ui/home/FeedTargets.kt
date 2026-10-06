package timeshealth.app.ui.home

import timeshealth.app.core.model.AppTab as ModelTab
import timeshealth.app.core.model.FeedAction
import timeshealth.app.core.model.HeroMyRace
import timeshealth.app.core.model.HeroRaceResult
import timeshealth.app.core.model.HeroSellMarathon
import timeshealth.app.core.model.HeroSellYoga
import timeshealth.app.core.model.HeroSessionState
import timeshealth.app.core.model.HeroSlot
import timeshealth.app.core.model.HeroYogaRenew
import timeshealth.app.core.model.HeroYogaSession
import timeshealth.app.core.model.LiveClassCard
import timeshealth.app.core.model.YogaSession
import timeshealth.app.ui.navigation.AppTab
import timeshealth.app.ui.navigation.Route

/**
 * Where a tap on Home goes. Pure (no Android, no navigation controller), so
 * every feed action the server can send is unit-tested; the screen just
 * performs the target.
 */
sealed interface FeedTarget {
    /** A full-screen destination over the tabs. */
    data class Open(val route: Route) : FeedTarget

    /** Switch the bottom tab. */
    data class Tab(val tab: AppTab) : FeedTarget

    /** A web page, in the browser (only http(s); checked again when opened). */
    data class External(val url: String) : FeedTarget

    /** Nothing to do (an action this build doesn't know). */
    data object None : FeedTarget
}

/** The membership plan the paywall preselects when a locked item is tapped. */
const val YOGA_PLAN_ID = "yoga_annual"

/** A server action (promo strip, entry tile). The only navigation vocabulary the server has. */
fun targetFor(action: FeedAction): FeedTarget = when (action) {
    is FeedAction.OpenTab -> when (action.tab) {
        ModelTab.HOME -> FeedTarget.Tab(AppTab.HOME)
        ModelTab.YOGA -> FeedTarget.Tab(AppTab.YOGA)
        ModelTab.MARATHON -> FeedTarget.Tab(AppTab.MARATHON)
        ModelTab.DIET -> FeedTarget.Tab(AppTab.DIET)
        ModelTab.UNKNOWN -> FeedTarget.None
    }
    FeedAction.OpenRunTracker -> FeedTarget.Open(Route.RunTracker)
    is FeedAction.OpenYogaSession -> FeedTarget.Open(Route.SessionDetail(action.sessionId))
    is FeedAction.OpenYogaExplorer -> FeedTarget.Open(Route.YogaExplorer(action.categoryId))
    // The class link is issued by the Yoga tab's join (it also records attendance).
    is FeedAction.JoinLiveSession -> FeedTarget.Tab(AppTab.YOGA)
    is FeedAction.OpenRaceDetail -> FeedTarget.Open(Route.RaceDetail(action.eventId))
    is FeedAction.OpenRaceResults -> FeedTarget.Open(Route.RaceResults(action.eventId))
    is FeedAction.OpenDigitalBib -> FeedTarget.Open(Route.Bib(action.eventId))
    // Workshops are booked from the Yoga tab's workshop list.
    is FeedAction.OpenWorkshop -> FeedTarget.Tab(AppTab.YOGA)
    is FeedAction.OpenPaywall -> FeedTarget.Open(Route.Paywall(action.productId))
    FeedAction.OpenDietLeadForm -> FeedTarget.Tab(AppTab.DIET)
    is FeedAction.OpenArticle -> FeedTarget.External(action.url)
    is FeedAction.OpenExternal -> FeedTarget.External(action.url)
    is FeedAction.Unknown -> FeedTarget.None
}

/**
 * A hero card. A member's class opens the in-app live class when an admin
 * scheduled one for the batch and it is joinable; otherwise the Yoga tab,
 * where the timetable and the class link live.
 */
fun targetFor(slot: HeroSlot): FeedTarget = when (slot) {
    is HeroYogaSession -> {
        val joinable = slot.state == HeroSessionState.LIVE || slot.state == HeroSessionState.STARTING_SOON
        if (joinable && slot.liveClassId != null) {
            FeedTarget.Open(Route.LiveClass(slot.liveClassId!!))
        } else {
            FeedTarget.Tab(AppTab.YOGA)
        }
    }
    is HeroYogaRenew -> FeedTarget.Open(Route.Paywall(YOGA_PLAN_ID))
    // Race morning: the pass is what the runner needs at the gate.
    is HeroMyRace -> FeedTarget.Open(if (slot.isRaceDay) Route.Bib(slot.eventId) else Route.RaceDetail(slot.eventId))
    is HeroRaceResult -> FeedTarget.Open(Route.RaceResults(slot.eventId))
    is HeroSellYoga -> FeedTarget.Open(Route.Paywall(slot.planId))
    is HeroSellMarathon -> FeedTarget.Open(Route.RaceDetail(slot.eventId))
    is HeroSlot.Unknown -> FeedTarget.None
}

/** §6.3: a paid session tapped by a non-member opens the paywall, never a dead tap. */
fun targetFor(session: YogaSession, entitledToYoga: Boolean): FeedTarget =
    if (!session.isFree && !entitledToYoga) {
        FeedTarget.Open(Route.Paywall(YOGA_PLAN_ID))
    } else {
        FeedTarget.Open(Route.SessionDetail(session.id))
    }

/** A live class card: the class (it shows its own wait room), or the paywall. */
fun targetFor(liveClass: LiveClassCard): FeedTarget =
    if (liveClass.canJoin) FeedTarget.Open(Route.LiveClass(liveClass.id)) else FeedTarget.Open(Route.Paywall(YOGA_PLAN_ID))
