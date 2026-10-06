import type { FastifyBaseLogger } from 'fastify';
import { prisma } from '../db.js';
import {
  addMinutes,
  batchInstant,
  formatBatchTime,
  istDateString,
  istDaysUntil,
  istHour,
} from '../time.js';
import {
  pruneNotificationLog,
  releaseStaleClaims,
  sendPushBatch,
  type PushRecipient,
} from './push.js';
import { forwardPendingDietLeads } from './dietLeads.js';

/**
 * Notification scheduler — the timed triggers from PRD §11.
 *
 *   Session reminder     → push, 15 min before the user's chosen slot
 *   Session starting now → push, as the batch goes live
 *   Race countdown       → push, at 30 / 14 / 7 / 3 days out
 *   Expo / race day info → push, the day before expo and on race eve
 *   Result published     → push, when the timing partner's result lands
 *
 * WhatsApp delivery of the same messages runs through the existing BSP and is
 * not this service's job (§11: "WhatsApp continues, app push is added").
 *
 * ROBUSTNESS: every window below is wider than the one-minute tick, and every
 * send is de-duplicated by NotificationLog (services/push.ts). So a late tick,
 * a restart, or a PC waking from sleep still sends once — never zero times
 * inside the window, and never twice.
 *
 * SCALE: each tick first works out WHICH batches / events are inside a
 * window (usually none), then loads only those users, and sends in batches of
 * 500 — not one query and one FCM call per subscriber per minute.
 */

const TICK_MS = 60_000;

/** Countdown, race-info and result pushes respect quiet hours. Class reminders follow the user's chosen slot. */
const QUIET_BEFORE_HOUR = 9;
const QUIET_FROM_HOUR = 21;

const COUNTDOWN_DAYS = new Set([30, 14, 7, 3]);

let timer: ReturnType<typeof setInterval> | null = null;
let inFlight: Promise<void> | null = null;
let lastPruneAt = 0;

export function startScheduler(log: FastifyBaseLogger): void {
  if (timer) return;
  timer = setInterval(() => void runTick(log), TICK_MS);
  void runTick(log);
  log.info('notification scheduler started');
}

/**
 * Stops the timer and waits for a tick that is mid-send, so shutdown doesn't
 * pull the database out from under it and strand claimed-but-unsent pushes.
 */
export async function stopScheduler(): Promise<void> {
  if (timer) clearInterval(timer);
  timer = null;
  if (inFlight) await inFlight;
}

function runTick(log: FastifyBaseLogger): Promise<void> {
  // A slow tick must not overlap the next one.
  if (inFlight) return inFlight;
  inFlight = tick(log).finally(() => {
    inFlight = null;
  });
  return inFlight;
}

async function tick(log: FastifyBaseLogger): Promise<void> {
  const now = new Date();
  const step = async (name: string, fn: () => Promise<unknown>) => {
    try {
      await fn();
    } catch (err) {
      // One failing step must not starve the others.
      log.error({ err: String(err), step: name }, 'scheduler step failed');
    }
  };

  await step('release-stale', () => releaseStaleClaims(now));
  await step('session', () => sessionReminders(now));
  const hour = istHour(now);
  if (hour >= QUIET_BEFORE_HOUR && hour < QUIET_FROM_HOUR) {
    await step('race', () => raceMilestones(now, hour));
    // A result is never urgent enough to wake someone at 2 AM.
    await step('results', () => publishedResults(now));
  }
  await step('diet-leads', () => forwardPendingDietLeads(log));
  if (now.getTime() - lastPruneAt > 3600_000) {
    lastPruneAt = now.getTime();
    await step('prune', () => pruneNotificationLog(now));
  }
}

