package timeshealth.app.ui.navigation

import kotlinx.serialization.Serializable

/**
 * The four bottom tabs, in bar order (PRD §4: fixed, identical for every user;
 * content inside changes by state, never a locked or greyed tab).
 */
@Serializable
enum class AppTab { HOME, YOGA, MARATHON, DIET }

/**
 * Every top-level destination, as type-safe Navigation Compose routes. One per
 * screen of the RN app (apps/mobile/app/…), with the same parameters, so a
 * push/inbox route maps 1:1 (see DeepLinks.kt).
 *
 * Conventions:
 * - Arguments are primitives / enums only; screens load the rest by id. Never
 *   put a model object in a route.
 * - Optional RN query params are nullable with a null default.
 * - A screen reads its arguments in its ViewModel with
 *   `savedStateHandle.toRoute<Route.X>()`, not from the composable.
 */
sealed interface Route {

    /** apps/mobile/app/index.tsx: launch switches → session → login / onboarding / tabs. */
    @Serializable
    data object Gate : Route

    /** login.tsx: pre-login carousel + sign-in card. */
    @Serializable
    data object Login : Route

    /** onboarding.tsx: the 4-step personalisation (PRD §5). */
    @Serializable
    data object Onboarding : Route

    /**
     * (tabs)/_layout.tsx: TopHeader + one of the four tabs + BottomNavBar.
     * [tab] is the tab to open on; tab switching inside is the nested [TabRoute] graph.
     */
    @Serializable
    data class Tabs(val tab: AppTab = AppTab.HOME) : Route

    /** paywall.tsx: a bottom SHEET over the current screen. [productId] preselects a plan. */
    @Serializable
    data class Paywall(val productId: String? = null) : Route

    /** yoga-explorer.tsx: sessions by category; [categoryId] preselects one. */
    @Serializable
    data class YogaExplorer(val categoryId: String? = null) : Route

    /** session/[id].tsx: a recorded session's detail page. */
    @Serializable
    data class SessionDetail(val id: String) : Route

    /** video/[id].tsx: the in-app player (Media3 PlayerView through AndroidView). */
    @Serializable
    data class VideoPlayer(val id: String) : Route

    /**
     * A live class ("premiere", scheduled in the admin dashboard): wait room, then the stream at
     * the same point for everyone. Joining records attendance for members.
     */
    @Serializable
    data class LiveClass(val id: String) : Route

    /** reel.tsx: an instructor reel, full screen ([url] is the media, the rest is the caption). */
    @Serializable
    data class Reel(val url: String, val title: String? = null, val handle: String? = null) : Route

    /**
     * race/[eventId]/index.tsx. [distance] carries the Marathon tab's distance
     * chip over; [edit] = "participant" opens the participant editor directly.
     */
    @Serializable
    data class RaceDetail(val eventId: String, val distance: String? = null, val edit: String? = null) : Route

    /** race/[eventId]/results.tsx. */
    @Serializable
    data class RaceResults(val eventId: String) : Route

    /** bib/[eventId].tsx: the digital bib + QR; opens offline from a saved pass (PRD §8.3). */
    @Serializable
    data class Bib(val eventId: String) : Route

    /** run-tracker.tsx: GPS run tracking (PRD §8.6). */
    @Serializable
    data object RunTracker : Route
}

/** The tabs' own destinations, inside [Route.Tabs]. */
sealed interface TabRoute {
    @Serializable
    data object Home : TabRoute

    @Serializable
    data object Yoga : TabRoute

    @Serializable
    data object Marathon : TabRoute

    @Serializable
    data object Diet : TabRoute
}

/** The tab destination for [this] tab. */
fun AppTab.route(): TabRoute = when (this) {
    AppTab.HOME -> TabRoute.Home
    AppTab.YOGA -> TabRoute.Yoga
    AppTab.MARATHON -> TabRoute.Marathon
    AppTab.DIET -> TabRoute.Diet
}
