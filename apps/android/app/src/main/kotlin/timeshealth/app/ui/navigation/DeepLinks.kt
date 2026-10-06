package timeshealth.app.ui.navigation

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timeshealth.app.core.domain.isAppRoute

/**
 * The destination for an in-app route from a push payload or an inbox item
 * (`data.route`, the RN expo-router path), or null when it isn't one.
 *
 * The route arrives in a payload the app didn't write, so it must first pass
 * core:domain's [isAppRoute] (whole-string match against the screens that
 * exist, ids 1–64 of `[A-Za-z0-9_-]`); only then is it parsed:
 *
 * | route                          | destination                       |
 * |--------------------------------|-----------------------------------|
 * | /(tabs)                        | Tabs(HOME)                        |
 * | /(tabs)/yoga · marathon · diet | Tabs(YOGA · MARATHON · DIET)      |
 * | /race/{id}                     | RaceDetail(id)                    |
 * | /race/{id}/results             | RaceResults(id)                   |
 * | /bib/{id}                      | Bib(id)                           |
 * | /session/{id}                  | SessionDetail(id)                 |
 * | /paywall                       | Paywall()                         |
 * | /run-tracker                   | RunTracker                        |
 * | /yoga-explorer                 | YogaExplorer()                    |
 */
fun appRouteToDestination(route: String?): Route? {
    if (!isAppRoute(route)) return null
    val parts = route!!.removePrefix("/").split('/')
    return when (parts[0]) {
        "(tabs)" -> Route.Tabs(
            when (parts.getOrNull(1)) {
                "yoga" -> AppTab.YOGA
                "marathon" -> AppTab.MARATHON
                "diet" -> AppTab.DIET
                else -> AppTab.HOME
            },
        )
        "race" -> if (parts.getOrNull(2) == "results") Route.RaceResults(parts[1]) else Route.RaceDetail(parts[1])
        "bib" -> Route.Bib(parts[1])
        "session" -> Route.SessionDetail(parts[1])
        "paywall" -> Route.Paywall()
        "run-tracker" -> Route.RunTracker
        "yoga-explorer" -> Route.YogaExplorer()
        else -> null
    }
}

/**
 * A route waiting to be opened: a notification tapped while the app was
 * closed or signed out arrives before there is anywhere to open it. AppNavHost
 * opens it once the user is on the tabs (signed in), as RN only handled taps
 * while its tabs were mounted.
 *
 * MainActivity offers the intent's `route` extra (the push data payload key);
 * push wiring (FCM) will offer here too.
 */
@Singleton
class PendingDeepLink @Inject constructor() {
    private val _pending = MutableStateFlow<Route?>(null)
    val pending: StateFlow<Route?> = _pending.asStateFlow()

    /** Queues [route] if it is a valid app route; returns whether it was accepted. */
    fun offer(route: String?): Boolean {
        val destination = appRouteToDestination(route) ?: return false
        _pending.value = destination
        return true
    }

    /** Takes the waiting route (once). */
    fun consume(): Route? = _pending.value.also { _pending.value = null }

    companion object {
        /** The intent extra / push data key carrying the route (RN `data.route`). */
        const val EXTRA_ROUTE = "route"
    }
}
