/*
 * API request/response contracts. Mirrors packages/types/src/api.ts, which @th/api and
 * @th/mobile share at compile time; keep the two in step. The conventions are listed at the top of
 * Domain.kt.
 *
 * A few response shapes the RN app types inline (apps/mobile/src/api/hooks.ts) rather than in
 * packages/types are included here too, marked "Not in packages/types".
 */
package timeshealth.app.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// Envelope
// ─────────────────────────────────────────────────────────────────────────────

/** The body of every non-2xx response. */
@Serializable
data class ApiError(
    val code: String,
    val message: String,
    /**
     * Present on 4xx validation failures: field name to messages. The TS says
     * `Record<string, string>`, but the server sends zod's `string[]` per field. Both decode.
     * See [FieldErrorsSerializer].
     */
    @Serializable(with = FieldErrorsSerializer::class)
    val fields: Map<String, List<String>>? = null,
) {
    /** The first validation message for [field], if any. */
    fun fieldMessage(field: String): String? = fields?.get(field)?.firstOrNull()
}

/**
 * Lets callers branch without inspecting HTTP status codes (TS `ApiResult<T>`).
 *
 * Not a wire shape. The API returns the bare body on 2xx and an [ApiError] otherwise, and the
 * network layer builds this from those. TS discriminates on `ok: true | false`; Kotlin uses
 * subtypes instead.
 */
sealed interface ApiResult<out T> {
    data class Ok<out T>(val data: T) : ApiResult<T>
    data class Err(val error: ApiError) : ApiResult<Nothing>
}

// ─────────────────────────────────────────────────────────────────────────────
// Session / bootstrap
// ─────────────────────────────────────────────────────────────────────────────

/** Server-triggered maintenance screen ([SessionResponse.maintenance], [AppConfigResponse.maintenance]). */
@Serializable
data class Maintenance(
    val active: Boolean,
    val message: String? = null,
)

/**
 * GET /session. Called once on launch, after the Firebase ID token is available. Resolves
 * identity (PRD §5: phone on the app and email on the web map to one account) and returns
 * everything needed to decide the first screen.
 */
@Serializable
data class SessionResponse(
    val profile: UserProfile,
    val entitlements: Entitlements,
    val persona: PersonaInfo,
    /** True sends the user to onboarding. Returning subscribers never see it again (§5). */
    val needsOnboarding: Boolean,
    val serverTime: String,
    /** Hard gate. The client refuses to run below this version (see docs/05 §1). */
    val minSupportedAppVersion: String,
    val maintenance: Maintenance,
)

/**
 * GET /config. Public and unauthenticated, checked on EVERY launch before sign-in. The update
 * gate has to work even when an old version's login is what broke, which is exactly when you
 * need to force people off it.
 */
@Serializable
data class AppConfigResponse(
    val minSupportedAppVersion: String,
    val maintenance: Maintenance,
    val serverTime: String,
)

// ─────────────────────────────────────────────────────────────────────────────
// Onboarding: PRD §5. Every step is skippable, and progress is saved as it goes
// so a partial drop still leaves usable lead data.
// ─────────────────────────────────────────────────────────────────────────────

/** TS inline union on [UpdateProfileRequest.gender]. */
@Serializable(with = Gender.Serializer::class)
enum class Gender {
    FEMALE, MALE, NON_BINARY, PREFER_NOT_TO_SAY, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<Gender>(entries, UNKNOWN)
}

/**
 * PATCH /profile (§10 personal details). Only non-null fields are sent. The sign-in email or
 * phone can't be changed here (409 LOGIN_IDENTIFIER); see [UserProfile.emailIsLogin].
 */
@Serializable
data class UpdateProfileRequest(
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    /** ISO datetime; age 13–100. */
    val dob: String? = null,
    val gender: Gender? = null,
    val units: Units? = null,
    val locale: String? = null,
    val healthGoal: HealthGoal? = null,
    val concern: Concern? = null,
)

