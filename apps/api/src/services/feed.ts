import type {
  Article as ArticleDto,
  Entitlements,
  FeedComponent,
  HeroSlot,
  HomeFeedResponse,
  PersonaInfo,
  Reel as ReelDto,
  UserQuote as QuoteDto,
  YogaSession as YogaSessionDto,
} from '@th/types';
import type { User } from '@prisma/client';
import { prisma } from '../db.js';
import {
  WAIT_ROOM_MINUTES,
  YOGA_BATCH_DURATION_MINUTES,
  batchInstant,
  byClockTime,
  greetingFor,
  istDaysUntil,
  isLiveNow,
  secondsBetween,
} from '../time.js';

/**
 * Yoga hero photo (the design's ThCardImage). Batches carry no artwork, so the
 * hero uses one programme image under the design's black scrim.
 */
const YOGA_HERO_IMAGE =
  'https://images.unsplash.com/photo-1545389336-cf090694435e?auto=format&fit=crop&w=900&q=75';

/** §8.4 result state lives on Home this long after the result is published, then selling resumes. */
const RESULT_HERO_DAYS = 14;
/** A result the timing partner never publishes must not hold Home forever. */
const PENDING_RESULT_MAX_DAYS = 30;
import { signPlaybackUrl } from './media.js';
import { shouldShowRenewPrompt } from './entitlements.js';
import { loadWorkshopsFor } from './workshops.js';
import { getAttendance } from './attendance.js';
import {
  activePromos,
  allBatches,
  allCategories,
  freeSessions as freeSessionsRail,
  homeArticles,
  homeQuotes,
  homeReels,
  sessionsInCategory,
} from './contentCache.js';

/**
 * Home feed assembly — PRD §6.
 *
 * Everything about the shape of Home is decided here, on the server. The app
 * renders the returned components in order and knows nothing about the rules.
 * That is what makes the §13 cut list (concern ordering, Refer & Win placement,
 * promo strip) config changes instead of releases.
 */


// ─────────────────────────────────────────────────────────────────────────────
// Today's yoga schedule
// ─────────────────────────────────────────────────────────────────────────────

interface TodaySchedule {
  liveBatch: {
    id: string;
    title: string;
    instructorName: string;
    instructorAvatarUrl: string | null;
    startsAt: Date;
  } | null;
  nextBatch: {
    id: string;
    title: string;
    instructorName: string;
    instructorAvatarUrl: string | null;
    startsAt: Date;
    /** False when the next batch is tomorrow — §6.1 rest-day edge case. */
    isToday: boolean;
  } | null;
}

export async function getTodaySchedule(now: Date = new Date()): Promise<TodaySchedule> {
  // Clock order matters below: after the last class, "tomorrow" means the
  // earliest batch (05:15), not whichever row the database returns first.
  // Copy before sorting: the cached array is shared by every request.
  const batches = [...(await allBatches())].sort(byClockTime);
  if (batches.length === 0) return { liveBatch: null, nextBatch: null };

  const todays = batches
    .map((b) => ({
      id: b.id,
      title: b.title,
      instructorName: b.instructor.name,
      instructorAvatarUrl: b.instructor.avatarUrl,
      startsAt: batchInstant(b.time, 0, now),
    }))
    .sort((a, b) => a.startsAt.getTime() - b.startsAt.getTime());

  const live = todays.find((b) => isLiveNow(b.startsAt, now)) ?? null;
  const upcomingToday = todays.find((b) => b.startsAt > now) ?? null;

  if (upcomingToday) {
    return { liveBatch: live, nextBatch: { ...upcomingToday, isToday: true } };
  }

  // All of today's batches have passed (or it is a rest day). §6.1 says the
  // hero then shows the next scheduled session with its DAY, not a countdown.
  const first = batches[0];
  if (!first) return { liveBatch: live, nextBatch: null };
  const tomorrow = {
    id: first.id,
    title: first.title,
    instructorName: first.instructor.name,
    instructorAvatarUrl: first.instructor.avatarUrl,
    startsAt: batchInstant(first.time, 1, now),
    isToday: false,
  };
  return { liveBatch: live, nextBatch: tomorrow };
}

// ─────────────────────────────────────────────────────────────────────────────
// Hero stack — §6.1
// ─────────────────────────────────────────────────────────────────────────────

