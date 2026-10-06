/*
 * Server-driven Home feed: PRD §6. Mirrors packages/types/src/feed.ts; keep the two in step.
 *
 * The server returns an ordered list of typed components. The client renders the types it knows
 * and SILENTLY SKIPS the ones it doesn't. That skip rule lets the server ship new rails, reorder
 * the feed, swap the promo or suppress a sold-out edition without an app release. It is also
 * what makes PRD §13's cut list (concern ordering, Refer & Win placement, promo strip) a config
 * change rather than a code change.
 *
 * Rule: never add a required field to an existing component type. Add a new type instead, or
 * make the field optional. Old clients live for years.
 *
 * In Kotlin, each discriminated union is a sealed interface with an `Unknown` variant. A
 * discriminator this build doesn't know decodes to `Unknown`, never an exception; see
 * [ForwardCompatibleUnionSerializer]. Renderers should use [HomeFeedResponse.knownComponents]
 * and [HeroStackComponent.knownSlots], which drop the unknowns exactly as the RN renderer does
 * (apps/mobile/src/components/feed/FeedRenderer.tsx and Hero.tsx).
 */
package timeshealth.app.core.model

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// Hero: PRD §6.1. Two slots using the same component, priority-resolved
// server-side.
//
// Priority rule (hard): a yoga subscriber's session always wins slot 1.
// Whatever loses drops to slot 2.
// ─────────────────────────────────────────────────────────────────────────────

/** The `kind` discriminator of [HeroSlot]. */
enum class HeroSlotKind {
    YOGA_SESSION, YOGA_RENEW, MY_RACE, RACE_RESULT, SELL_YOGA, SELL_MARATHON, UNKNOWN,
}

/** TS `HeroSlot`: a union discriminated by `"kind"`. Render only [KnownHeroSlot]s. */
@Serializable(with = HeroSlotSerializer::class)
sealed interface HeroSlot {
    val kind: HeroSlotKind

    /** A slot kind from a newer server. Skip it, as RN does; never render an empty card. */
    data class Unknown(val rawKind: String?) : HeroSlot {
        override val kind: HeroSlotKind get() = HeroSlotKind.UNKNOWN
    }
}

/** Every slot this build can render. Carries TS `HeroSlotBase`'s shared fields. */
sealed interface KnownHeroSlot : HeroSlot {
    val title: String
    val subtitle: String?
    val ctaLabel: String
    val imageUrl: String?
}

/** TS inline union on [HeroYogaSession.state]. LIVE gets the visually distinct state. */
@Serializable(with = HeroSessionState.Serializer::class)
enum class HeroSessionState {
    LIVE, STARTING_SOON, SCHEDULED_TODAY, SCHEDULED_LATER, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<HeroSessionState>(entries, UNKNOWN)
}

/** §6.1 and its edge cases: live now, a starts-in countdown, or the next scheduled day. */
@Serializable
data class HeroYogaSession(
    override val title: String,
    override val subtitle: String? = null,
    override val ctaLabel: String,
    override val imageUrl: String? = null,
    val sessionId: String,
    val batchId: String,
    /** LIVE wins the visually distinct state, and join is the primary action. */
    val state: HeroSessionState,
    val startsAt: String,
    /**
     * Null when the next session is not today (a rest day, or all batches have passed). The
     * client then shows the day instead of a countdown. §6.1 edge case.
     */
    val secondsToStart: Int? = null,
    val joinUrl: String? = null,
    /** For the hero's instructor row (design: 22dp avatar · "name · batch · 60 min"). */
    val instructorName: String,
    val instructorAvatarUrl: String? = null,
    val durationMinutes: Int,
) : KnownHeroSlot {
    override val kind: HeroSlotKind get() = HeroSlotKind.YOGA_SESSION
}

/** §5 / §6.1: an expired subscriber gets a warm re-subscribe prompt, not a cold sell. */
@Serializable
data class HeroYogaRenew(
    override val title: String,
    override val subtitle: String? = null,
    override val ctaLabel: String,
    override val imageUrl: String? = null,
    val expiredAt: String,
    /** Kept so the prompt can say the streak is still there. */
    val preservedStreak: Int,
) : KnownHeroSlot {
    override val kind: HeroSlotKind get() = HeroSlotKind.YOGA_RENEW
}

@Serializable
data class HeroMyRace(
    override val title: String,
    override val subtitle: String? = null,
    override val ctaLabel: String,
    override val imageUrl: String? = null,
    val eventId: String,
    val startsAt: String,
    val daysRemaining: Int,
    val bibNumber: String? = null,
    /** "21K": the registered distance. */
    val category: String,
    /** "5:30 AM · Wave 2". Shown on race day instead of the countdown. */
    val flagOffTime: String,
    /** True on the race's IST calendar day. */
    val isRaceDay: Boolean,
) : KnownHeroSlot {
    override val kind: HeroSlotKind get() = HeroSlotKind.MY_RACE
}

