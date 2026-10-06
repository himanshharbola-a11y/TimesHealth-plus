import type {
  Entitlements,
  MarathonEntitlement,
  PersonaInfo,
  RaceLifecycleStatus,
  UserPersona,
  YogaEntitlement,
} from '@th/types';
import { prisma } from '../db.js';
import { istDateOnly } from '../time.js';

/**
 * Entitlement resolution — PRD §2, §3.
 *
 * Three independent flags, never a tier. This is the single source of truth for
 * every gated surface in the app. The client receives the result and renders
 * against it; it never computes entitlement itself and may not override it.
 *
 * Note `active` is derived from the expiry timestamp at read time, not trusted
 * from the stored status column. A subscription that lapsed overnight reads as
 * inactive on the next request without waiting for a cron job to catch up.
 */

export interface ResolvedEntitlements {
  entitlements: Entitlements;
  persona: PersonaInfo;
}

const PERSONA_LABELS: Record<UserPersona, string> = {
  FREE: 'Free Member',
  YOGA_SUBSCRIBER: 'Yoga Subscriber',
  MARATHON_REGISTRANT: 'Marathon Registered',
  BOTH: 'Yoga + Marathon',
  YOGA_EXPIRED: 'Previous Member',
};

/**
 * The race's lifecycle by the IST calendar. RACE_DAY spans the whole IST race
 * day; COMPLETED begins the next IST midnight. (UTC day boundaries made a race
 * "COMPLETED" at 05:30 IST on race morning — flipping the hero to "Check
 * Result" and hiding the bib QR while runners stood at the start line.)
 */
export function raceStatus(startsAt: Date, stored: string, now: Date): RaceLifecycleStatus {
  if (stored === 'COMPLETED') return 'COMPLETED';
  const dayStart = istDateOnly(startsAt);
  const dayEnd = new Date(dayStart.getTime() + 24 * 3600 * 1000);
  if (now >= dayEnd) return 'COMPLETED';
  if (now >= dayStart) return 'RACE_DAY';
  return 'UPCOMING';
}

export async function resolveEntitlements(
  userId: string,
  now: Date = new Date(),
): Promise<ResolvedEntitlements> {
  const [subscription, registrations] = await Promise.all([
    prisma.yogaSubscription.findUnique({ where: { userId } }),
    prisma.marathonRegistration.findMany({
      where: { userId },
      include: { event: { select: { startsAt: true, name: true } } },
      orderBy: { event: { startsAt: 'asc' } },
    }),
  ]);

  let yoga: YogaEntitlement | null = null;
  if (subscription) {
    const notExpired = subscription.expiresAt > now;
    const active = subscription.status === 'ACTIVE' && notExpired;
    yoga = {
      active,
      // A row still marked ACTIVE past its expiry reads as EXPIRED.
      status: active ? 'ACTIVE' : subscription.status === 'CANCELLED' ? 'CANCELLED' : 'EXPIRED',
      planId: subscription.planId,
      planLabel: subscription.planLabel,
      startedAt: subscription.startedAt.toISOString(),
      expiresAt: subscription.expiresAt.toISOString(),
      autoRenews: subscription.autoRenews,
      reminderSlotId: subscription.reminderSlotId,
    };
  }

  const marathon: MarathonEntitlement[] = registrations.map((r) => ({
    eventId: r.eventId,
    eventName: r.event.name,
    registrationRef: r.registrationRef,
    tier: r.tier === 'PREMIUM' ? 'PREMIUM' : 'CLASSIC',
    category: r.category,
    bibNumber: r.bibNumber,
    status: raceStatus(r.event.startsAt, r.status, now),
    registeredAt: r.registeredAt.toISOString(),
  }));

  const hasYoga = yoga?.active === true;
  // §8.2/§6.1 only ever surface races that have not finished; a completed race
  // still counts as an entitlement because its result screen stays reachable.
  const hasMarathon = marathon.length > 0;

  let persona: UserPersona;
  if (hasYoga && hasMarathon) persona = 'BOTH';
  else if (hasYoga) persona = 'YOGA_SUBSCRIBER';
  else if (hasMarathon) persona = 'MARATHON_REGISTRANT';
  else if (yoga && !yoga.active) persona = 'YOGA_EXPIRED';
  else persona = 'FREE';

  return {
    entitlements: { yoga, marathon, diet: null },
    persona: { persona, hasYoga, hasMarathon, label: PERSONA_LABELS[persona] },
  };
}

/**
 * PRD §5: an expired subscriber is "treated as free for gating, but Home shows
 * a re-subscribe prompt rather than a cold sell". Gating and messaging are two
 * different questions, so they get two different helpers.
 */
export function canAccessLiveYoga(e: Entitlements): boolean {
  return e.yoga?.active === true;
}

export function canAccessRecordings(e: Entitlements): boolean {
  return e.yoga?.active === true;
}

export function canAccessYogaTracker(e: Entitlements): boolean {
  return e.yoga?.active === true;
}

export function shouldShowRenewPrompt(e: Entitlements): boolean {
  return e.yoga !== null && !e.yoga.active;
}

/** §8.6 — the run tracker is free for everyone. Here for explicitness. */
export function canUseRunTracker(): boolean {
  return true;
}
