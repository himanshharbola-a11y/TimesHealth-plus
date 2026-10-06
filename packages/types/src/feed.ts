/**
 * Server-driven Home feed — PRD §6.
 *
 * The server returns an ordered list of typed components; the client renders
 * the types it knows and SILENTLY SKIPS the ones it does not. That skip rule is
 * what lets us ship new rails, reorder the feed, swap the promo or suppress a
 * sold-out edition without an app release — and it is what makes PRD §13's cut
 * list (concern ordering, Refer & Win placement, promo strip) config changes
 * rather than code changes.
 *
 * Rule: never add a required field to an existing component type. Add a new
 * type instead, or make the field optional. Old clients live for years.
 */

import type {
  Article,
  LiveWorkshop,
  Reel,
  UserQuote,
  YogaSession,
} from './domain';

// ─────────────────────────────────────────────────────────────────────────────
// Hero — PRD §6.1. Two slots, same component, priority-resolved server-side.
//
// Priority rule (hard): a yoga subscriber's session always wins slot 1.
// Whatever loses drops to slot 2.
// ─────────────────────────────────────────────────────────────────────────────

export type HeroSlotKind =
  | 'YOGA_SESSION'
  | 'YOGA_RENEW'
  | 'MY_RACE'
  | 'RACE_RESULT'
  | 'SELL_YOGA'
  | 'SELL_MARATHON';

interface HeroSlotBase {
  kind: HeroSlotKind;
  title: string;
  subtitle: string | null;
  ctaLabel: string;
  imageUrl: string | null;
}

/** §6.1 + edge cases: live now, starts-in countdown, or next scheduled day. */
export interface HeroYogaSession extends HeroSlotBase {
  kind: 'YOGA_SESSION';
  sessionId: string;
  batchId: string;
  /** LIVE wins the visually distinct state; join is the primary action. */
  state: 'LIVE' | 'STARTING_SOON' | 'SCHEDULED_TODAY' | 'SCHEDULED_LATER';
  startsAt: string;
  /**
   * Null when the next session is not today (rest day / all batches passed) —
   * the client then shows the day, not a countdown. §6.1 edge case.
   */
  secondsToStart: number | null;
  joinUrl: string | null;
  /** For the hero's instructor row (design: 22dp avatar · "name · batch · 60 min"). */
  instructorName: string;
  instructorAvatarUrl: string | null;
  durationMinutes: number;
}

/** §5 / §6.1: expired subscriber gets a warm re-subscribe, not a cold sell. */
export interface HeroYogaRenew extends HeroSlotBase {
  kind: 'YOGA_RENEW';
  expiredAt: string;
  /** Preserved so the prompt can say the streak is still there. */
  preservedStreak: number;
}

export interface HeroMyRace extends HeroSlotBase {
  kind: 'MY_RACE';
  eventId: string;
  startsAt: string;
  daysRemaining: number;
  bibNumber: string | null;
  /** "21K" — the registered distance. */
  category: string;
  /** "5:30 AM · Wave 2" — shown on race day instead of the countdown. */
  flagOffTime: string;
  /** True on the race's IST calendar day. */
  isRaceDay: boolean;
}

/** §6.1 / §8.4: race day passed → box switches to the result state. */
export interface HeroRaceResult extends HeroSlotBase {
  kind: 'RACE_RESULT';
  eventId: string;
  /** False → pending state, never an empty results screen (§8.4). */
  resultPublished: boolean;
}

export interface HeroSellYoga extends HeroSlotBase {
  kind: 'SELL_YOGA';
  planId: string;
  pricePaise: number;
}

export interface HeroSellMarathon extends HeroSlotBase {
  kind: 'SELL_MARATHON';
  eventId: string;
  fromPricePaise: number;
  city: string;
  startsAt: string;
}

export type HeroSlot =
  | HeroYogaSession
  | HeroYogaRenew
  | HeroMyRace
  | HeroRaceResult
  | HeroSellYoga
  | HeroSellMarathon;

// ─────────────────────────────────────────────────────────────────────────────
// Feed components
// ─────────────────────────────────────────────────────────────────────────────

/** The six card templates named in PRD §6.3. */
export type CardFormat =
  | 'VIDEO_LANDSCAPE'
  | 'REEL_PORTRAIT'
  | 'ARTICLE_WIDE'
  | 'QUOTE_SQUARE'
  | 'ENTRY_TILE'
  | 'WORKSHOP_CARD';