/** POST /onboarding/step. */
@Serializable
data class OnboardingStepRequest(
    /** TS `1 | 2 | 3 | 4`. Step 4 completes onboarding. */
    val step: Int,
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val healthGoal: HealthGoal? = null,
    val concern: Concern? = null,
) {
    init {
        require(step in 1..4) { "Onboarding step must be 1–4, was $step" }
    }
}

/**
 * Response of PATCH /profile, POST /onboarding/step and POST /onboarding/skip.
 * Not in packages/types. Shape taken from apps/api/src/routes/session.ts.
 */
@Serializable
data class ProfileResponse(val profile: UserProfile)

// ─────────────────────────────────────────────────────────────────────────────
// Yoga
// ─────────────────────────────────────────────────────────────────────────────

/** GET /yoga/today. */
@Serializable
data class YogaTodayResponse(
    /** All 8 daily batches. The user's reminder slot is flagged (§7.1). */
    val batches: List<YogaBatch> = emptyList(),
    val liveBatchId: String? = null,
    val nextBatchId: String? = null,
    /** Null when there is no session today: a rest day, or all batches have passed. */
    val nextSessionStartsAt: String? = null,
)

/** POST /yoga/join. */
@Serializable
data class JoinSessionRequest(val batchId: String)

/** How the app should open [JoinSessionResponse.joinUrl]. */
@Serializable(with = JoinMode.Serializer::class)
enum class JoinMode {
    EXTERNAL_APP, WEBVIEW, IN_APP_PLAYER, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<JoinMode>(entries, UNKNOWN)
}

/**
 * Returns the join target AND writes the attendance mark. The server writes it on this call
 * rather than trusting a client assertion; see docs/04 T9.
 */
@Serializable
data class JoinSessionResponse(
    val joinUrl: String,
    val mode: JoinMode,
    val attendanceRecorded: Boolean,
    val source: AttendanceSource,
)

/** GET /yoga/catalog. */
@Serializable
data class YogaCatalogResponse(
    val categories: List<YogaCategory> = emptyList(),
    val sessions: List<YogaSession> = emptyList(),
)

/** PUT /yoga/reminder-slot. */
@Serializable
data class SetReminderSlotRequest(val batchId: String)

/**
 * GET /yoga/sessions/{id}/playback. 403 NOT_ENTITLED for a locked session.
 * Not in packages/types. Typed inline in apps/mobile/src/api/hooks.ts.
 */
@Serializable
data class PlaybackResponse(
    /** Short-TTL signed URL. */
    val playbackUrl: String,
)

/**
 * GET /yoga/me/sessions: the caller's saved and completed session ids.
 * Not in packages/types. Typed inline in apps/mobile/src/api/hooks.ts.
 */
@Serializable
data class MySessionsResponse(
    val savedSessionIds: List<String> = emptyList(),
    val completedSessionIds: List<String> = emptyList(),
)

// ─────────────────────────────────────────────────────────────────────────────
// Marathon
// ─────────────────────────────────────────────────────────────────────────────

/** TS inline union on [MarathonListResponse.orderedBy]. */
@Serializable(with = EventOrdering.Serializer::class)
enum class EventOrdering {
    REGISTRATION, LOCATION, DATE, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<EventOrdering>(entries, UNKNOWN)
}

/** GET /marathon/events. */
@Serializable
data class MarathonListResponse(
    /**
     * Ordered per §8.2: registered first, then nearest by location if granted, otherwise next
     * upcoming by date. The server resolves the ordering, so the fallback is silent and the
     * client never asks for location twice.
     */
    val events: List<MarathonEvent> = emptyList(),
    val orderedBy: EventOrdering,
)

/** GET /marathon/events/{id}. */
@Serializable
data class RaceDetailResponse(
    val event: MarathonEvent,
    val bib: DigitalBib? = null,
    val result: RaceResult? = null,
    val kit: KitDelivery? = null,
    val referral: ReferralState? = null,
    /** Suppressed entirely when sold out or already Premium (§8.3 edge cases). */
    val upgradeOffer: UpgradeOffer? = null,
    val faqs: List<Faq> = emptyList(),
    /** The user's own race-day details (§10 "where held"). Null when not registered. */
    val participant: RaceParticipant? = null,
)

