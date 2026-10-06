/**
 * API request/response contracts shared by @th/api and @th/mobile.
 *
 * Changing anything here breaks the build on both sides at compile time, which
 * is the point — the alternative is finding out on a user's phone.
 */

import type {
  Article,
  AttendanceSource,
  Concern,
  DietLead,
  DigitalBib,
  Entitlements,
  HealthGoal,
  Instructor,
  KitDelivery,
  LiveWorkshop,
  MarathonEvent,
  PersonaInfo,
  RaceResult,
  Reel,
  ReferralState,
  ReferralState as Referral,
  RunRecord,
  UserProfile,
  UserQuote,
  YogaAttendance,
  YogaBatch,
  YogaCategory,
  YogaSession,
} from './domain';

// ─────────────────────────────────────────────────────────────────────────────
// Envelope
// ─────────────────────────────────────────────────────────────────────────────

export interface ApiError {
  code: string;
  message: string;
  /** Present on 4xx validation failures. */
  fields?: Record<string, string[]>;
}

/** Thin wrapper so clients can branch without inspecting HTTP status codes. */
export type ApiResult<T> =
  | { ok: true; data: T }
  | { ok: false; error: ApiError };

// ─────────────────────────────────────────────────────────────────────────────
// Session / bootstrap
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Called once on launch, after the Firebase ID token is available.
 * Resolves identity (PRD §5: phone on app, email on web → one account) and
 * returns everything needed to decide the first screen.
 */
export interface SessionResponse {
  profile: UserProfile;
  entitlements: Entitlements;
  persona: PersonaInfo;
  /** False → onboarding. Returning subscribers never see it again (§5). */
  needsOnboarding: boolean;
  serverTime: string;
  /** Hard gate. The client refuses to run below this (see docs/05 §1). */
  minSupportedAppVersion: string;
  /** Server-triggered maintenance screen. */
  maintenance: { active: boolean; message: string | null };
}

/**
 * Public, unauthenticated, checked on EVERY launch before sign-in. The update
 * gate has to work even when an old version's login is what broke — which is
 * exactly when you need to force people off it.
 */
export interface AppConfigResponse {
  minSupportedAppVersion: string;
  maintenance: { active: boolean; message: string | null };
  serverTime: string;
}

// ─────────────────────────────────────────────────────────────────────────────
// Onboarding — PRD §5. Every step skippable, progress saved as it goes so a
// partial drop still leaves usable lead data.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * PATCH /profile (§10 personal details). The sign-in email/phone can't be
 * changed here (409 LOGIN_IDENTIFIER) — see UserProfile.emailIsLogin.
 */
export interface UpdateProfileRequest {
  name?: string;
  email?: string;
  phone?: string;
  /** ISO datetime; age 13–100. */
  dob?: string;
  gender?: 'FEMALE' | 'MALE' | 'NON_BINARY' | 'PREFER_NOT_TO_SAY';
  units?: 'METRIC' | 'IMPERIAL';
  locale?: string;
  healthGoal?: HealthGoal;
  concern?: Concern;
}

export interface OnboardingStepRequest {
  step: 1 | 2 | 3 | 4;
  name?: string;
  email?: string;
  phone?: string;
  healthGoal?: HealthGoal;
  concern?: Concern;
}

// ─────────────────────────────────────────────────────────────────────────────
// Yoga
// ─────────────────────────────────────────────────────────────────────────────

export interface YogaTodayResponse {
  /** All 8 daily batches; the user's reminder slot is flagged (§7.1). */
  batches: YogaBatch[];
  liveBatchId: string | null;
  nextBatchId: string | null;
  /** Null when there is no session today — rest day or all batches passed. */
  nextSessionStartsAt: string | null;
}

export interface JoinSessionRequest {
  batchId: string;
}

/**
 * Returns the join target AND writes the attendance mark. The write happens
 * server-side on this call, not from a client assertion — see docs/04 T9.
 */
export interface JoinSessionResponse {
  joinUrl: string;
  /** How the app should open it. */
  mode: 'EXTERNAL_APP' | 'WEBVIEW' | 'IN_APP_PLAYER';
  attendanceRecorded: boolean;
  source: AttendanceSource;
}

export interface YogaCatalogResponse {
  categories: YogaCategory[];
  sessions: YogaSession[];
}

export interface SetReminderSlotRequest {
  batchId: string;
}

// ─────────────────────────────────────────────────────────────────────────────
// Marathon
// ─────────────────────────────────────────────────────────────────────────────

export interface MarathonListResponse {
  /**
   * Ordered per §8.2: registered first, then nearest by location if granted,
   * else next upcoming by date. Ordering is resolved server-side so the
   * fallback is silent and the client never asks for location twice.
   */
  events: MarathonEvent[];
  orderedBy: 'REGISTRATION' | 'LOCATION' | 'DATE';
}