/** §6.1 / §8.4: once race day has passed, the box switches to the result state. */
@Serializable
data class HeroRaceResult(
    override val title: String,
    override val subtitle: String? = null,
    override val ctaLabel: String,
    override val imageUrl: String? = null,
    val eventId: String,
    /** False shows the pending state, never an empty results screen (§8.4). */
    val resultPublished: Boolean,
) : KnownHeroSlot {
    override val kind: HeroSlotKind get() = HeroSlotKind.RACE_RESULT
}

@Serializable
data class HeroSellYoga(
    override val title: String,
    override val subtitle: String? = null,
    override val ctaLabel: String,
    override val imageUrl: String? = null,
    val planId: String,
    val pricePaise: Long,
) : KnownHeroSlot {
    override val kind: HeroSlotKind get() = HeroSlotKind.SELL_YOGA
}

@Serializable
data class HeroSellMarathon(
    override val title: String,
    override val subtitle: String? = null,
    override val ctaLabel: String,
    override val imageUrl: String? = null,
    val eventId: String,
    val fromPricePaise: Long,
    val city: String,
    val startsAt: String,
) : KnownHeroSlot {
    override val kind: HeroSlotKind get() = HeroSlotKind.SELL_MARATHON
}

internal object HeroSlotSerializer : ForwardCompatibleUnionSerializer<HeroSlot>(
    serialName = "HeroSlot",
    discriminator = "kind",
    variants = listOf(
        variant(HeroSlotKind.YOGA_SESSION.name, HeroYogaSession.serializer()),
        variant(HeroSlotKind.YOGA_RENEW.name, HeroYogaRenew.serializer()),
        variant(HeroSlotKind.MY_RACE.name, HeroMyRace.serializer()),
        variant(HeroSlotKind.RACE_RESULT.name, HeroRaceResult.serializer()),
        variant(HeroSlotKind.SELL_YOGA.name, HeroSellYoga.serializer()),
        variant(HeroSlotKind.SELL_MARATHON.name, HeroSellMarathon.serializer()),
    ),
) {
    override fun toUnknown(tag: String?): HeroSlot = HeroSlot.Unknown(tag)
    override fun unknownTag(value: HeroSlot): String? = (value as? HeroSlot.Unknown)?.rawKind
}

// ─────────────────────────────────────────────────────────────────────────────
// Feed components
// ─────────────────────────────────────────────────────────────────────────────

/** The six card templates named in PRD §6.3. */
@Serializable(with = CardFormat.Serializer::class)
enum class CardFormat {
    VIDEO_LANDSCAPE, REEL_PORTRAIT, ARTICLE_WIDE, QUOTE_SQUARE, ENTRY_TILE, WORKSHOP_CARD, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<CardFormat>(entries, UNKNOWN)
}

/** The `type` discriminator of [FeedComponent] (TS `FeedComponentType`). */
enum class FeedComponentType {
    HERO_STACK, VIDEO_RAIL, REEL_RAIL, ARTICLE_RAIL, QUOTE_RAIL, WORKSHOP_RAIL, ENTRY_TILE, PROMO_STRIP, UNKNOWN,
}

/** TS `FeedComponent`: a union discriminated by `"type"`. Render only [KnownFeedComponent]s. */
@Serializable(with = FeedComponentSerializer::class)
sealed interface FeedComponent {
    val type: FeedComponentType

    /** A component type from a newer server. Skip it silently, as RN does. */
    data class Unknown(val rawType: String?) : FeedComponent {
        override val type: FeedComponentType get() = FeedComponentType.UNKNOWN
    }
}

/** Every component this build can render. */
sealed interface KnownFeedComponent : FeedComponent {
    /** Stable across refreshes. Use it as the list key. */
    val id: String
}

@Serializable
data class HeroStackComponent(
    override val id: String,
    /** 1 or 2 entries, slot 1 first. May include [HeroSlot.Unknown]; render [knownSlots]. */
    val slots: List<HeroSlot> = emptyList(),
) : KnownFeedComponent {
    override val type: FeedComponentType get() = FeedComponentType.HERO_STACK

    /**
     * [slots] without kinds this build doesn't know, in server order. The first entry is the
     * primary hero and the second the secondary card. If this is empty, render nothing.
     */
    val knownSlots: List<KnownHeroSlot> get() = slots.knownOnly()
}

