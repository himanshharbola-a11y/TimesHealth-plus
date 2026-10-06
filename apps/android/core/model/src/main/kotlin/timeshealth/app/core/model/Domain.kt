/*
 * TimesHealth+ domain model. Mirrors packages/types/src/domain.ts; keep the two in step.
 *
 * Field names follow the design prototype so screens map 1:1, but the types are corrected for a
 * real API: the prototype stored display strings (`dateStr`, `timeStr`, `priceLabel`). Here they
 * are ISO-8601 timestamps and integers, and the client formats them. The server never sends a
 * pre-formatted string the client might need to localise, sort or compute against.
 *
 * Conventions used by every contract file (Domain.kt, Api.kt, Feed.kt):
 * - JSON names are the Kotlin property names (camelCase, identical to the TS).
 * - Money is always integer paise, as Long. Never floats.
 * - Timestamps stay `String` (ISO-8601 UTC) exactly as on the wire; parse at the edge.
 * - TS `x?: T` and `T | null` become `T? = null`, so a missing key parses.
 * - Lists default to empty only where empty is a harmless display state. Lists whose absence
 *   would misstate entitlements, attendance or prices stay required, so a broken response fails
 *   loudly instead of misleading the user.
 * - Closed string unions are enums with an `UNKNOWN` fallback. See ApiJson.kt.
 */
package timeshealth.app.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ─────────────────────────────────────────────────────────────────────────────
// Entitlement: PRD §2, §3. Independent flags, never a tier.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable(with = YogaStatus.Serializer::class)
enum class YogaStatus {
    ACTIVE, EXPIRED, CANCELLED, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<YogaStatus>(entries, UNKNOWN)
}

@Serializable(with = RaceTier.Serializer::class)
enum class RaceTier {
    CLASSIC, PREMIUM, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<RaceTier>(entries, UNKNOWN)
}

@Serializable(with = RaceLifecycleStatus.Serializer::class)
enum class RaceLifecycleStatus {
    UPCOMING, RACE_DAY, COMPLETED, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<RaceLifecycleStatus>(entries, UNKNOWN)
}

@Serializable
data class YogaEntitlement(
    val active: Boolean,
    val status: YogaStatus,
    val planId: String,
    val planLabel: String,
    val startedAt: String,
    val expiresAt: String,
    val autoRenews: Boolean,
    /** The batch the user picked for reminders. They may still join any batch (§7.1). */
    val reminderSlotId: String? = null,
)

@Serializable
data class MarathonEntitlement(
    val eventId: String,
    /** For display in My Products, because "10K · Premium" alone doesn't say which race. */
    val eventName: String,
    val registrationRef: String,
    val tier: RaceTier,
    /** e.g. "21K". One of the event's [MarathonEvent.distanceOptions]. */
    val category: String,
    val bibNumber: String? = null,
    val status: RaceLifecycleStatus,
    val registeredAt: String,
)

/**
 * The spine of the app. Every screen resolves against this. Server-authoritative: the client
 * never computes or overrides it.
 *
 * [diet] is always null in V1 (PRD §9, accepted gap). The key exists so that mapping diet
 * customers later is additive, not a breaking change.
 */
@Serializable
data class Entitlements(
    val yoga: YogaEntitlement? = null,
    val marathon: List<MarathonEntitlement>,
    /**
     * TS type is the literal `null`. It is held as raw JSON so that when the server starts
     * sending a diet entitlement object, this build still decodes the session instead of crashing.
     */
    val diet: JsonElement? = null,
)

/**
 * Derived view used for layout decisions (PRD §3 matrix, §6.1 hero priority). Computed on the
 * server from [Entitlements], so the app and backend never disagree.
 */
@Serializable(with = UserPersona.Serializer::class)
enum class UserPersona {
    FREE, YOGA_SUBSCRIBER, MARATHON_REGISTRANT, BOTH, YOGA_EXPIRED, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<UserPersona>(entries, UNKNOWN)
}

@Serializable
data class PersonaInfo(
    val persona: UserPersona,
    val hasYoga: Boolean,
    val hasMarathon: Boolean,
    val label: String,
)

// ─────────────────────────────────────────────────────────────────────────────
// User
// ─────────────────────────────────────────────────────────────────────────────

/** The five goals offered in the design's onboarding step 3, in its order. */
@Serializable(with = HealthGoal.Serializer::class)
enum class HealthGoal {
    WEIGHT_LOSS, STRENGTH_FLEXIBILITY, STRESS_ANXIETY, MARATHON_TRAINING, CONSISTENCY, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<HealthGoal>(entries, UNKNOWN)
}