async function sessionReminders(now: Date): Promise<void> {
  const batches = await prisma.yogaBatch.findMany({ include: { instructor: { select: { name: true } } } });
  const day = istDateString(now);

  // Which batches are inside a window right now — usually none.
  const reminding: typeof batches = [];
  const live: typeof batches = [];
  for (const batch of batches) {
    const startsAt = batchInstant(batch.time, 0, now);
    const minutesToStart = (startsAt.getTime() - now.getTime()) / 60_000;
    // 15-minute reminder. The window is 10–15 min so a late tick still sends;
    // the body names the start time rather than "in 15 minutes" for that reason.
    if (minutesToStart > 10 && minutesToStart <= 15) reminding.push(batch);
    // Live now — within the first five minutes of the batch.
    if (startsAt <= now && now < addMinutes(startsAt, 5)) live.push(batch);
  }
  const inWindow = [...new Set([...reminding, ...live].map((b) => b.id))];
  if (inWindow.length === 0) return;

  const subscribers = await prisma.yogaSubscription.findMany({
    where: { status: 'ACTIVE', expiresAt: { gt: now }, reminderSlotId: { in: inWindow } },
    select: { userId: true, reminderSlotId: true },
  });
  const usersOf = (batchId: string) => subscribers.filter((s) => s.reminderSlotId === batchId);

  for (const batch of reminding) {
    const at = formatBatchTime(batch.time);
    await sendPushBatch(
      'SESSION_REMINDER',
      usersOf(batch.id).map((u) => ({
        userId: u.userId,
        dedupeKey: `${batch.id}:${day}`,
        message: {
          title: 'Your class starts soon',
          body: `${batch.title} with ${batch.instructor.name} begins at ${at}.`,
          route: '/(tabs)/yoga',
        },
      })),
    );
  }
  for (const batch of live) {
    await sendPushBatch(
      'SESSION_LIVE',
      usersOf(batch.id).map((u) => ({
        userId: u.userId,
        dedupeKey: `${batch.id}:${day}`,
        message: {
          title: `${batch.title} is live`,
          body: `${batch.instructor.name} has started. Tap to join.`,
          route: '/(tabs)/yoga',
        },
      })),
    );
  }
}

async function raceMilestones(now: Date, hour: number): Promise<void> {
  // Events within the furthest milestone, then only those on a milestone day.
  const events = await prisma.marathonEvent.findMany({
    where: { startsAt: { gt: now, lte: new Date(now.getTime() + 31 * 86_400_000) } },
  });
  const due = events
    .map((e) => ({
      e,
      daysLeft: istDaysUntil(e.startsAt, now),
      expoTomorrow: e.expoStartsAt !== null && istDaysUntil(e.expoStartsAt, now) === 1,
    }))
    .filter((x) => COUNTDOWN_DAYS.has(x.daysLeft) || x.expoTomorrow || (x.daysLeft === 1 && hour >= 18));
  if (due.length === 0) return;

  const registrations = await prisma.marathonRegistration.findMany({
    where: { eventId: { in: due.map((d) => d.e.id) }, status: { not: 'COMPLETED' } },
    select: { userId: true, eventId: true, category: true, tier: true },
  });

  const countdown: PushRecipient[] = [];
  const expo: PushRecipient[] = [];
  const eve: PushRecipient[] = [];
  for (const { e, daysLeft, expoTomorrow } of due) {
    for (const r of registrations.filter((x) => x.eventId === e.id)) {
      if (COUNTDOWN_DAYS.has(daysLeft)) {
        countdown.push({
          userId: r.userId,
          dedupeKey: `${e.id}:${daysLeft}d`,
          message: {
            title: `${daysLeft} days to go`,
            body: `${e.name} · ${r.category} ${r.tier === 'PREMIUM' ? 'Premium' : 'Classic'}. Your bib is in the app.`,
            route: `/race/${e.id}`,
          },
        });
      }
      if (expoTomorrow) {
        expo.push({
          userId: r.userId,
          dedupeKey: `${e.id}:expo`,
          message: {
            title: 'Bib pickup opens tomorrow',
            body: `${e.expoVenue ?? 'Expo'} · carry a government photo ID. Your pass is in the app.`,
            route: `/bib/${e.id}`,
          },
        });
      }
      // Race eve, in the evening rather than at 9 AM, so it's fresh at bedtime.
      if (daysLeft === 1 && hour >= 18) {
        eve.push({
          userId: r.userId,
          dedupeKey: `${e.id}:eve`,
          message: {
            title: 'Race day tomorrow',
            body: `Flag-off ${e.flagOffTime} at ${e.venue}. Open your digital bib before you leave.`,
            route: `/bib/${e.id}`,
          },
        });
      }
    }
  }
  await sendPushBatch('RACE_COUNTDOWN', countdown);
  // Expo and race-eve share a kind; distinct dedupe keys keep them separate.
  await sendPushBatch('RACE_DAY_INFO', [...expo, ...eve]);
}

async function publishedResults(now: Date): Promise<void> {
  const results = await prisma.raceResult.findMany({
    where: { published: true, updatedAt: { gt: new Date(now.getTime() - 7 * 86_400_000) } },
    select: {
      registrationId: true,
      chipTime: true,
      registration: { select: { userId: true, event: { select: { id: true, name: true } } } },
    },
  });
  if (results.length === 0) return;

  await sendPushBatch(
    'RESULT_PUBLISHED',
    results.map((res) => ({
      userId: res.registration.userId,
      dedupeKey: res.registrationId,
      message: {
        title: 'Your result is in',
        body: `${res.registration.event.name}${res.chipTime ? ` · chip time ${res.chipTime}` : ''}. Your certificate is ready.`,
        route: `/race/${res.registration.event.id}/results`,
      },
    })),
  );
}