async function buildYogaSessionSlot(
  schedule: TodaySchedule,
  now: Date,
): Promise<HeroSlot | null> {
  const live = schedule.liveBatch;
  if (live) {
    return {
      kind: 'YOGA_SESSION',
      title: live.title,
      subtitle: `Live now · with ${live.instructorName}`,
      ctaLabel: 'Join Live Session',
      imageUrl: YOGA_HERO_IMAGE,
      sessionId: live.id,
      batchId: live.id,
      state: 'LIVE',
      startsAt: live.startsAt.toISOString(),
      secondsToStart: 0,
      joinUrl: null, // issued by POST /yoga/join, which also writes attendance
      instructorName: live.instructorName,
      instructorAvatarUrl: live.instructorAvatarUrl,
      durationMinutes: YOGA_BATCH_DURATION_MINUTES,
    };
  }

  const next = schedule.nextBatch;
  if (!next) return null;

  const seconds = secondsBetween(now, next.startsAt);
  const startingSoon = next.isToday && seconds <= WAIT_ROOM_MINUTES * 60;

  return {
    kind: 'YOGA_SESSION',
    title: next.title,
    subtitle: `with ${next.instructorName}`,
    // Only an open wait room is joinable (the server counts attendance only
    // then); a later session opens the day's schedule instead.
    ctaLabel: startingSoon ? 'Join Wait Room' : 'View Schedule',
    imageUrl: YOGA_HERO_IMAGE,
    sessionId: next.id,
    batchId: next.id,
    state: startingSoon ? 'STARTING_SOON' : next.isToday ? 'SCHEDULED_TODAY' : 'SCHEDULED_LATER',
    startsAt: next.startsAt.toISOString(),
    // Null when it is not today — the client shows the day instead of a timer.
    secondsToStart: next.isToday ? seconds : null,
    joinUrl: null,
    instructorName: next.instructorName,
    instructorAvatarUrl: next.instructorAvatarUrl,
    durationMinutes: YOGA_BATCH_DURATION_MINUTES,
  };
}

async function buildMyRaceSlot(
  userId: string,
  entitlements: Entitlements,
  now: Date,
): Promise<HeroSlot | null> {
  // §6.1 edge case: multiple registrations → nearest by date. Completed races
  // only surface here when there is nothing upcoming to show instead.
  const events = await prisma.marathonEvent.findMany({
    where: { id: { in: entitlements.marathon.map((m) => m.eventId) } },
    select: { id: true, startsAt: true },
  });
  const startsAt = new Map(events.map((e) => [e.id, e.startsAt]));

  const byDate = (a: typeof entitlements.marathon[number], b: typeof a) =>
    (startsAt.get(a.eventId)?.getTime() ?? 0) - (startsAt.get(b.eventId)?.getTime() ?? 0);

  const upcoming = entitlements.marathon.filter((m) => m.status !== 'COMPLETED').sort(byDate);
  const target = upcoming[0];
  if (!target) return buildRaceResultSlot(userId, entitlements, startsAt, now);

  const event = await prisma.marathonEvent.findUnique({ where: { id: target.eventId } });
  if (!event) return null;

  // Calendar days in IST, so "1 day to go" means tomorrow, not "24h from now".
  const days = Math.max(0, istDaysUntil(event.startsAt, now));
  const isRaceDay = target.status === 'RACE_DAY';
  return {
    kind: 'MY_RACE',
    title: event.name,
    subtitle: isRaceDay ? 'Race day' : days === 1 ? '1 day to go' : `${days} days to go`,
    // Race morning: the pass is what the runner needs at the gate.
    ctaLabel: isRaceDay ? 'Open Digital Bib & Pass' : 'Open Race Dashboard',
    imageUrl: event.imageUrl,
    eventId: event.id,
    startsAt: event.startsAt.toISOString(),
    daysRemaining: days,
    bibNumber: target.bibNumber,
    category: target.category,
    flagOffTime: event.flagOffTime,
    isRaceDay,
  };
}

/**
 * §6.1 / §8.4: once the race is run the box becomes a result state, and a
 * pending result shows as pending rather than as an empty screen.
 *
 * The slot is held for RESULT_HERO_DAYS after the result is PUBLISHED, not
 * after race day: timing partners can take a week or more, and a result that
 * lands late must still reach Home. A result that never arrives lets go after
 * PENDING_RESULT_MAX_DAYS so the next edition can be sold.
 */
