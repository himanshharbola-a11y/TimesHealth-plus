package timeshealth.app.core.domain

/*
 * The onboarding wording (design prototype) for goals, concerns and gender,
 * shared so the profile (PRD §10) repeats exactly what the user picked in
 * onboarding (PRD §5) — "Sleep & energy", not a title-cased enum.
 *
 * Keyed by the API's enum strings, so this module needs no :core:model type.
 * Port of apps/mobile/src/lib/profileLabels.ts (+ GENDERS from ProfileDrawer).
 */

/** One choice: the API value, the words shown, and the onboarding emoji if any. */
data class LabelOption(val value: String, val label: String, val emoji: String? = null)

/** Shown when a field has no (or an unknown) value. */
const val NO_VALUE: String = "—"

/** Health goals, in onboarding order (PRD §5 step 3). */
val GOALS: List<LabelOption> = listOf(
    LabelOption("WEIGHT_LOSS", "Lose weight", "🔥"),
    LabelOption("STRENGTH_FLEXIBILITY", "Build strength & flexibility", "🧘"),
    LabelOption("STRESS_ANXIETY", "Reduce stress & anxiety", "🍃"),
    LabelOption("MARATHON_TRAINING", "Train for a marathon race", "🏃"),
    LabelOption("CONSISTENCY", "Just stay consistent daily", "📅"),
)

/** Focus areas, in onboarding order (PRD §5 step 4). */
val CONCERNS: List<LabelOption> = listOf(
    LabelOption("LOWER_BACK", "Lower back"),
    LabelOption("KNEES_JOINTS", "Knees & joints"),
    LabelOption("NECK_SHOULDERS", "Neck & shoulders"),
    LabelOption("HIPS_PELVIS", "Hips & pelvis"),
    LabelOption("SLEEP_ENERGY", "Sleep & energy"),
    LabelOption("NONE", "Nothing specific"),
)

/** Gender options on the profile (race categories, PRD §10). */
val GENDERS: List<LabelOption> = listOf(
    LabelOption("FEMALE", "Female"),
    LabelOption("MALE", "Male"),
    LabelOption("NON_BINARY", "Non-binary"),
    LabelOption("PREFER_NOT_TO_SAY", "Prefer not to say"),
)

/** "WEIGHT_LOSS" → "Lose weight"; null or unknown (a newer server's value) → "—". */
fun goalLabel(value: String?): String = GOALS.firstOrNull { it.value == value }?.label ?: NO_VALUE

/** "SLEEP_ENERGY" → "Sleep & energy"; null or unknown → "—". */
fun concernLabel(value: String?): String = CONCERNS.firstOrNull { it.value == value }?.label ?: NO_VALUE

/** "NON_BINARY" → "Non-binary"; null or unknown → "—". */
fun genderLabel(value: String?): String = GENDERS.firstOrNull { it.value == value }?.label ?: NO_VALUE

private val E164_INDIA = Regex("^\\+91(\\d{5})(\\d{5})$")

/**
 * "+919000000004" → "+91 90000 00004". Anything else (a foreign number, a
 * legacy value) is shown exactly as stored — never reformatted wrongly.
 * Null or empty → "—".
 */
fun formatPhone(phone: String?): String {
    if (phone.isNullOrEmpty()) return NO_VALUE
    val m = E164_INDIA.matchEntire(phone) ?: return phone
    return "+91 ${m.groupValues[1]} ${m.groupValues[2]}"
}