export interface RaceDetailResponse {
  event: MarathonEvent;
  bib: DigitalBib | null;
  result: RaceResult | null;
  kit: KitDelivery | null;
  referral: ReferralState | null;
  /** Suppressed entirely when sold out or already Premium (§8.3 edge cases). */
  upgradeOffer: {
    available: boolean;
    netDifferencePaise: number;
    benefits: string[];
    /** Earned through Refer & Win (5 referrals): claim it free, no payment. */
    freeClaim: boolean;
  } | null;
  faqs: { question: string; answer: string }[];
  /** The user's own race-day details (§10 "where held"); null when not registered. */
  participant: {
    tshirtSize: string | null;
    emergencyContactName: string | null;
    emergencyContactPhone: string | null;
  } | null;
}

export interface UpdateParticipantRequest {
  eventId: string;
  tshirtSize?: string;
  emergencyContactName?: string;
  emergencyContactPhone?: string;
}

// ─────────────────────────────────────────────────────────────────────────────
// Run tracker — PRD §8.6
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Runs are recorded locally first and uploaded after. The client owns `id`
 * (a UUID) so a retry is idempotent and an app killed mid-run loses nothing.
 */
export interface UploadRunRequest {
  id: string;
  startedAt: string;
  endedAt: string;
  distanceKm: number;
  durationSeconds: number;
  avgPaceSecPerKm: number;
  caloriesBurned: number;
  routePolyline: string | null;
  hasAccuracyWarning: boolean;
}

export interface RunHistoryResponse {
  runs: RunRecord[];
  /** Over EVERY run, not just the page listed; "month" is the IST calendar month. */
  totals: {
    runs: number;
    distanceKm: number;
    durationSeconds: number;
    longestKm: number;
    monthDistanceKm: number;
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// Diet / workshops / content
// ─────────────────────────────────────────────────────────────────────────────

export interface DietLeadRequest extends DietLead {}

export interface DietLeadResponse {
  leadId: string;
  message: string;
}

export interface WorkshopListResponse {
  workshops: LiveWorkshop[];
}

export interface ContentResponse {
  articles: Article[];
  quotes: UserQuote[];
  instructors: Instructor[];
  /**
   * Served by the API rather than hard-coded in the app, so an answer that
   * stops being true (see the WhatsApp FAQ, docs/01 §B4) can be corrected
   * without shipping a release.
   */
  yogaFaqs: { question: string; answer: string }[];
  /** PRD §7: "chief (spiritual) mentor". */
  mentor: { name: string; title: string; bio: string; avatarUrl: string };
  /** PRD §7.2: instructor videos on the free Yoga page (same reels as Home). */
  reels: Reel[];
}

// ─────────────────────────────────────────────────────────────────────────────
// Commerce — scaffolded behind a real interface. See docs/04 §5 before enabling.
// ─────────────────────────────────────────────────────────────────────────────

export interface CreateOrderRequest {
  productType: 'YOGA_SUBSCRIPTION' | 'MARATHON_REGISTRATION' | 'PREMIUM_UPGRADE' | 'WORKSHOP';
  productId: string;
  /** Marathon only. */
  eventId?: string;
  category?: string;
  tier?: 'CLASSIC' | 'PREMIUM';
  /**
   * Refer & Win (§8.3): a friend's code, from their shared link or typed at
   * checkout. Credited to the friend only once this registration is PAID.
   */
  referralCode?: string;
}

/**
 * Amount is computed server-side and the client never sends a price.
 * Entitlement is granted on the gateway webhook, never on client confirmation.
 * See docs/04 T8.
 */
export interface CreateOrderResponse {
  orderId: string;
  gateway: 'RAZORPAY' | 'STUB';
  gatewayOrderId: string;
  amountPaise: number;
  currency: 'INR';
  /** Public key only. Secrets never leave the server. */
  gatewayKeyId: string | null;
}

export interface OrderStatusResponse {
  orderId: string;
  /**
   * PAID_NOT_GRANTED: money arrived but the product was gone by then (seat
   * sold out, registration closed) — the order is flagged for a refund.
   */
  status: 'CREATED' | 'PENDING' | 'PAID' | 'FAILED' | 'PAID_NOT_GRANTED';
  entitlementGranted: boolean;
}

export type { Referral };

// ─────────────────────────────────────────────────────────────────────────────
// Notification inbox — the TopHeader bell (design). Every push the user was
// sent, newest first, so a tap on the bell is never a dead end.
// ─────────────────────────────────────────────────────────────────────────────

export type NotificationKind =
  | 'SESSION_REMINDER'
  | 'SESSION_LIVE'
  | 'RACE_COUNTDOWN'
  | 'RACE_DAY_INFO'
  | 'RESULT_PUBLISHED';

export interface NotificationItem {
  id: string;
  kind: NotificationKind;
  title: string;
  body: string;
  /** In-app route to open on tap, e.g. "/race/delhi_half/results". */
  route: string | null;
  sentAt: string;
}

export interface NotificationListResponse {
  items: NotificationItem[];
}
