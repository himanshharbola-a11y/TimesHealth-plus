package timeshealth.server.session

import timeshealth.app.core.model.Concern
import timeshealth.app.core.model.Gender
import timeshealth.app.core.model.HealthGoal
import timeshealth.app.core.model.Units
import timeshealth.app.core.model.UserProfile
import timeshealth.server.db.entity.UserEntity
import timeshealth.server.json.toIsoString

/**
 * The closed sets the API accepts, taken from the SHARED contract (minus the client-only UNKNOWN
 * fallback), so a value the app can send is exactly a value the server accepts. Same order as
 * the zod enums in routes/session.ts (it appears in validation messages).
 */
object ProfileEnums {
    val HEALTH_GOALS: List<String> = HealthGoal.entries.filter { it != HealthGoal.UNKNOWN }.map { it.name }
    val CONCERNS: List<String> = Concern.entries.filter { it != Concern.UNKNOWN }.map { it.name }
    val GENDERS: List<String> = Gender.entries.filter { it != Gender.UNKNOWN }.map { it.name }
    val UNITS: List<String> = Units.entries.filter { it != Units.UNKNOWN }.map { it.name }
}

/** routes/session.ts `toProfileDto`. */
fun UserEntity.toProfileDto(): UserProfile = UserProfile(
    id = id,
    name = name,
    // The login identifier when there is one, else what the user typed.
    email = email ?: contactEmail,
    phone = phone ?: contactPhone,
    emailIsLogin = email != null,
    phoneIsLogin = phone != null,
    dob = dob?.toIsoString(),
    gender = gender,
    healthGoal = healthGoal?.let { g -> HealthGoal.entries.firstOrNull { it.name == g } ?: HealthGoal.UNKNOWN },
    concern = concern?.let { c -> Concern.entries.firstOrNull { it.name == c } ?: Concern.UNKNOWN },
    profileCompletion = profileCompletion,
    onboardingCompleted = onboardingCompleted,
    units = if (units == "IMPERIAL") Units.IMPERIAL else Units.METRIC,
    locale = locale,
)

/** routes/session.ts `computeCompletion`: 0–100 for the profile drawer. Six fields, weighted evenly. */
fun computeCompletion(u: UserEntity): Int {
    val fields: List<Any?> = listOf(
        u.name,
        u.email ?: u.contactEmail,
        u.phone ?: u.contactPhone,
        u.dob,
        u.healthGoal,
        u.concern,
    )
    val filled = fields.count { it != null && it != "" }
    // Math.round: half up, as in JavaScript.
    return Math.round(filled.toDouble() / fields.size * 100).toInt()
}