/** [RaceDetailResponse.upgradeOffer] (inline object in TS). */
@Serializable
data class UpgradeOffer(
    val available: Boolean,
    val netDifferencePaise: Long,
    val benefits: List<String> = emptyList(),
    /** Earned through Refer & Win (5 referrals): claim it free, with no payment. */
    val freeClaim: Boolean,
)

/** A question and answer pair: [RaceDetailResponse.faqs] and [ContentResponse.yogaFaqs]. */
@Serializable
data class Faq(val question: String, val answer: String)

/** [RaceDetailResponse.participant] (inline object in TS). */
@Serializable
data class RaceParticipant(
    val tshirtSize: String? = null,
    val emergencyContactName: String? = null,
    val emergencyContactPhone: String? = null,
)

/** PATCH /marathon/events/{eventId}/participant. Only non-null fields are sent. */
@Serializable
data class UpdateParticipantRequest(
    val eventId: String,
    val tshirtSize: String? = null,
    val emergencyContactName: String? = null,
    val emergencyContactPhone: String? = null,
)

/**
 * GET /marathon/events/{id}/bib-token: a fresh QR token for [DigitalBib.qrToken].
 * Not in packages/types. Typed inline in apps/mobile/src/api/hooks.ts.
 */
@Serializable
data class BibTokenResponse(
    val qrToken: String,
    val qrExpiresAt: String,
)

// ─────────────────────────────────────────────────────────────────────────────
// Run tracker: PRD §8.6
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /runs. Runs are recorded locally first and uploaded afterwards. The client owns [id]
 * (a UUID), so a retry is idempotent and an app killed mid-run loses nothing.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = UploadRunRequest.Serializer::class)
data class UploadRunRequest(
    val id: String,
    val startedAt: String,
    val endedAt: String,
    val distanceKm: Double,
    val durationSeconds: Int,
    val avgPaceSecPerKm: Int,
    val caloriesBurned: Int,
    /**
     * Always sent, as `null` when there is no route. The server requires the key
     * (zod `.nullable()`, not `.optional()`). See [RequiredNullKeysSerializer].
     */
    val routePolyline: String? = null,
    val hasAccuracyWarning: Boolean,
) {
    internal object Serializer :
        RequiredNullKeysSerializer<UploadRunRequest>(generatedSerializer(), setOf("routePolyline"))
}

/** GET /runs. */
@Serializable
data class RunHistoryResponse(
    val runs: List<RunRecord> = emptyList(),
    val totals: RunTotals,
)

/**
 * [RunHistoryResponse.totals] (inline object in TS). Covers EVERY run, not just the page
 * listed. "Month" is the IST calendar month.
 */
@Serializable
data class RunTotals(
    val runs: Int,
    val distanceKm: Double,
    val durationSeconds: Long,
    val longestKm: Double,
    val monthDistanceKm: Double,
)

// ─────────────────────────────────────────────────────────────────────────────
// Diet / workshops / content
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /diet/leads. TS `interface DietLeadRequest extends DietLead {}`, the same shape.
 * Null optional fields are omitted, which the server accepts (`.nullable().optional()`).
 */
typealias DietLeadRequest = DietLead

@Serializable
data class DietLeadResponse(
    val leadId: String,
    val message: String,
)

/** GET /workshops. */
@Serializable
data class WorkshopListResponse(
    val workshops: List<LiveWorkshop> = emptyList(),
)

/** [ContentResponse.mentor] (inline object in TS). PRD §7: "chief (spiritual) mentor". */
@Serializable
data class Mentor(
    val name: String,
    val title: String,
    val bio: String,
    val avatarUrl: String,
)