async function buildRaceResultSlot(
  userId: string,
  entitlements: Entitlements,
  startsAt: Map<string, Date>,
  now: Date,
): Promise<HeroSlot | null> {
  const completedIds = entitlements.marathon
    .filter((m) => m.status === 'COMPLETED')
    .map((m) => m.eventId);
  if (completedIds.length === 0) return null;

  const registrations = await prisma.marathonRegistration.findMany({
    where: { userId, eventId: { in: completedIds } },
    select: { eventId: true, result: { select: { published: true, updatedAt: true } } },
  });
  const DAY = 86_400_000;
  const eligible = registrations
    .filter((r) => {
      if (r.result?.published) return now.getTime() - r.result.updatedAt.getTime() <= RESULT_HERO_DAYS * DAY;
      const raceAt = startsAt.get(r.eventId);
      return raceAt !== undefined && now.getTime() - raceAt.getTime() <= PENDING_RESULT_MAX_DAYS * DAY;
    })
    // Most recent race first.
    .sort((a, b) => (startsAt.get(b.eventId)?.getTime() ?? 0) - (startsAt.get(a.eventId)?.getTime() ?? 0));

  const chosen = eligible[0];
  if (!chosen) return null;
  const event = await prisma.marathonEvent.findUnique({ where: { id: chosen.eventId } });
  if (!event) return null;
  const published = chosen.result?.published === true;
  return {
    kind: 'RACE_RESULT',
    title: event.name,
    subtitle: published ? 'Your result is ready' : 'Results are being published',
    ctaLabel: published ? 'Check Result & Certificate' : 'Result pending',
    imageUrl: event.imageUrl,
    eventId: event.id,
    resultPublished: published,
  };
}

async function buildSellMarathonSlot(now: Date, excludeEventIds: string[]): Promise<HeroSlot | null> {
  // §6.1 "Sell marathon (nearest edition)" — never one the user already holds.
  const event = await prisma.marathonEvent.findFirst({
    where: { startsAt: { gt: now }, registrationOpen: true, id: { notIn: excludeEventIds } },
    orderBy: { startsAt: 'asc' },
    include: { distanceOptions: { orderBy: { sortOrder: 'asc' } } },
  });
  if (!event) return null;
  const from = Math.min(
    ...event.distanceOptions.map((d) => d.priceClassicPaise).filter((p) => p > 0),
  );
  return {
    kind: 'SELL_MARATHON',
    title: event.name,
    subtitle: `${event.city} · ${event.distanceOptions.length} distances`,
    ctaLabel: 'Register Now',
    imageUrl: event.imageUrl,
    eventId: event.id,
    fromPricePaise: Number.isFinite(from) ? from : 0,
    city: event.city,
    startsAt: event.startsAt.toISOString(),
  };
}

function buildSellYogaSlot(): HeroSlot {
  return {
    kind: 'SELL_YOGA',
    // The design's copy (HomeScreen.kt), line break included.
    title: 'Eight live yoga classes\nevery single day',
    subtitle: 'Taught by certified masters from The Yoga Institute. Join morning or evening.',
    ctaLabel: 'Explore Yoga Membership',
    // The design's sell hero is a photo card, like the live-class one.
    imageUrl: YOGA_HERO_IMAGE,
    planId: 'yoga_annual',
    pricePaise: 0, // resolved by the paywall; never priced from the client
  };
}

function buildRenewSlot(entitlements: Entitlements, streak: number): HeroSlot {
  return {
    kind: 'YOGA_RENEW',
    title: 'Your saved practice is still here.',
    // Never "your 0-day streak" — a member who never attended gets the general line.
    subtitle:
      streak > 0
        ? `Your ${streak}-day streak history is preserved. Re-subscribe to return to live batches.`
        : 'Your saved sessions and history are preserved. Re-subscribe to return to live batches.',
    ctaLabel: 'Renew Membership',
    imageUrl: null,
    expiredAt: entitlements.yoga?.expiresAt ?? new Date().toISOString(),
    preservedStreak: streak,
  };
}

/**
 * The hard priority rule from §6.1:
 *   1. A yoga subscriber's session ALWAYS wins slot 1.
 *   2. Whatever loses drops to slot 2.
 */