/**
 * Drives Home feed rail ordering (PRD §5 step 4, §6.3). It only orders rails and never hides one.
 * Exactly the six options in the design's onboarding step 4.
 */
@Serializable(with = Concern.Serializer::class)
enum class Concern {
    LOWER_BACK, KNEES_JOINTS, NECK_SHOULDERS, HIPS_PELVIS, SLEEP_ENERGY, NONE, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<Concern>(entries, UNKNOWN)
}

/** TS inline union `'METRIC' | 'IMPERIAL'` (UserProfile.units, UpdateProfileRequest.units). */
@Serializable(with = Units.Serializer::class)
enum class Units {
    METRIC, IMPERIAL, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<Units>(entries, UNKNOWN)
}

@Serializable
data class UserProfile(
    val id: String,
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    /**
     * True when [email] or [phone] is the sign-in identifier itself, verified by the identity
     * provider. Those change through the login provider, not by editing the profile, so the app
     * shows them read-only.
     */
    val emailIsLogin: Boolean,
    val phoneIsLogin: Boolean,
    val dob: String? = null,
    /** Free string in the response. Requests restrict it to [Gender] values. */
    val gender: String? = null,
    val healthGoal: HealthGoal? = null,
    val concern: Concern? = null,
    /** 0–100, shown in the profile drawer. */
    val profileCompletion: Int,
    val onboardingCompleted: Boolean,
    val units: Units,
    val locale: String,
)

// ─────────────────────────────────────────────────────────────────────────────
// Yoga: PRD §7
// ─────────────────────────────────────────────────────────────────────────────

/** TS inline union on [YogaBatch.period]. */
@Serializable(with = BatchPeriod.Serializer::class)
enum class BatchPeriod {
    MORNING, EVENING, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<BatchPeriod>(entries, UNKNOWN)
}

@Serializable
data class YogaBatch(
    val id: String,
    val title: String,
    /** Local time of day, "HH:mm", IST. The client combines it with the date. */
    val time: String,
    val period: BatchPeriod,
    val instructorName: String,
    /** True for the user's chosen reminder slot (§7.1). */
    val isUserReminderSlot: Boolean,
    /** Resolved against server time, not the device clock. */
    val isLiveNow: Boolean,
    val startsAt: String,
    val endsAt: String,
)

/** The six category banner themes used by the design. */
@Serializable(with = BannerTheme.Serializer::class)
enum class BannerTheme {
    PLUM, CORAL, AMBER, OCEAN, EMERALD, SAGE, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<BannerTheme>(entries, UNKNOWN)
}

@Serializable
data class YogaCategory(
    val id: String,
    val name: String,
    val tagline: String,
    val bodyTargetSummary: String,
    val imageUrl: String,
    /** Selects a gradient. See packages/config/design-tokens.ts `categoryGradients`. */
    val bannerTheme: BannerTheme,
    val sessionCount: Int,
    val totalYogisJoined: Int,
)

@Serializable
data class YogaSession(
    val id: String,
    val categoryId: String,
    val title: String,
    val description: String,
    val imageUrl: String,
    val durationMinutes: Int,
    val level: String,
    val intensity: String,
    val caloriesBurned: Int,
    /** Free sessions are playable by anyone (§6.3 rail 2). */
    val isFree: Boolean,
    val isLive: Boolean,
    val startsAt: String? = null,
    /** Therapeutic metadata, giving the design's session explorer its depth. */
    val bodyFocusTitle: String,
    val targetBodyParts: List<String> = emptyList(),
    val keyPoses: List<String> = emptyList(),
    val lifestyleImpact: String,
    val instructor: Instructor,
    val joinedCountTillDate: Int,
    val todayActiveCount: Int,
    /** Null unless the caller is entitled. A short-TTL signed URL (see §E2). */
    val playbackUrl: String? = null,
)

@Serializable
data class Instructor(
    val id: String,
    val name: String,
    val title: String,
    val specialty: String,
    val bio: String,
    val experience: String,
    val avatarUrl: String,
    val rating: Double,
    /** Social handle for the instructor reels rail (§6.3 rail 4). */
    val handle: String? = null,
    val reelCount: Int,
)