/** GET /content. */
@Serializable
data class ContentResponse(
    val articles: List<Article> = emptyList(),
    val quotes: List<UserQuote> = emptyList(),
    val instructors: List<Instructor> = emptyList(),
    /**
     * Served by the API rather than hard-coded in the app, so an answer that stops being true
     * (see the WhatsApp FAQ, docs/01 §B4) can be corrected without shipping a release.
     */
    val yogaFaqs: List<Faq> = emptyList(),
    val mentor: Mentor,
    /** PRD §7.2: instructor videos on the free Yoga page (the same reels as Home). */
    val reels: List<Reel> = emptyList(),
)

// ─────────────────────────────────────────────────────────────────────────────
// Commerce: scaffolded behind a real interface. See docs/04 §5 before enabling.
// ─────────────────────────────────────────────────────────────────────────────

/** TS inline union on [CreateOrderRequest.productType]. */
@Serializable(with = ProductType.Serializer::class)
enum class ProductType {
    YOGA_SUBSCRIPTION, MARATHON_REGISTRATION, PREMIUM_UPGRADE, WORKSHOP, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<ProductType>(entries, UNKNOWN)
}

/** POST /orders. The client never sends a price. */
@Serializable
data class CreateOrderRequest(
    val productType: ProductType,
    val productId: String,
    /** Marathon only. */
    val eventId: String? = null,
    val category: String? = null,
    val tier: RaceTier? = null,
    /**
     * Refer & Win (§8.3): a friend's code, from their shared link or typed at checkout. It is
     * credited to the friend only once this registration is PAID.
     */
    val referralCode: String? = null,
)

/** TS inline union on [CreateOrderResponse.gateway]. */
@Serializable(with = PaymentGateway.Serializer::class)
enum class PaymentGateway {
    RAZORPAY, STUB, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<PaymentGateway>(entries, UNKNOWN)
}

/** TS literal `'INR'` on [CreateOrderResponse.currency]. */
@Serializable(with = Currency.Serializer::class)
enum class Currency {
    INR, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<Currency>(entries, UNKNOWN)
}

/**
 * The server computes the amount. Entitlement is granted on the gateway webhook, never on client
 * confirmation; see docs/04 T8.
 */
@Serializable
data class CreateOrderResponse(
    val orderId: String,
    val gateway: PaymentGateway,
    val gatewayOrderId: String,
    val amountPaise: Long,
    val currency: Currency,
    /** Public key only. Secrets never leave the server. */
    val gatewayKeyId: String? = null,
)

/**
 * TS inline union on [OrderStatusResponse.status].
 * PAID_NOT_GRANTED: the money arrived but the product was gone by then (seat sold out,
 * registration closed), so the order is flagged for a refund.
 */
@Serializable(with = OrderStatus.Serializer::class)
enum class OrderStatus {
    CREATED, PENDING, PAID, FAILED, PAID_NOT_GRANTED, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<OrderStatus>(entries, UNKNOWN)
}

/** GET /orders/{id}. */
@Serializable
data class OrderStatusResponse(
    val orderId: String,
    val status: OrderStatus,
    val entitlementGranted: Boolean,
)

/** TS re-exports `ReferralState as Referral`. GET /marathon/referral returns it. */
typealias Referral = ReferralState

// ─────────────────────────────────────────────────────────────────────────────
// Notification inbox: the TopHeader bell (design). Every push the user was sent,
// newest first, so a tap on the bell is never a dead end.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable(with = NotificationKind.Serializer::class)
enum class NotificationKind {
    SESSION_REMINDER, SESSION_LIVE, RACE_COUNTDOWN, RACE_DAY_INFO, RESULT_PUBLISHED, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<NotificationKind>(entries, UNKNOWN)
}

@Serializable
data class NotificationItem(
    val id: String,
    val kind: NotificationKind,
    val title: String,
    val body: String,
    /** In-app route to open on tap, e.g. "/race/delhi_half/results". */
    val route: String? = null,
    val sentAt: String,
)

/** GET /notifications. */
@Serializable
data class NotificationListResponse(
    val items: List<NotificationItem> = emptyList(),
)
