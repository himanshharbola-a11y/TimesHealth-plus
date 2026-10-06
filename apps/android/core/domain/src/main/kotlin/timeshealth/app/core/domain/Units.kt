package timeshealth.app.core.domain

/*
 * Distance units for the run tracker (PRD §8.6). The profile's units setting
 * (METRIC / IMPERIAL) drives every distance and pace shown; runs are always
 * stored and uploaded in km and sec/km. Port of unitsFor in
 * apps/mobile/src/components/RunTrackerPanel.tsx.
 */

/** International mile, exactly. */
const val KM_PER_MILE: Double = 1.609344

/** Display units for one setting. Build with [unitsFor]. */
data class DistanceUnits(val imperial: Boolean) {
    /** "km" | "mi" */
    val short: String get() = if (imperial) "mi" else "km"

    /** "KILOMETRES" | "MILES" */
    val long: String get() = if (imperial) "MILES" else "KILOMETRES"

    /** A stored km figure in the display unit. */
    fun dist(km: Double): Double = if (imperial) km / KM_PER_MILE else km

    /**
     * 6'04" — pace per display unit, from seconds per km (no unit suffix; the
     * screen labels it). Not finite or not positive → --'--". Rounds the
     * total first, as [formatPace] does: 13:59.6 must not read 13'60".
     */
    fun pace(secPerKm: Double): String {
        val sec = if (imperial) secPerKm * KM_PER_MILE else secPerKm
        if (!sec.isFinite() || sec <= 0) return "--'--\""
        return minutesSeconds(sec)
    }
}

/** The units for the profile's setting: `unitsFor(profile.units == "IMPERIAL")`. */
fun unitsFor(imperial: Boolean): DistanceUnits = DistanceUnits(imperial)