/** PRD §7.1 Tracker. The server computes every value from the single attendance ledger. */
@Serializable
data class YogaAttendance(
    /** ISO dates (YYYY-MM-DD) the user attended a live session. */
    val attendedDates: List<String>,
    val classesAttended: Int,
    val currentStreak: Int,
    /** Named in §7.1, absent from the design. Built here. */
    val bestStreak: Int,
    /** 0–100. Also named in §7.1 and absent from the design. */
    val attendanceRate: Int,
    /**
     * YYYY-MM-DD (IST) the subscription began. Days from here up to yesterday without a mark
     * are "missed" on the calendar (§7.1 present/absent). Days before it are simply not part
     * of the membership.
     */
    val trackingSince: String? = null,
    /**
     * Forward view of scheduled sessions (§7.1): the next few batch starts, rolling into
     * tomorrow once today's are over.
     */
    val upcomingSessions: List<UpcomingYogaSession> = emptyList(),
)

/** One entry of [YogaAttendance.upcomingSessions] (inline object in TS). */
@Serializable
data class UpcomingYogaSession(
    /** YYYY-MM-DD (IST). */
    val date: String,
    val batchId: String,
    val title: String,
    /** ISO 8601. */
    val startsAt: String,
)

/** How an attendance mark reached the ledger. Single-source requirement, §7.1. */
@Serializable(with = AttendanceSource.Serializer::class)
enum class AttendanceSource {
    APP, WHATSAPP, WEB, MANUAL, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<AttendanceSource>(entries, UNKNOWN)
}

// ─────────────────────────────────────────────────────────────────────────────
// Live workshops: present in the design, absent from the PRD. In scope by
// explicit decision. A fourth commercial model: paid and capacity-limited.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable(with = WorkshopCategory.Serializer::class)
enum class WorkshopCategory {
    YOGA, MARATHON, DIET, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<WorkshopCategory>(entries, UNKNOWN)
}

