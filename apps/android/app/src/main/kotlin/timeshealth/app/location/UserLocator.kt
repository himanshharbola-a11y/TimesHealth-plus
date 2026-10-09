package timeshealth.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where a location came from, best first. The UI says which, so "near you" is honest. */
enum class LocationSource { GPS, NETWORK, LAST_KNOWN, IP }

/** A position good enough to sort races by distance. [city] when the source knows it (IP). */
data class ApproxLocation(val lat: Double, val lng: Double, val source: LocationSource, val city: String? = null) {
    /** Device-grade (GPS, cell/Wi-Fi, or a recent fix), not the network's approximation. */
    val precise: Boolean get() = source != LocationSource.IP
}

/**
 * PLUG-IN POINT — approximate location from the device's IP address, used when
 * device location isn't allowed or doesn't answer. Today: [PublicIpGeolocator]
 * (free public geo-IP services). The tech team can bind their own geo-IP (or a
 * server-side lookup) in wiring/IntegrationsModule.kt; nothing else changes.
 */
interface IpGeolocator {
    suspend fun locate(): ApproxLocation?
}

/**
 * Asks free HTTPS geo-IP services in turn (ipapi.co, then ipwho.is); the first
 * answer with coordinates wins. City-level accuracy at best; never blocks the
 * screen for more than a few seconds per service.
 */
class PublicIpGeolocator @Inject constructor() : IpGeolocator {
    private val services = listOf(
        Triple("https://ipapi.co/json/", "latitude", "longitude"),
        Triple("https://ipwho.is/", "latitude", "longitude"),
    )

    override suspend fun locate(): ApproxLocation? = withContext(Dispatchers.IO) {
        for ((url, latKey, lngKey) in services) {
            val loc = try {
                fetch(url)?.let { json ->
                    val lat = json[latKey]?.jsonPrimitive?.doubleOrNull
                    val lng = json[lngKey]?.jsonPrimitive?.doubleOrNull
                    val city = json["city"]?.jsonPrimitive?.content
                    if (lat != null && lng != null) ApproxLocation(lat, lng, LocationSource.IP, city) else null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (loc != null) return@withContext loc
        }
        null
    }

    private fun fetch(url: String): JsonObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 4_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TimesHealthPlus-Android")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else Json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * The person's location for "races near you", best source first:
 *  1. a fresh device fix (GPS when precise location is allowed, else
 *     cell/Wi-Fi), waited for up to [FIX_TIMEOUT_MS];
 *  2. the device's last fix, if recent ([LAST_FIX_MAX_AGE_MS]);
 *  3. the IP address's approximate location ([IpGeolocator]).
 * Never asks for permission itself: the screen does that, in context.
 */
class UserLocator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ip: IpGeolocator,
) {
    fun hasDevicePermission(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    suspend fun locate(): ApproxLocation? = deviceLocation() ?: ip.locate()

    @SuppressLint("MissingPermission")
    private suspend fun deviceLocation(): ApproxLocation? {
        if (!hasDevicePermission()) return null
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val client = LocationServices.getFusedLocationProviderClient(context)
        return try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(if (fine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setDurationMillis(FIX_TIMEOUT_MS)
                .build()
            val fresh = withTimeoutOrNull(FIX_TIMEOUT_MS + 1_000) { client.getCurrentLocation(request, null).await() }
            if (fresh != null) {
                val source = if (fine && fresh.hasAccuracy() && fresh.accuracy <= 100f) LocationSource.GPS else LocationSource.NETWORK
                return ApproxLocation(fresh.latitude, fresh.longitude, source)
            }
            client.lastLocation.await()
                ?.takeIf { System.currentTimeMillis() - it.time <= LAST_FIX_MAX_AGE_MS }
                ?.let { ApproxLocation(it.latitude, it.longitude, LocationSource.LAST_KNOWN) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Location services off or unavailable: the IP fallback takes over.
            null
        }
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val FIX_TIMEOUT_MS = 6_000L
        const val LAST_FIX_MAX_AGE_MS = 30 * 60_000L
    }
}