@Serializable
data class VideoRailComponent(
    override val id: String,
    /** The heading changes with the concern, e.g. "Sessions for lower back relief" (§6.3 rail 1). */
    val title: String,
    val cardFormat: CardFormat = CardFormat.VIDEO_LANDSCAPE,
    val items: List<YogaSession> = emptyList(),
    val seeAllCategoryId: String? = null,
    /**
     * Header action label: "See all" for the concern rail, "Explore" for the free rail. Null
     * means no action. RN treats a missing key (a pre-actionLabel server) as "See all" when
     * [seeAllCategoryId] is set. Kotlin reads missing as null; current servers always send it.
     */
    val actionLabel: String? = null,
) : KnownFeedComponent {
    override val type: FeedComponentType get() = FeedComponentType.VIDEO_RAIL
}

@Serializable
data class ReelRailComponent(
    override val id: String,
    val title: String,
    val cardFormat: CardFormat = CardFormat.REEL_PORTRAIT,
    val items: List<Reel> = emptyList(),
) : KnownFeedComponent {
    override val type: FeedComponentType get() = FeedComponentType.REEL_RAIL
}

@Serializable
data class ArticleRailComponent(
    override val id: String,
    val title: String,
    val cardFormat: CardFormat = CardFormat.ARTICLE_WIDE,
    val items: List<Article> = emptyList(),
) : KnownFeedComponent {
    override val type: FeedComponentType get() = FeedComponentType.ARTICLE_RAIL
}

/** §6.3 rail 6: read-only. No link, no video, no tap action. */
@Serializable
data class QuoteRailComponent(
    override val id: String,
    val title: String,
    val cardFormat: CardFormat = CardFormat.QUOTE_SQUARE,
    val items: List<UserQuote> = emptyList(),
) : KnownFeedComponent {
    override val type: FeedComponentType get() = FeedComponentType.QUOTE_RAIL
}

@Serializable
data class WorkshopRailComponent(
    override val id: String,
    val title: String,
    val cardFormat: CardFormat = CardFormat.WORKSHOP_CARD,
    val items: List<LiveWorkshop> = emptyList(),
) : KnownFeedComponent {
    override val type: FeedComponentType get() = FeedComponentType.WORKSHOP_RAIL
}

/** §6.3 rail 3: not a scrolling rail. A single tile that opens the tracker directly. */
@Serializable
data class EntryTileComponent(
    override val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String? = null,
    val action: FeedAction,
) : KnownFeedComponent {
    override val type: FeedComponentType get() = FeedComponentType.ENTRY_TILE
}

/**
 * §6.2. Fully server-controlled. Narrower and shorter than the hero, deliberately ad-like, and
 * placed AFTER the first content rail. Omitting it entirely is valid and must not break the
 * layout.
 */
@Serializable
data class PromoStripComponent(
    override val id: String,
    val campaignId: String,
    val title: String,
    val subtitle: String? = null,
    val ctaLabel: String,
    val imageUrl: String? = null,
    /** CSS hex colour, e.g. "#1B4931". */
    val backgroundColor: String? = null,
    val action: FeedAction,
) : KnownFeedComponent {
    override val type: FeedComponentType get() = FeedComponentType.PROMO_STRIP
}

internal object FeedComponentSerializer : ForwardCompatibleUnionSerializer<FeedComponent>(
    serialName = "FeedComponent",
    discriminator = "type",
    variants = listOf(
        variant(FeedComponentType.HERO_STACK.name, HeroStackComponent.serializer()),
        variant(FeedComponentType.VIDEO_RAIL.name, VideoRailComponent.serializer()),
        variant(FeedComponentType.REEL_RAIL.name, ReelRailComponent.serializer()),
        variant(FeedComponentType.ARTICLE_RAIL.name, ArticleRailComponent.serializer()),
        variant(FeedComponentType.QUOTE_RAIL.name, QuoteRailComponent.serializer()),
        variant(FeedComponentType.WORKSHOP_RAIL.name, WorkshopRailComponent.serializer()),
        variant(FeedComponentType.ENTRY_TILE.name, EntryTileComponent.serializer()),
        variant(FeedComponentType.PROMO_STRIP.name, PromoStripComponent.serializer()),
    ),
) {
    override fun toUnknown(tag: String?): FeedComponent = FeedComponent.Unknown(tag)
    override fun unknownTag(value: FeedComponent): String? = (value as? FeedComponent.Unknown)?.rawType
}

// ─────────────────────────────────────────────────────────────────────────────
// Actions: the only navigation vocabulary the server may use.
// ─────────────────────────────────────────────────────────────────────────────

/** TS inline union on [FeedAction.OpenTab.tab]. */
@Serializable(with = AppTab.Serializer::class)
enum class AppTab {
    HOME, YOGA, MARATHON, DIET, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<AppTab>(entries, UNKNOWN)
}

