package timeshealth.app.core.runtracker

import timeshealth.app.core.domain.Coordinates

/**
 * Google's encoded polyline algorithm format, so a route costs ~10 bytes a
 * point instead of ~40 on the upload (`UploadRunRequest.routePolyline`).
 * Port of `encodePolyline` in apps/mobile/src/lib/runTracker.ts.
 *
 * Each point is rounded to 5 decimals (~1 m) with Java [Math.round], which
 * rounds half up exactly like JavaScript's `Math.round`, so both apps encode a
 * route identically. Each point is then written as the delta from the last.
 */
fun encodePolyline(points: List<Coordinates>): String {
    val out = StringBuilder(points.size * 8)
    var lastLat = 0L
    var lastLng = 0L
    for (p in points) {
        val lat = Math.round(p.lat * 1e5)
        val lng = Math.round(p.lng * 1e5)
        out.appendSigned(lat - lastLat)
        out.appendSigned(lng - lastLng)
        lastLat = lat
        lastLng = lng
    }
    return out.toString()
}

/** One value: zig-zag sign into bit 0, then 5-bit chunks, low first, +63. */
private fun StringBuilder.appendSigned(value: Long) {
    var v = if (value < 0) (value shl 1).inv() else value shl 1
    while (v >= 0x20) {
        append(((0x20L or (v and 0x1f)) + 63).toInt().toChar())
        v = v shr 5
    }
    append((v + 63).toInt().toChar())
}
