package timeshealth.app.core.domain

/*
 * PERSONALISATION — the rules that tailor Home, Yoga and Marathon to one person.
 *
 * Rule-based on purpose (explainable, testable, no data science needed). Pure
 * Kotlin, shared by the app (Yoga, Marathon) and the server (Home), so every
 * screen agrees. A stronger recommender later replaces these functions behind
 * the same inputs and outputs; nothing that calls them changes.
 *
 * Inputs (UserSignals) are what we already know:
 *   - what they told us at onboarding: goal and focus area;
 *   - what they hold: yoga membership (active / lapsed), an upcoming race;
 *   - what they do: classes attended lately, streak, recordings completed and
 *     saved per track, runs in the last 30 days, longest run;
 *   - when they are here: the hour of day (IST).
 *
 * Outputs:
 *   - homeOrder: the admin's Home sections, re-ranked within a small window
 *     (the admin's first section stays first; nothing moves more than MAX_SHIFT places), so the
 *     dashboard's layout still leads and personalisation fine-tunes it;
 *   - personalRail: which yoga track leads Home, with a heading that says why;
 *   - rankCategories / recommendSessions: Yoga's tracks and "Recommended for
 *     you" sessions, each with a short reason;
 *   - suggestDistance: the race distance that fits their running so far.
 */

/** Everything personalisation looks at. Unknown values stay at their defaults. */
data class UserSignals(
    val goal: String? = null,
    val concern: String? = null,
    val isYogaMember: Boolean = false,
    val isLapsedMember: Boolean = false,
    val hasUpcomingRace: Boolean = false,
    val classesLast7Days: Int = 0,
    val currentStreak: Int = 0,
    /** Recordings completed per yoga track (category id → count). */
    val completedByCategory: Map<String, Int> = emptyMap(),
    /** Saved recordings per yoga track. */
    val savedByCategory: Map<String, Int> = emptyMap(),
    val runsLast30Days: Int = 0,
    val longestRunKm: Double = 0.0,
    val kmLast30Days: Double = 0.0,
    /** 0–23, IST. */
    val hourOfDay: Int = 12,
) {
    /** Runs, or wants to: running content should come forward. */
    val intoRunning: Boolean get() = goal == "MARATHON_TRAINING" || hasUpcomingRace || runsLast30Days > 0

    /** Practises regularly: deeper content (workshops, longer sessions) suits them. */
    val engaged: Boolean get() = currentStreak >= 3 || classesLast7Days >= 3

    /** The track they practise most (from completions, then saves), if any. */
    val favouriteCategory: String?
        get() = (completedByCategory.entries.maxByOrNull { it.value } ?: savedByCategory.entries.maxByOrNull { it.value })?.key
}

// ── Focus area / goal → yoga track ───────────────────────────────────────────

/** A yoga track chosen for someone, and the words that say why. */
data class PersonalRail(val categoryId: String, val heading: String, val reason: String)

private val CONCERN_TRACK = mapOf(
    "LOWER_BACK" to ("cat_spine" to "lower back relief"),
    "NECK_SHOULDERS" to ("cat_desk" to "neck & shoulder release"),
    "KNEES_JOINTS" to ("cat_flex" to "knee & joint stability"),
    "HIPS_PELVIS" to ("cat_flex" to "hip & pelvic release"),
    "SLEEP_ENERGY" to ("cat_sleep" to "deep sleep & energy"),
)

private val GOAL_TRACK = mapOf(
    "STRESS_ANXIETY" to ("cat_sleep" to "calm & stress release"),
    "WEIGHT_LOSS" to ("cat_core" to "weight loss & agility"),
    "STRENGTH_FLEXIBILITY" to ("cat_flex" to "strength & flexibility"),
    "MARATHON_TRAINING" to ("cat_flex" to "runners’ mobility"),
    "CONSISTENCY" to ("cat_morning" to "a daily morning habit"),
)

/** The track a goal or focus area points to (focus area wins: it is the more specific answer). */
fun trackFor(concern: String?, goal: String?): String? =
    concern?.let { CONCERN_TRACK[it]?.first } ?: goal?.let { GOAL_TRACK[it]?.first }

