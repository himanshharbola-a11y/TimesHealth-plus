package timeshealth.app.ui.run.map

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import javax.inject.Inject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import timeshealth.app.core.domain.Coordinates

/**
 * The interim [RouteMapRenderer]: MapLibre (open-source map SDK) with
 * OpenFreeMap's "positron" style, a light, low-contrast street map like
 * Strava's, so the coral route stands out. No API key, no usage fees. Map data
 * © OpenStreetMap contributors (the attribution button stays on).
 *
 * A classic MapView through AndroidView (the team's Views interop), with its
 * lifecycle forwarded from the screen's.
 */
class MapLibreRouteMap @Inject constructor() : RouteMapRenderer {

    @Composable
    override fun RouteMap(state: RouteMapState, modifier: Modifier) {
        val context = LocalContext.current
        val mapView = remember {
            MapLibre.getInstance(context)
            MapView(context).apply { onCreate(Bundle()) }
        }
        var map by remember { mutableStateOf<MapLibreMap?>(null) }
        var style by remember { mutableStateOf<Style?>(null) }

        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, mapView) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            // Catch up if the screen is already started.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
            onDispose {
                lifecycle.removeObserver(observer)
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
        }

        LaunchedEffect(mapView) {
            mapView.getMapAsync { m ->
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                m.uiSettings.isCompassEnabled = false
                m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                    s.addSource(GeoJsonSource(ROUTE_SOURCE))
                    s.addSource(GeoJsonSource(ENDS_SOURCE))
                    // A white casing under the coral line keeps it legible on any street colour.
                    s.addLayer(
                        LineLayer(ROUTE_CASING, ROUTE_SOURCE).withProperties(
                            PropertyFactory.lineColor("#FFFFFF"),
                            PropertyFactory.lineWidth(8f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        ),
                    )
                    s.addLayer(
                        LineLayer(ROUTE_LINE, ROUTE_SOURCE).withProperties(
                            PropertyFactory.lineColor(ROUTE_COLOR),
                            PropertyFactory.lineWidth(5f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                        ),
                    )
                    s.addLayer(
                        CircleLayer(ENDS_LAYER, ENDS_SOURCE).withProperties(
                            PropertyFactory.circleRadius(6.5f),
                            PropertyFactory.circleColor(org.maplibre.android.style.expressions.Expression.get("color")),
                            PropertyFactory.circleStrokeColor("#FFFFFF"),
                            PropertyFactory.circleStrokeWidth(2.5f),
                        ),
                    )
                    map = m
                    style = s
                }
            }
        }

        // Redraw and move the camera whenever the route changes.
        LaunchedEffect(map, style, state) {
            val m = map ?: return@LaunchedEffect
            val s = style ?: return@LaunchedEffect
            val points = state.route.map { Point.fromLngLat(it.lng, it.lat) }
            (s.getSource(ROUTE_SOURCE) as? GeoJsonSource)?.setGeoJson(
                if (points.size >= 2) FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points)))
                else FeatureCollection.fromFeatures(emptyList()),
            )
            (s.getSource(ENDS_SOURCE) as? GeoJsonSource)?.setGeoJson(endsOf(state))
            moveCamera(m, state)
        }

        AndroidView(factory = { mapView }, modifier = modifier)
    }

    private fun endsOf(state: RouteMapState): FeatureCollection {
        val route = state.route
        if (route.isEmpty()) return FeatureCollection.fromFeatures(emptyList())
        fun dot(c: Coordinates, color: String) = Feature.fromGeometry(Point.fromLngLat(c.lng, c.lat)).apply { addStringProperty("color", color) }
        val start = dot(route.first(), START_COLOR)
        // Live: where the runner is. Summary: where the run finished.
        val last = dot(route.last(), if (state.mode == RouteMapState.Mode.FOLLOW) ROUTE_COLOR else FINISH_COLOR)
        return FeatureCollection.fromFeatures(if (route.size == 1) listOf(last) else listOf(start, last))
    }

    private fun moveCamera(map: MapLibreMap, state: RouteMapState) {
        val route = state.route
        when {
            route.isEmpty() -> state.fallbackCenter?.let {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat, it.lng), 15.0))
            }
            state.mode == RouteMapState.Mode.FOLLOW || route.size < 2 -> {
                val last = route.last()
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(last.lat, last.lng), 16.5), 600)
            }
            else -> {
                val bounds = LatLngBounds.Builder().includes(route.map { LatLng(it.lat, it.lng) }).build()
                // A run in one spot has zero-size bounds; fall back to a close zoom.
                runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80)) }
                    .onFailure { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(route.last().lat, route.last().lng), 16.0)) }
            }
        }
    }

    private companion object {
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
        const val ROUTE_SOURCE = "th-route"
        const val ENDS_SOURCE = "th-route-ends"
        const val ROUTE_CASING = "th-route-casing"
        const val ROUTE_LINE = "th-route-line"
        const val ENDS_LAYER = "th-route-ends-layer"
        const val ROUTE_COLOR = "#E8533A" // CoralBrand
        const val START_COLOR = "#10B981" // LiveEmerald
        const val FINISH_COLOR = "#1A1D21" // Carbon900
    }
}