/**
 * TS `FeedAction`: a union discriminated by `"type"`.
 *
 * For [Unknown] (or an [OpenTab] with [AppTab.UNKNOWN]), RN navigates to Home. That is the safe
 * landing rather than a tap that does nothing (apps/mobile/app/(tabs)/index.tsx). Do the same.
 */
@Serializable(with = FeedActionSerializer::class)
sealed interface FeedAction {
    @Serializable
    data class OpenTab(val tab: AppTab) : FeedAction

    @Serializable
    data object OpenRunTracker : FeedAction

    @Serializable
    data class OpenYogaSession(val sessionId: String) : FeedAction

    @Serializable
    data class OpenYogaExplorer(val categoryId: String? = null) : FeedAction

    @Serializable
    data class JoinLiveSession(val sessionId: String, val batchId: String) : FeedAction

    @Serializable
    data class OpenRaceDetail(val eventId: String) : FeedAction

    @Serializable
    data class OpenRaceResults(val eventId: String) : FeedAction

    @Serializable
    data class OpenDigitalBib(val eventId: String) : FeedAction

    @Serializable
    data class OpenWorkshop(val workshopId: String) : FeedAction

    @Serializable
    data class OpenPaywall(val productId: String) : FeedAction

    @Serializable
    data object OpenDietLeadForm : FeedAction

    @Serializable
    data class OpenArticle(val url: String) : FeedAction

    @Serializable
    data class OpenExternal(val url: String) : FeedAction

    /** An action type from a newer server. Fall back to Home, as RN does. */
    data class Unknown(val rawType: String?) : FeedAction
}

internal object FeedActionSerializer : ForwardCompatibleUnionSerializer<FeedAction>(
    serialName = "FeedAction",
    discriminator = "type",
    variants = listOf(
        variant("OPEN_TAB", FeedAction.OpenTab.serializer()),
        variant("OPEN_RUN_TRACKER", FeedAction.OpenRunTracker.serializer()),
        variant("OPEN_YOGA_SESSION", FeedAction.OpenYogaSession.serializer()),
        variant("OPEN_YOGA_EXPLORER", FeedAction.OpenYogaExplorer.serializer()),
        variant("JOIN_LIVE_SESSION", FeedAction.JoinLiveSession.serializer()),
        variant("OPEN_RACE_DETAIL", FeedAction.OpenRaceDetail.serializer()),
        variant("OPEN_RACE_RESULTS", FeedAction.OpenRaceResults.serializer()),
        variant("OPEN_DIGITAL_BIB", FeedAction.OpenDigitalBib.serializer()),
        variant("OPEN_WORKSHOP", FeedAction.OpenWorkshop.serializer()),
        variant("OPEN_PAYWALL", FeedAction.OpenPaywall.serializer()),
        variant("OPEN_DIET_LEAD_FORM", FeedAction.OpenDietLeadForm.serializer()),
        variant("OPEN_ARTICLE", FeedAction.OpenArticle.serializer()),
        variant("OPEN_EXTERNAL", FeedAction.OpenExternal.serializer()),
    ),
) {
    override fun toUnknown(tag: String?): FeedAction = FeedAction.Unknown(tag)
    override fun unknownTag(value: FeedAction): String? = (value as? FeedAction.Unknown)?.rawType
}

// ─────────────────────────────────────────────────────────────────────────────
// Response
// ─────────────────────────────────────────────────────────────────────────────

/** GET /home. */
@Serializable
data class HomeFeedResponse(
    /** Greeting line, e.g. "Good morning · Thursday". Resolved by the server for the locale. */
    val greeting: String,
    val userName: String? = null,
    /** In server order. May include [FeedComponent.Unknown]; render [knownComponents]. */
    val components: List<FeedComponent> = emptyList(),
    /**
     * Authoritative clock. Countdowns tick locally from this, never from the device clock, and
     * never by polling. §6.1.
     */
    val serverTime: String,
    /** Cache hint for the client. The CDN caches the segment; this is the overlay. */
    val ttlSeconds: Int,
) {
    /** [components] without types this build doesn't know, in server order. What RN renders. */
    val knownComponents: List<KnownFeedComponent> get() = components.knownOnly()
}

/** Drops component types this build doesn't know, keeping server order. */
@JvmName("knownComponentsOnly")
fun List<FeedComponent>.knownOnly(): List<KnownFeedComponent> = filterIsInstance<KnownFeedComponent>()

/** Drops hero slot kinds this build doesn't know, keeping server order. */
@JvmName("knownHeroSlotsOnly")
fun List<HeroSlot>.knownOnly(): List<KnownHeroSlot> = filterIsInstance<KnownHeroSlot>()
