package timeshealth.app.core.integrations.analytics

import android.util.Log
import javax.inject.Inject

/**
 * PLUG-IN POINT — analytics.
 *
 * Screens and ViewModels record what happened through [Analytics] (inject it)
 * using the typed events in [AnalyticsEvents] — never a vendor SDK directly.
 * [Analytics] fans every event out to ALL registered [AnalyticsTracker]s, so
 * GrowthRx and Google Analytics can both receive the same events.
 *
 * TODAY: [LogcatAnalyticsTracker] (prints events in debug builds).
 *
 * HOW TO PLUG IN GROWTHRX / GOOGLE ANALYTICS
 *  1. Add the SDK dependency to app/build.gradle.kts.
 *  2. Write `class GrowthRxTracker @Inject constructor(...) : AnalyticsTracker`
 *     (and/or `GoogleAnalyticsTracker`) mapping [AnalyticsEvent.name] and
 *     [AnalyticsEvent.params] to the SDK's calls.
 *  3. In app/.../wiring/IntegrationsModule.kt add ONE line per tracker:
 *       `@Binds @IntoSet fun growthRx(impl: GrowthRxTracker): AnalyticsTracker`
 * Every existing event then flows to it automatically.
 */
interface AnalyticsTracker {
    fun track(event: AnalyticsEvent)

    /** Associates later events with a signed-in user; null on sign-out. */
    fun identify(userId: String?)
}

/** One thing that happened. [name] is snake_case; [params] values are String/Number/Boolean. */
data class AnalyticsEvent(val name: String, val params: Map<String, Any> = emptyMap())

/**
 * The single entry point the app uses. Fans out to every tracker; one
 * tracker throwing never stops the others or crashes the app — analytics
 * must never break a user flow.
 */
class Analytics @Inject constructor(
    private val trackers: Set<@JvmSuppressWildcards AnalyticsTracker>,
) {
    fun track(event: AnalyticsEvent) = trackers.forEach { t -> runCatching { t.track(event) } }

    fun identify(userId: String?) = trackers.forEach { t -> runCatching { t.identify(userId) } }
}

/** Prints events to Logcat — the interim tracker until GrowthRx / GA are added. */
class LogcatAnalyticsTracker @Inject constructor() : AnalyticsTracker {
    override fun track(event: AnalyticsEvent) {
        Log.d(TAG, "${event.name} ${event.params}")
    }

    override fun identify(userId: String?) {
        Log.d(TAG, "identify ${userId ?: "<signed out>"}")
    }

    private companion object {
        const val TAG = "Analytics"
    }
}

/**
 * The event catalogue. Add events here (not as ad-hoc strings at call sites)
 * so every tracker — and the analytics team — sees one consistent taxonomy.
 * Align names with GrowthRx's taxonomy once the tech team shares it.
 */
object AnalyticsEvents {
    fun screenView(screen: String) = AnalyticsEvent("screen_view", mapOf("screen" to screen))
    fun signIn(method: String) = AnalyticsEvent("sign_in", mapOf("method" to method))
    fun signOut() = AnalyticsEvent("sign_out")

    fun purchaseStarted(productType: String, productId: String) =
        AnalyticsEvent("purchase_started", mapOf("product_type" to productType, "product_id" to productId))
    fun purchaseResult(productType: String, productId: String, outcome: String) =
        AnalyticsEvent(
            "purchase_result",
            mapOf("product_type" to productType, "product_id" to productId, "outcome" to outcome),
        )

    fun classJoined(batchId: String) = AnalyticsEvent("class_joined", mapOf("batch_id" to batchId))
    fun reminderSet(batchId: String) = AnalyticsEvent("class_reminder_set", mapOf("batch_id" to batchId))
    fun videoPlayed(sessionId: String) = AnalyticsEvent("video_played", mapOf("session_id" to sessionId))

    fun runStarted() = AnalyticsEvent("run_started")
    fun runFinished(distanceKm: Double, seconds: Int) =
        AnalyticsEvent("run_finished", mapOf("distance_km" to distanceKm, "duration_s" to seconds))

    fun raceRegistered(eventId: String) = AnalyticsEvent("race_registered", mapOf("event_id" to eventId))
    fun dietLeadSubmitted() = AnalyticsEvent("diet_lead_submitted")
}