export interface HeroStackComponent {
  type: 'HERO_STACK';
  id: string;
  /** 1 or 2 entries. Slot 1 first. */
  slots: HeroSlot[];
}

export interface VideoRailComponent {
  type: 'VIDEO_RAIL';
  id: string;
  /** Heading swaps by concern — "Sessions for lower back relief" (§6.3 rail 1). */
  title: string;
  cardFormat: 'VIDEO_LANDSCAPE';
  items: YogaSession[];
  seeAllCategoryId: string | null;
  /** Header action label — "See all" (concern rail), "Explore" (free rail). Null → none. */
  actionLabel: string | null;
}

export interface ReelRailComponent {
  type: 'REEL_RAIL';
  id: string;
  title: string;
  cardFormat: 'REEL_PORTRAIT';
  items: Reel[];
}

export interface ArticleRailComponent {
  type: 'ARTICLE_RAIL';
  id: string;
  title: string;
  cardFormat: 'ARTICLE_WIDE';
  items: Article[];
}

/** §6.3 rail 6: read-only. No link, no video, no tap action. */
export interface QuoteRailComponent {
  type: 'QUOTE_RAIL';
  id: string;
  title: string;
  cardFormat: 'QUOTE_SQUARE';
  items: UserQuote[];
}

export interface WorkshopRailComponent {
  type: 'WORKSHOP_RAIL';
  id: string;
  title: string;
  cardFormat: 'WORKSHOP_CARD';
  items: LiveWorkshop[];
}

/** §6.3 rail 3: not a scrolling rail. One tile, opens the tracker directly. */
export interface EntryTileComponent {
  type: 'ENTRY_TILE';
  id: string;
  title: string;
  subtitle: string;
  imageUrl: string | null;
  action: FeedAction;
}

/**
 * §6.2. Fully server-controlled. Narrower and shorter than the hero,
 * deliberately ad-like, sits AFTER the first content rail.
 * Omitting it entirely is valid and must not break the layout.
 */
export interface PromoStripComponent {
  type: 'PROMO_STRIP';
  id: string;
  campaignId: string;
  title: string;
  subtitle: string | null;
  ctaLabel: string;
  imageUrl: string | null;
  backgroundColor: string | null;
  action: FeedAction;
}

export type FeedComponent =
  | HeroStackComponent
  | VideoRailComponent
  | ReelRailComponent
  | ArticleRailComponent
  | QuoteRailComponent
  | WorkshopRailComponent
  | EntryTileComponent
  | PromoStripComponent;

export type FeedComponentType = FeedComponent['type'];

// ─────────────────────────────────────────────────────────────────────────────
// Actions — the only navigation vocabulary the server may use.
// ─────────────────────────────────────────────────────────────────────────────

export type FeedAction =
  | { type: 'OPEN_TAB'; tab: 'HOME' | 'YOGA' | 'MARATHON' | 'DIET' }
  | { type: 'OPEN_RUN_TRACKER' }
  | { type: 'OPEN_YOGA_SESSION'; sessionId: string }
  | { type: 'OPEN_YOGA_EXPLORER'; categoryId: string | null }
  | { type: 'JOIN_LIVE_SESSION'; sessionId: string; batchId: string }
  | { type: 'OPEN_RACE_DETAIL'; eventId: string }
  | { type: 'OPEN_RACE_RESULTS'; eventId: string }
  | { type: 'OPEN_DIGITAL_BIB'; eventId: string }
  | { type: 'OPEN_WORKSHOP'; workshopId: string }
  | { type: 'OPEN_PAYWALL'; productId: string }
  | { type: 'OPEN_DIET_LEAD_FORM' }
  | { type: 'OPEN_ARTICLE'; url: string }
  | { type: 'OPEN_EXTERNAL'; url: string };

// ─────────────────────────────────────────────────────────────────────────────
// Response
// ─────────────────────────────────────────────────────────────────────────────

export interface HomeFeedResponse {
  /** Greeting line — "Good morning · Thursday". Server-resolved for locale. */
  greeting: string;
  userName: string | null;
  components: FeedComponent[];
  /**
   * Authoritative clock. Countdowns tick locally from this, never from the
   * device clock, and never by polling. §6.1.
   */
  serverTime: string;
  /** Cache hint for the client. The CDN caches the segment; this is the overlay. */
  ttlSeconds: number;
}