@Serializable
data class LiveWorkshop(
    val id: String,
    val title: String,
    val description: String,
    val category: WorkshopCategory,
    val focusArea: String,
    val imageUrl: String,
    val startsAt: String,
    val durationMinutes: Int,
    val level: String,
    val platform: String,
    val instructorName: String,
    val instructorTitle: String,
    val instructorAvatarUrl: String,
    /** Null means free for the caller's entitlement (design: "Free for Members"). */
    val pricePaise: Long? = null,
    val spotsRemaining: Int,
    val totalCapacity: Int,
    val isRegistered: Boolean,
    /**
     * The caller paid for their seat. A paid seat is never cancelled in-app, because that
     * would forfeit the money. It goes through support, where refunds live.
     */
    val paidSeat: Boolean,
    /** Only populated for registered users, close to the start time. */
    val joinUrl: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// Marathon: PRD §8
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class RaceDistanceOption(
    /** "3K" | "5K" | "10K" | "21K". */
    val code: String,
    val label: String,
    /**
     * TS `Record<RaceTier, number>`: a price for every tier. A tier this build doesn't know maps
     * to the [RaceTier.UNKNOWN] key and is ignored by callers that look up CLASSIC or PREMIUM.
     */
    val pricePaise: Map<RaceTier, Long>,
    val wasPricePaise: Map<RaceTier, Long>? = null,
    /** §8.3 edge case: suppresses the upgrade banner rather than making it fail. */
    val premiumSoldOut: Boolean,
    val registrationOpen: Boolean,
)

@Serializable
data class MarathonEvent(
    val id: String,
    val name: String,
    val city: String,
    val venue: String,
    val imageUrl: String,
    val startsAt: String,
    val flagOffTime: String,
    val distanceOptions: List<RaceDistanceOption>,
    /** §8.3 edge case: the box shows a closed state with no register action. */
    val registrationOpen: Boolean,
    /** §8.3 edge case: the web already handles this, and the app consumes it. */
    val rescheduledFrom: String? = null,
    val expo: RaceExpoInfo? = null,
    /** Set when the caller holds a registration for this edition. */
    val registration: MarathonEntitlement? = null,
    /** Distance from the user, in km. Null when location is unavailable (§8.2). */
    val distanceFromUserKm: Double? = null,
)

@Serializable
data class RaceExpoInfo(
    val venue: String,
    val address: String,
    val startsAt: String,
    val endsAt: String,
    val pickupWindow: String,
    val instructions: String,
    val requiredDocuments: List<String> = emptyList(),
)

/** §8.3. Server-signed with a short TTL, never a bare bib number. See docs/04. */
@Serializable
data class DigitalBib(
    val bibNumber: String,
    val participantName: String,
    val category: String,
    val tier: RaceTier,
    val eventName: String,
    /** Signed token to render as a QR code. It expires, so the client must refresh it. */
    val qrToken: String,
    val qrExpiresAt: String,
    /** Cached for offline display at the venue. Safe to persist. */
    val offlinePayload: String,
)

/** §8.4. Only returned once the timing partner has published. */
@Serializable
data class RaceResult(
    val published: Boolean,
    val finishTime: String? = null,
    val chipTime: String? = null,
    val avgPace: String? = null,
    val overallRank: Int? = null,
    val ageGroupRank: Int? = null,
    val category: String,
    val splits: List<RaceSplit> = emptyList(),
    val certificateUrl: String? = null,
    val medalStatus: String? = null,
    val photoUrls: List<String> = emptyList(),
)

/** One entry of [RaceResult.splits] (inline object in TS). */
@Serializable
data class RaceSplit(val label: String, val time: String)

/** §8.3. 1 referral = a draw entry; 5 = a guaranteed Premium upgrade. */
@Serializable
data class ReferralState(
    val code: String,
    val shareUrl: String,
    /** Counts only completed paid registrations. See docs/04 T2. */
    val confirmedReferrals: Int,
    val luckyDrawEntries: Int,
    val guaranteedUpgradeUnlocked: Boolean,
    /** The earned free upgrade has been used. It can be claimed only once. */
    val upgradeClaimed: Boolean,
)

/** TS inline union on [KitDelivery.status]. */
@Serializable(with = KitStatus.Serializer::class)
enum class KitStatus {
    NOT_DISPATCHED, IN_TRANSIT, DELIVERED, PICKUP_ONLY, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<KitStatus>(entries, UNKNOWN)
}

/** Design-only addition: kit courier tracking. */
@Serializable
data class KitDelivery(
    val status: KitStatus,
    val courierName: String? = null,
    val trackingRef: String? = null,
    val tshirtSize: String? = null,
    val expectedBy: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// Run tracker: PRD §8.6. Free for everyone.
// ─────────────────────────────────────────────────────────────────────────────

/** Client-side tracker state. Not sent over the wire; kept for parity with the TS. */
@Serializable(with = RunTrackerState.Serializer::class)
enum class RunTrackerState {
    READY, RUNNING, PAUSED, SUMMARY, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<RunTrackerState>(entries, UNKNOWN)
}

/** A raw GPS fix recorded on the device. Not sent over the wire; runs upload a polyline. */
@Serializable
data class GeoPoint(
    val lat: Double,
    val lng: Double,
    /** Metres. Points above the accuracy gate are dropped, not averaged in. */
    val accuracy: Double,
    val altitude: Double? = null,
    val speed: Double? = null,
    /** Epoch milliseconds. */
    val timestamp: Long,
)

@Serializable
data class RunRecord(
    val id: String,
    val startedAt: String,
    val endedAt: String,
    val distanceKm: Double,
    val durationSeconds: Int,
    val avgPaceSecPerKm: Int,
    val caloriesBurned: Int,
    /** Simplified polyline. Private by default and never exposed to other users. */
    val routePolyline: String? = null,
    /** True when poor GPS quality forced a degraded recording (§8.6). */
    val hasAccuracyWarning: Boolean,
    /** False until the device has synced it. Runs are created locally first. */
    val synced: Boolean,
)

// ─────────────────────────────────────────────────────────────────────────────
// Diet: PRD §9. Lead capture only.
// ─────────────────────────────────────────────────────────────────────────────

/** Also the body of POST /diet/leads ([DietLeadRequest]). */
@Serializable
data class DietLead(
    val name: String,
    val phone: String,
    val condition: String? = null,
    val cuisinePreference: String? = null,
    val bestTimeToCall: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// Content: PRD §6.3
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class Article(
    val id: String,
    val title: String,
    val source: String,
    val category: String,
    val imageUrl: String,
    val readTimeMinutes: Int,
    val url: String,
)

@Serializable
data class Reel(
    val id: String,
    val instructorId: String,
    val instructorName: String,
    val instructorHandle: String,
    val thumbnailUrl: String,
    val playbackUrl: String,
    val durationSeconds: Int,
)

@Serializable
data class UserQuote(
    val id: String,
    val quote: String,
    val author: String,
    val role: String,
)