/**
 * Which track leads Home. Order of evidence: the focus area they named, then
 * what they actually practise (two or more completions), then their goal,
 * then a morning-flow default.
 */
fun personalRail(s: UserSignals, categoryNames: Map<String, String> = emptyMap()): PersonalRail {
    CONCERN_TRACK[s.concern]?.let { (cat, words) -> return PersonalRail(cat, "Sessions for $words", "For your ${concernLabel(s.concern).lowercase()}") }
    val fav = s.favouriteCategory
    if (fav != null && (s.completedByCategory[fav] ?: 0) >= 2) {
        val name = categoryNames[fav] ?: "your favourite track"
        return PersonalRail(fav, "More from $name", "Because you practise $name")
    }
    GOAL_TRACK[s.goal]?.let { (cat, words) -> return PersonalRail(cat, "Sessions for $words", "For your goal: $words") }
    return PersonalRail("cat_morning", "Start your day with yoga", "A gentle place to begin")
}

// ── Home: re-rank the admin's sections ───────────────────────────────────────

/** How far personalisation may move a section from where the admin put it. */
const val MAX_SHIFT = 3

/**
 * How strongly a Home section suits this person, in places to move up (+) or
 * down (−). Section kinds are the dashboard's (SectionKind names).
 */
fun sectionBoost(kind: String, s: UserSignals): Int {
    val morningOrEvening = s.hourOfDay in 5..9 || s.hourOfDay in 17..21
    return when (kind) {
        "PERSONALISED_RAIL" -> 2
        "LIVE_CLASSES" -> when {
            s.isYogaMember && morningOrEvening -> 3
            s.isYogaMember -> 2
            else -> 0
        }
        // Free samples sell to newcomers; members already have everything.
        "FREE_SESSIONS" -> if (s.isYogaMember) -3 else 2
        "TESTIMONIALS" -> if (s.isYogaMember) -2 else 1
        "PROMO" -> if (s.isYogaMember) -1 else 1
        "RUN_TRACKER_TILE" -> when {
            s.hasUpcomingRace || s.runsLast30Days >= 2 -> 3
            s.intoRunning -> 2
            else -> -1
        }
        "WORKSHOPS" -> if (s.engaged) 2 else 0
        "ARTICLES" -> if (s.goal == "STRESS_ANXIETY" || s.goal == "WEIGHT_LOSS") 1 else 0
        else -> 0
    }.coerceIn(-MAX_SHIFT, MAX_SHIFT)
}

/**
 * The admin's sections (in their order) re-ranked for this person. The admin's
 * FIRST section stays on top (usually the hero; if they put something above
 * it, that is their call); every other section moves by its boost, at most
 * [MAX_SHIFT] places; ties keep the admin's order.
 */
fun <T> personaliseOrder(sections: List<T>, kindOf: (T) -> String, s: UserSignals): List<T> {
    if (sections.size < 2) return sections
    val ranked = sections.drop(1).withIndex()
        .sortedWith(compareBy({ it.index - sectionBoost(kindOf(it.value), s) }, { it.index }))
        .map { it.value }
    return listOf(sections.first()) + ranked
}

// ── Yoga: tracks and sessions ────────────────────────────────────────────────

/** How well a track fits: named focus area, then goal, then what they practise and save. */
fun categoryScore(categoryId: String, s: UserSignals): Int {
    var score = 0
    if (CONCERN_TRACK[s.concern]?.first == categoryId) score += 100
    if (GOAL_TRACK[s.goal]?.first == categoryId) score += 60
    score += 12 * (s.completedByCategory[categoryId] ?: 0).coerceAtMost(5)
    score += 6 * (s.savedByCategory[categoryId] ?: 0).coerceAtMost(5)
    return score
}

/** Track ids in personal order (ties keep the catalogue's order). */
fun rankCategories(categoryIds: List<String>, s: UserSignals): List<String> =
    categoryIds.withIndex().sortedWith(compareBy({ -categoryScore(it.value, s) }, { it.index })).map { it.value }

/** What recommendSessions needs to know about one recording. */
data class SessionFacts(
    val id: String,
    val categoryId: String,
    val level: String,
    val durationMinutes: Int,
    val completed: Boolean,
    val saved: Boolean,
    val playable: Boolean,
)

