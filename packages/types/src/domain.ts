/**
 * TimesHealth+ domain model.
 *
 * Field names follow the design prototype so screens map 1:1, but types are
 * corrected for a real API: the prototype stored display strings (`dateStr`,
 * `timeStr`, `priceLabel`). Here those are ISO timestamps and integers, and the
 * client formats them. Never send a pre-formatted string the client might need
 * to localise, sort or compute against.
 *
 * Money is always integer paise. Never floats.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Entitlement — PRD §2, §3. Independent flags, never a tier.
// ─────────────────────────────────────────────────────────────────────────────

export type YogaStatus = 'ACTIVE' | 'EXPIRED' | 'CANCELLED';
export type RaceTier = 'CLASSIC' | 'PREMIUM';
export type RaceLifecycleStatus = 'UPCOMING' | 'RACE_DAY' | 'COMPLETED';

export interface YogaEntitlement {
  active: boolean;
  status: YogaStatus;
  planId: string;
  planLabel: string;
  startedAt: string;
  expiresAt: string;
  autoRenews: boolean;
  /** Batch the user picked for reminders. They may still join any batch (§7.1). */
  reminderSlotId: string | null;
}

export interface MarathonEntitlement {
  eventId: string;
  /** For display in My Products — "10K · Premium" alone doesn't say which race. */
  eventName: string;
  registrationRef: string;
  tier: RaceTier;
  /** e.g. "21K" — one of the event's distanceOptions. */
  category: string;
  bibNumber: string | null;
  status: RaceLifecycleStatus;
  registeredAt: string;
}

/**
 * The spine of the app. Every screen resolves against this.
 * Server-authoritative — the client never computes or overrides it.
 *
 * `diet` is always null in V1 (PRD §9, accepted gap). The key exists so that
 * mapping diet customers later is additive, not a breaking change.
 */
export interface Entitlements {
  yoga: YogaEntitlement | null;
  marathon: MarathonEntitlement[];
  diet: null;
}

/**
 * Derived view used for layout decisions (PRD §3 matrix, §6.1 hero priority).
 * Computed on the server from Entitlements so app and backend never disagree.
 */
export type UserPersona =
  | 'FREE'
  | 'YOGA_SUBSCRIBER'
  | 'MARATHON_REGISTRANT'
  | 'BOTH'
  | 'YOGA_EXPIRED';

export interface PersonaInfo {
  persona: UserPersona;
  hasYoga: boolean;
  hasMarathon: boolean;
  label: string;
}

// ─────────────────────────────────────────────────────────────────────────────
// User
// ─────────────────────────────────────────────────────────────────────────────

/** The five goals offered in the design's onboarding step 3, in its order. */
export type HealthGoal =
  | 'WEIGHT_LOSS'
  | 'STRENGTH_FLEXIBILITY'
  | 'STRESS_ANXIETY'
  | 'MARATHON_TRAINING'
  | 'CONSISTENCY';

/**
 * Drives Home feed rail ordering (PRD §5 step 4, §6.3). Ordering only — never
 * hides a rail. Exactly the six options in the design's onboarding step 4.
 */
export type Concern =
  | 'LOWER_BACK'
  | 'KNEES_JOINTS'
  | 'NECK_SHOULDERS'
  | 'HIPS_PELVIS'
  | 'SLEEP_ENERGY'
  | 'NONE';

export interface UserProfile {
  id: string;
  name: string | null;
  email: string | null;
  phone: string | null;
  /**
   * True when `email` / `phone` is the sign-in identifier itself (verified by
   * the identity provider). Those change through the login provider, not by
   * editing the profile — the app shows them read-only.
   */
  emailIsLogin: boolean;
  phoneIsLogin: boolean;
  dob: string | null;
  gender: string | null;
  healthGoal: HealthGoal | null;
  concern: Concern | null;
  /** 0–100, shown in the profile drawer. */
  profileCompletion: number;
  onboardingCompleted: boolean;
  units: 'METRIC' | 'IMPERIAL';
  locale: string;
}

// ─────────────────────────────────────────────────────────────────────────────
// Yoga — PRD §7
// ─────────────────────────────────────────────────────────────────────────────

export interface YogaBatch {
  id: string;
  title: string;
  /** Local time of day, "HH:mm", IST. Combined with date on the client. */
  time: string;
  period: 'MORNING' | 'EVENING';
  instructorName: string;
  /** True for the user's chosen reminder slot (§7.1). */
  isUserReminderSlot: boolean;
  /** Resolved against server time, not the device clock. */
  isLiveNow: boolean;
  startsAt: string;
  endsAt: string;
}