async function buildHeroStack(
  userId: string,
  entitlements: Entitlements,
  persona: PersonaInfo,
  schedule: TodaySchedule,
  streak: number,
  now: Date,
): Promise<HeroSlot[]> {
  const slots: HeroSlot[] = [];

  if (persona.hasYoga) {
    const session = await buildYogaSessionSlot(schedule, now);
    if (session) slots.push(session);
  } else if (shouldShowRenewPrompt(entitlements)) {
    // §5: a warm re-subscribe prompt, never a cold sell.
    slots.push(buildRenewSlot(entitlements, streak));
  }

  if (persona.hasMarathon) {
    const race = await buildMyRaceSlot(userId, entitlements, now);
    if (race) slots.push(race);
  }

  // Fill remaining capacity with sell slots, in the order §6.1's matrix implies.
  // Marathon is per-event (§2): only a race still AHEAD suppresses selling the
  // next one — a past finisher is exactly who the next edition is for.
  const hasRaceAhead = entitlements.marathon.some((m) => m.status !== 'COMPLETED');
  if (slots.length < 2 && !persona.hasYoga && !shouldShowRenewPrompt(entitlements)) {
    slots.push(buildSellYogaSlot());
  }
  if (slots.length < 2 && !hasRaceAhead) {
    const sell = await buildSellMarathonSlot(
      now,
      entitlements.marathon.map((m) => m.eventId),
    );
    if (sell) slots.push(sell);
  }
  // No third fill: a second identical "sell yoga" banner, or a cold yoga sell
  // under the warm renew prompt (§5), is worse than a single hero.

  return slots.slice(0, 2);
}

// ─────────────────────────────────────────────────────────────────────────────
// Rails — §6.3
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Concern → rail 1 heading and category.
 *
 * Headings are the design's exact copy (HomeScreen.kt). The prototype matched
 * on "knees" / "sleep" / "stress", which never equal the onboarding options
 * "Knees & joints" / "Sleep & energy" — so 4 of its 6 concerns silently fell
 * through to the weight-loss default. Here every concern maps explicitly.
 *
 * Hips & pelvis had no heading in the design at all; its copy below follows the
 * same voice and should be confirmed with design.
 */
const CONCERN_RAIL: Record<string, { categoryId: string; heading: string }> = {
  LOWER_BACK: { categoryId: 'cat_spine', heading: 'Sessions for lower back relief' },
  NECK_SHOULDERS: { categoryId: 'cat_desk', heading: 'Sessions for neck & shoulder release' },
  KNEES_JOINTS: { categoryId: 'cat_flex', heading: 'Sessions for knee & joint stability' },
  HIPS_PELVIS: { categoryId: 'cat_flex', heading: 'Sessions for hip & pelvic release' },
  SLEEP_ENERGY: { categoryId: 'cat_sleep', heading: 'Sessions for deep evening sleep' },
};

/** With no specific concern, the onboarding goal picks the rail instead. */
const GOAL_RAIL: Record<string, { categoryId: string; heading: string }> = {
  STRESS_ANXIETY: { categoryId: 'cat_sleep', heading: 'Sessions for calm & stress release' },
  WEIGHT_LOSS: { categoryId: 'cat_core', heading: 'Sessions for weight loss & agility' },
  STRENGTH_FLEXIBILITY: { categoryId: 'cat_flex', heading: 'Sessions for weight loss & agility' },
  MARATHON_TRAINING: { categoryId: 'cat_flex', heading: 'Sessions for knee & joint stability' },
};

/** The design's default when nothing matches. */
const DEFAULT_RAIL = { categoryId: 'cat_morning', heading: 'Sessions for weight loss & agility' };

export function resolveConcernRail(
  concern: string | null,
  goal: string | null,
): { categoryId: string; heading: string } {
  if (concern && CONCERN_RAIL[concern]) return CONCERN_RAIL[concern];
  if (goal && GOAL_RAIL[goal]) return GOAL_RAIL[goal];
  return DEFAULT_RAIL;
}

type SessionRow = Awaited<ReturnType<typeof prisma.yogaSession.findMany<{ include: { instructor: true } }>>>[number];

