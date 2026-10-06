package timeshealth.app.ui.run.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import timeshealth.app.core.domain.Coordinates

/**
 * PLUG-IN POINT: the map under a run (live) and its route (summary).
 *
 * The run screens draw [LocalRouteMap]`.current.RouteMap(state)` and never a
 * map SDK directly.
 *
 * TODAY: [MapLibreRouteMap], MapLibre with OpenFreeMap street tiles. It is
 * free and needs no API key, so the tracker works now.
 *
 * HOW TO PLUG IN THE GOOGLE MAPS SDK (the app team's standard)
 *  1. Get an Android Maps API key restricted to the app id + signing SHA-1, and
 *     add it to the manifest as `com.google.android.geo.API_KEY` (through a
 *     Gradle property, never in git).
 *  2. Add `com.google.maps.android:maps-compose` to app/build.gradle.kts.
 *  3. Write `class GoogleRouteMap @Inject constructor() : RouteMapRenderer`:
 *     a `GoogleMap` with a `Polyline` (coral, 5dp, round caps) over the
 *     route, a start marker, the current position; FOLLOW keeps the camera on
 *     the runner, FIT frames the whole route.
 *  4. In app/.../wiring/IntegrationsModule.kt change ONE line:
 *       `@Binds abstract fun routeMap(impl: GoogleRouteMap): RouteMapRenderer`
 *  5. Remove the MapLibre dependency.
 * No run screen changes. (iOS uses MapKit behind the same idea.)
 */
interface RouteMapRenderer {
    @Composable
    fun RouteMap(state: RouteMapState, modifier: Modifier)
}

/** What the map shows. */
@Immutable
data class RouteMapState(
    /** The route so far, in order. */
    val route: List<Coordinates>,
    val mode: Mode,
    /** Where to centre before there is any route (the last known position), if known. */
    val fallbackCenter: Coordinates? = null,
) {
    enum class Mode {
        /** While running: the camera follows the latest point, close in. */
        FOLLOW,

        /** The summary: the camera frames the whole route. */
        FIT,
    }
}

/** Provided once at the top of the app (MainActivity) from the binding in IntegrationsModule. */
val LocalRouteMap = staticCompositionLocalOf<RouteMapRenderer> {
    error("LocalRouteMap not provided: MainActivity provides the RouteMapRenderer bound in IntegrationsModule")
}