/** The six category banner themes used by the design. */
export type BannerTheme = 'PLUM' | 'CORAL' | 'AMBER' | 'OCEAN' | 'EMERALD' | 'SAGE';

export interface YogaCategory {
  id: string;
  name: string;
  tagline: string;
  bodyTargetSummary: string;
  imageUrl: string;
  /** Selects a gradient — see packages/config/design-tokens.ts categoryGradients. */
  bannerTheme: BannerTheme;
  sessionCount: number;
  totalYogisJoined: number;
}

export interface YogaSession {
  id: string;
  categoryId: string;
  title: string;
  description: string;
  imageUrl: string;
  durationMinutes: number;
  level: string;
  intensity: string;
  caloriesBurned: number;
  /** Free sessions are playable by anyone (§6.3 rail 2). */
  isFree: boolean;
  isLive: boolean;
  startsAt: string | null;
  /** Therapeutic metadata — the design's session explorer depth. */
  bodyFocusTitle: string;
  targetBodyParts: string[];
  keyPoses: string[];
  lifestyleImpact: string;
  instructor: Instructor;
  joinedCountTillDate: number;
  todayActiveCount: number;
  /** Null unless the caller is entitled. Short-TTL signed URL (see §E2). */
  playbackUrl: string | null;
}

export interface Instructor {
  id: string;
  name: string;
  title: string;
  specialty: string;
  bio: string;
  experience: string;
  avatarUrl: string;
  rating: number;
  /** Social handle for the instructor reels rail (§6.3 rail 4). */
  handle: string | null;
  reelCount: number;
}

/** PRD §7.1 Tracker. All values computed server-side from the single ledger. */
export interface YogaAttendance {
  /** ISO dates (YYYY-MM-DD) the user attended a live session. */
  attendedDates: string[];
  classesAttended: number;
  currentStreak: number;
  /** Named in §7.1, absent from the design — built here. */
  bestStreak: number;
  /** 0–100. Also named in §7.1 and absent from the design. */
  attendanceRate: number;
  /**
   * YYYY-MM-DD (IST) the subscription began. Days from here up to yesterday
   * without a mark are "missed" on the calendar (§7.1 present/absent); days
   * before it are simply not part of the membership.
   */
  trackingSince: string | null;
  /**
   * Forward view of scheduled sessions (§7.1): the next few batch starts,
   * rolling into tomorrow once today's are over. `startsAt` is ISO 8601.
   */
  upcomingSessions: { date: string; batchId: string; title: string; startsAt: string }[];
}

/** How an attendance mark reached the ledger. Single-source requirement, §7.1. */
export type AttendanceSource = 'APP' | 'WHATSAPP' | 'WEB' | 'MANUAL';

// ─────────────────────────────────────────────────────────────────────────────
// Live workshops — present in the design, absent from the PRD. In scope by
// explicit decision. A fourth commercial model: paid, capacity-limited.
// ─────────────────────────────────────────────────────────────────────────────

export type WorkshopCategory = 'YOGA' | 'MARATHON' | 'DIET';

export interface LiveWorkshop {
  id: string;
  title: string;
  description: string;
  category: WorkshopCategory;
  focusArea: string;
  imageUrl: string;
  startsAt: string;
  durationMinutes: number;
  level: string;
  platform: string;
  instructorName: string;
  instructorTitle: string;
  instructorAvatarUrl: string;
  /** null = free for the caller's entitlement (design: "Free for Members"). */
  pricePaise: number | null;
  spotsRemaining: number;
  totalCapacity: number;
  isRegistered: boolean;
  /**
   * The caller paid for their seat. A paid seat is never cancelled in-app —
   * that would forfeit the money — it goes through support, where refunds live.
   */
  paidSeat: boolean;
  /** Only populated for registered users, close to start. */
  joinUrl: string | null;
}

// ─────────────────────────────────────────────────────────────────────────────
// Marathon — PRD §8
// ─────────────────────────────────────────────────────────────────────────────

export interface RaceDistanceOption {
  /** "3K" | "5K" | "10K" | "21K" */
  code: string;
  label: string;
  pricePaise: Record<RaceTier, number>;
  wasPricePaise: Record<RaceTier, number> | null;
  /** §8.3 edge case — suppresses the upgrade banner rather than failing it. */
  premiumSoldOut: boolean;
  registrationOpen: boolean;
}