function toSessionDto(
  s: SessionRow,
  userId: string,
  entitled: boolean,
): YogaSessionDto {
  return {
    id: s.id,
    categoryId: s.categoryId,
    title: s.title,
    description: s.description,
    imageUrl: s.imageUrl,
    durationMinutes: s.durationMinutes,
    level: s.level,
    intensity: s.intensity,
    caloriesBurned: s.caloriesBurned,
    isFree: s.isFree,
    isLive: false,
    startsAt: null,
    bodyFocusTitle: s.bodyFocusTitle,
    targetBodyParts: s.targetBodyParts,
    keyPoses: s.keyPoses,
    lifestyleImpact: s.lifestyleImpact,
    instructor: {
      id: s.instructor.id,
      name: s.instructor.name,
      title: s.instructor.title,
      specialty: s.instructor.specialty,
      bio: s.instructor.bio,
      experience: s.instructor.experience,
      avatarUrl: s.instructor.avatarUrl,
      rating: s.instructor.rating,
      handle: s.instructor.handle,
      reelCount: s.instructor.reelCount,
    },
    joinedCountTillDate: s.joinedCountTillDate,
    todayActiveCount: s.todayActiveCount,
    // §6.3: free items play for anyone; paid items need entitlement. A free
    // user tapping a paid card gets the paywall, never a dead tap.
    playbackUrl:
      s.mediaKey && (s.isFree || entitled) ? signPlaybackUrl(s.mediaKey, userId) : null,
  };
}