/** A recommended recording and the one-line reason shown on its card. */
data class Recommendation(val sessionId: String, val reason: String)

/**
 * "Recommended for you": recordings ranked by fit. Track fit leads; new to
 * them beats already done; beginners (few classes so far) get gentler levels;
 * short sessions in the working day, longer ones morning and evening; things
 * they can play now beat locked ones. Returns at most [limit].
 */
fun recommendSessions(sessions: List<SessionFacts>, s: UserSignals, categoryNames: Map<String, String> = emptyMap(), limit: Int = 8): List<Recommendation> {
    val beginner = s.classesLast7Days == 0 && s.completedByCategory.values.sum() < 3
    val busyHours = s.hourOfDay in 10..16
    return sessions.map { x ->
        var score = categoryScore(x.categoryId, s)
        // Already done sinks below anything new, even on their focus track.
        if (x.completed) score -= 150
        if (x.saved && !x.completed) score += 25
        if (x.playable) score += 15
        val gentle = x.level.contains("Beginner", ignoreCase = true) || x.level.contains("All", ignoreCase = true)
        if (beginner && gentle) score += 20
        if (!beginner && x.level.contains("Intermediate", ignoreCase = true)) score += 10
        if (busyHours && x.durationMinutes <= 20) score += 15
        if (!busyHours && x.durationMinutes >= 30) score += 10
        x to score
    }
        .sortedByDescending { it.second }
        .take(limit)
        .map { (x, _) -> Recommendation(x.id, reasonFor(x, s, categoryNames, beginner, busyHours)) }
}

private fun reasonFor(x: SessionFacts, s: UserSignals, names: Map<String, String>, beginner: Boolean, busyHours: Boolean): String {
    val concern = CONCERN_TRACK[s.concern]
    val goal = GOAL_TRACK[s.goal]
    return when {
        x.saved && !x.completed -> "You saved this"
        concern?.first == x.categoryId -> "For your ${concern.second}"
        (s.completedByCategory[x.categoryId] ?: 0) >= 1 -> "Because you practise ${names[x.categoryId] ?: "this track"}"
        goal?.first == x.categoryId -> "For your goal: ${goal.second}"
        busyHours && x.durationMinutes <= 20 -> "A quick ${x.durationMinutes}-minute break"
        beginner -> "A good place to start"
        else -> "Picked for you"
    }
}

// ── Marathon: the distance that fits ─────────────────────────────────────────

/** A suggested race distance and why. */
data class DistanceSuggestion(val code: String, val reason: String)

/**
 * The race distance their running supports, from what is on offer (codes like
 * "5K", "10K", "21K", "42K"). Longest run and monthly volume set the level;
 * marathon-training goals with regular runs step up one; anything not on
 * offer falls back to the nearest shorter (or the shortest).
 */
fun suggestDistance(available: List<String>, s: UserSignals): DistanceSuggestion? {
    if (available.isEmpty()) return null
    val ladder = listOf("5K", "10K", "21K", "42K")
    var level = when {
        s.longestRunKm >= 25 || s.kmLast30Days >= 120 -> 3
        s.longestRunKm >= 14 || s.kmLast30Days >= 60 -> 2
        s.longestRunKm >= 6 || s.kmLast30Days >= 25 -> 1
        else -> 0
    }
    val stepUp = s.goal == "MARATHON_TRAINING" && s.runsLast30Days >= 4 && level < 3
    if (stepUp) level += 1
    val offered = ladder.filter { code -> available.any { it.equals(code, ignoreCase = true) } }
    val pick = offered.lastOrNull { ladder.indexOf(it) <= level } ?: offered.firstOrNull() ?: available.first()
    val reason = when {
        // No real run logged yet (or only GPS blips): never quote a 0.0 km "longest run".
        s.longestRunKm < 1.0 -> "A great first race"
        stepUp -> "Your training says you’re ready for a step up"
        else -> "Fits your longest run of ${"%.1f".format(s.longestRunKm)} km"
    }
    return DistanceSuggestion(pick, reason)
}