export interface MarathonEvent {
  id: string;
  name: string;
  city: string;
  venue: string;
  imageUrl: string;
  startsAt: string;
  flagOffTime: string;
  distanceOptions: RaceDistanceOption[];
  /** §8.3 edge case — box shows a closed state, no register action. */
  registrationOpen: boolean;
  /** §8.3 edge case — the web already handles this; the app consumes it. */
  rescheduledFrom: string | null;
  expo: RaceExpoInfo | null;
  /** Set when the caller holds a registration for this edition. */
  registration: MarathonEntitlement | null;
  /** Distance from the user, km. Null when location is unavailable (§8.2). */
  distanceFromUserKm: number | null;
}

export interface RaceExpoInfo {
  venue: string;
  address: string;
  startsAt: string;
  endsAt: string;
  pickupWindow: string;
  instructions: string;
  requiredDocuments: string[];
}

/** §8.3. Server-signed, short TTL — never a bare bib number. See docs/04. */
export interface DigitalBib {
  bibNumber: string;
  participantName: string;
  category: string;
  tier: RaceTier;
  eventName: string;
  /** Signed token to render as a QR. Expires — the client must refresh it. */
  qrToken: string;
  qrExpiresAt: string;
  /** Cached for offline display at the venue. Safe to persist. */
  offlinePayload: string;
}

/** §8.4. Only returned when the timing partner has published. */
export interface RaceResult {
  published: boolean;
  finishTime: string | null;
  chipTime: string | null;
  avgPace: string | null;
  overallRank: number | null;
  ageGroupRank: number | null;
  category: string;
  splits: { label: string; time: string }[];
  certificateUrl: string | null;
  medalStatus: string | null;
  photoUrls: string[];
}

/** §8.3. 1 referral = draw entry, 5 = guaranteed Premium upgrade. */
export interface ReferralState {
  code: string;
  shareUrl: string;
  /** Counts only completed paid registrations — see docs/04 T2. */
  confirmedReferrals: number;
  luckyDrawEntries: number;
  guaranteedUpgradeUnlocked: boolean;
  /** The earned free upgrade has been used (it can be claimed once). */
  upgradeClaimed: boolean;
}

/** Design-only addition: kit courier tracking. */
export interface KitDelivery {
  status: 'NOT_DISPATCHED' | 'IN_TRANSIT' | 'DELIVERED' | 'PICKUP_ONLY';
  courierName: string | null;
  trackingRef: string | null;
  tshirtSize: string | null;
  expectedBy: string | null;
}

// ─────────────────────────────────────────────────────────────────────────────
// Run tracker — PRD §8.6. Free for everyone.
// ─────────────────────────────────────────────────────────────────────────────

export type RunTrackerState = 'READY' | 'RUNNING' | 'PAUSED' | 'SUMMARY';

export interface GeoPoint {
  lat: number;
  lng: number;
  /** Metres. Points above the accuracy gate are dropped, not averaged in. */
  accuracy: number;
  altitude: number | null;
  speed: number | null;
  timestamp: number;
}

export interface RunRecord {
  id: string;
  startedAt: string;
  endedAt: string;
  distanceKm: number;
  durationSeconds: number;
  avgPaceSecPerKm: number;
  caloriesBurned: number;
  /** Simplified polyline. Private by default — never exposed to other users. */
  routePolyline: string | null;
  /** True when GPS quality forced a degraded recording (§8.6). */
  hasAccuracyWarning: boolean;
  /** False until the device has synced it. Runs are created locally first. */
  synced: boolean;
}

// ─────────────────────────────────────────────────────────────────────────────
// Diet — PRD §9. Lead capture only.
// ─────────────────────────────────────────────────────────────────────────────

export interface DietLead {
  name: string;
  phone: string;
  condition: string | null;
  cuisinePreference: string | null;
  bestTimeToCall: string | null;
}

// ─────────────────────────────────────────────────────────────────────────────
// Content — PRD §6.3
// ─────────────────────────────────────────────────────────────────────────────

export interface Article {
  id: string;
  title: string;
  source: string;
  category: string;
  imageUrl: string;
  readTimeMinutes: number;
  url: string;
}

export interface Reel {
  id: string;
  instructorId: string;
  instructorName: string;
  instructorHandle: string;
  thumbnailUrl: string;
  playbackUrl: string;
  durationSeconds: number;
}

export interface UserQuote {
  id: string;
  quote: string;
  author: string;
  role: string;
}