export async function buildHomeFeed(
  user: User,
  entitlements: Entitlements,
  persona: PersonaInfo,
  now: Date = new Date(),
): Promise<HomeFeedResponse> {
  const entitled = persona.hasYoga;

  const rail1 = resolveConcernRail(user.concern, user.healthGoal);
  const showRenew = !persona.hasYoga && shouldShowRenewPrompt(entitlements);

  // Rails fetch only what they show — never the whole library per request —
  // and everything shared by all users comes from the 60s content cache, so
  // the pre-class spike doesn't re-read the same rows thousands of times.
  const [schedule, categories, rail1Sessions, freeSessions, articles, quotes, reels, promo, attendance] =
    await Promise.all([
      getTodaySchedule(now),
      allCategories(),
      sessionsInCategory(rail1.categoryId, 4),
      freeSessionsRail(6),
      homeArticles(),
      homeQuotes(),
      homeReels(),
      activePromos(now),
      // The renew prompt says "your N-day streak is preserved" — that must be
      // the real best streak, not a count of classes attended.
      showRenew
        ? getAttendance(user.id, entitlements.yoga ? new Date(entitlements.yoga.startedAt) : null)
        : Promise.resolve(null),
    ]);

  const hero = await buildHeroStack(
    user.id,
    entitlements,
    persona,
    schedule,
    attendance?.bestStreak ?? 0,
    now,
  );
  const components: FeedComponent[] = [
    { type: 'HERO_STACK', id: 'hero', slots: hero },
  ];
  /** Index after which the promo strip goes: the first rail that rendered. */
  let promoAfter = -1;

  // Rail 1 — concern-led. §6.3: ordering only, never hides a rail.
  const primary = categories.find((c) => c.id === rail1.categoryId) ?? categories[0];
  if (primary) {
    const items = rail1Sessions.map((s) => toSessionDto(s, user.id, entitled));
    // §6.3 edge case: an empty rail does not render. No empty states in the feed.
    if (items.length > 0) {
      components.push({
        type: 'VIDEO_RAIL',
        id: `rail-${primary.id}`,
        title: rail1.heading,
        cardFormat: 'VIDEO_LANDSCAPE',
        items,
        seeAllCategoryId: primary.id,
        actionLabel: 'See all',
      });
      promoAfter = components.length;
    }
  }

  // §6.2 — the promo strip sits AFTER the first content rail, not at the top.
  // Default logic: carry whatever the hero stack is not already selling.
  const alreadySelling = new Set<string>(
    hero.map((s) =>
      s.kind === 'SELL_YOGA' ? 'YOGA' : s.kind === 'SELL_MARATHON' ? 'MARATHON' : 'NONE',
    ),
  );
  // Also never sell a product the user already holds. §6.2 says "whatever the
  // hero stack isn't already selling", but a registered runner being sold their
  // own race is the obvious failure of that rule read literally.
  if (persona.hasYoga) alreadySelling.add('YOGA');
  if (entitlements.marathon.some((m) => m.status !== 'COMPLETED')) alreadySelling.add('MARATHON');

  const strip =
    promo.find((p) => !alreadySelling.has(p.sellsProduct)) ??
    promo.find((p) => p.sellsProduct === 'DIET');
  const promoComponent: FeedComponent | null = strip
    ? {
      type: 'PROMO_STRIP',
      id: `promo-${strip.campaignId}`,
      campaignId: strip.campaignId,
      title: strip.title,
      subtitle: strip.subtitle,
      ctaLabel: strip.ctaLabel,
      imageUrl: strip.imageUrl,
      backgroundColor: strip.backgroundColor,
      action: strip.action as never,
    }
    : null;

  // Rail 2 — free sessions.
  const freeItems = freeSessions.map((s) => toSessionDto(s, user.id, entitled));
  if (freeItems.length > 0) {
    components.push({
      type: 'VIDEO_RAIL',
      id: 'rail-free',
      title: 'Free yoga sessions',
      cardFormat: 'VIDEO_LANDSCAPE',
      items: freeItems,
      seeAllCategoryId: null,
      // Design: the free rail's header action is "Explore" (opens the catalogue).
      actionLabel: 'Explore',
    });
    if (promoAfter < 0) promoAfter = components.length;
  }

  // Rail 3 — run tracker entry tile. Free for everyone (§8.6). Title is the
  // design's; the subtitle states only what the tracker really measures (the
  // design's "cadence & elevation" would be a false claim).
  components.push({
    type: 'ENTRY_TILE',
    id: 'tile-run-tracker',
    title: 'GPS Outdoor Run',
    subtitle: 'Track live distance, pace & route — even with the screen off',
    imageUrl: 'https://images.unsplash.com/photo-1571008887538-b36bb32f4571?auto=format&fit=crop&w=900&q=70',
    action: { type: 'OPEN_RUN_TRACKER' },
  });
  if (promoAfter < 0) promoAfter = components.length;

  // §6.2: the promo strip sits AFTER the first content rail that rendered —
  // never at the top of the feed, even when rail 1 had nothing to show.
  if (promoComponent) components.splice(promoAfter, 0, promoComponent);

  // Live workshops — in the design (HomeScreen.kt places it between free
  // sessions and instructors), not in the PRD; in scope by decision.
  const workshopItems = await loadWorkshopsFor(user.id, persona.hasYoga, now);
  if (workshopItems.length > 0) {
    components.push({
      type: 'WORKSHOP_RAIL',
      id: 'rail-workshops',
      title: 'Upcoming Live Workshops',
      cardFormat: 'WORKSHOP_CARD',
      items: workshopItems,
    });
  }

  // Rail 4 — instructor reels, deliberately low in the feed (§6.3).
  const reelItems: ReelDto[] = reels.map((r) => ({
    id: r.id,
    instructorId: r.instructorId,
    instructorName: r.instructor.name,
    instructorHandle: r.instructor.handle ?? '',
    thumbnailUrl: r.thumbnailUrl,
    playbackUrl: r.playbackUrl,
    durationSeconds: r.durationSeconds,
  }));
  if (reelItems.length > 0) {
    components.push({
      type: 'REEL_RAIL',
      id: 'rail-instructors',
      title: 'Explore our instructors',
      cardFormat: 'REEL_PORTRAIT',
      items: reelItems,
    });
  }

  // Rail 5 — TOI & ET coverage.
  const articleItems: ArticleDto[] = articles.map((a) => ({
    id: a.id,
    title: a.title,
    source: a.source,
    category: a.category,
    imageUrl: a.imageUrl,
    readTimeMinutes: a.readTimeMinutes,
    url: a.url,
  }));
  if (articleItems.length > 0) {
    components.push({
      type: 'ARTICLE_RAIL',
      id: 'rail-articles',
      title: 'TOI & ET coverage',
      cardFormat: 'ARTICLE_WIDE',
      items: articleItems,
    });
  }

  // Rail 6 — testimonials. Read-only: no link, no video, no tap action.
  const quoteItems: QuoteDto[] = quotes.map((q) => ({
    id: q.id,
    quote: q.quote,
    author: q.author,
    role: q.role,
  }));
  if (quoteItems.length > 0) {
    components.push({
      type: 'QUOTE_RAIL',
      id: 'rail-quotes',
      title: 'What our members say',
      cardFormat: 'QUOTE_SQUARE',
      items: quoteItems,
    });
  }

  return {
    greeting: greetingFor(now),
    userName: user.name,
    components,
    serverTime: now.toISOString(),
    ttlSeconds: 60,
  };
}

export { YOGA_BATCH_DURATION_MINUTES };
