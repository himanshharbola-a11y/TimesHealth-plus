import type { AttendanceSource, YogaAttendance } from '@th/types';
import { prisma } from '../db.js';
import { allBatches } from './contentCache.js';
import { istCalendarDate, istDateString, batchInstant, byClockTime, inJoinWindow } from '../time.js';

/**
 * Single-source attendance — PRD §7.1, marked do-not-cut.
 *
 *   "Whether the user joins via app or via the WhatsApp link, attendance writes
 *    to one record. No dual-path counting. This is a hard requirement — a
 *    streak that undercounts WhatsApp joiners is worse than no streak."
 *
 * Every channel calls recordAttendance(). The APP path runs on POST /yoga/join.
 * The WHATSAPP path runs on the redirect endpoint the class link points at, so
 * the same function and the same UNIQUE(userId, date) constraint apply.
 *
 * Rules implemented here, all from §7.1:
 *   - Joining late still counts as present (there is no lateness check).
 *   - Watching a recording does NOT count (recordings never call this).
 *   - Two sessions on one day produce one mark (database constraint).
 */

export async function recordAttendance(
  userId: string,
  batchId: string | null,
  source: AttendanceSource,
  now: Date = new Date(),
): Promise<{ recorded: boolean; alreadyMarked: boolean }> {
  // One mark per IST day: Attendance.date is a DATE column (see istCalendarDate).
  const date = istCalendarDate(now);

  const existing = await prisma.attendance.findUnique({
    where: { userId_date: { userId, date } },
  });
  if (existing) return { recorded: true, alreadyMarked: true };

  try {
    await prisma.attendance.create({ data: { userId, date, batchId, source } });
    return { recorded: true, alreadyMarked: false };
  } catch (err) {
    // Lost a race with the other channel. That is exactly the outcome we want:
    // whichever path arrived first owns the mark, and this one is a no-op.
    const code = (err as { code?: string }).code;
    if (code === 'P2002') return { recorded: true, alreadyMarked: true };
    throw err;
  }
}

/**
 * The batch a join right now counts toward, or null when no class is open.
 * The class link is shared across batches, so a WhatsApp join is credited to
 * whichever batch is open — the one the link named, if that one is.
 */
export async function attendableBatch(
  now: Date,
  preferredId?: string | null,
): Promise<{ id: string } | null> {
  const batches = await allBatches();
  const open = [...batches]
    .filter((b) => inJoinWindow(batchInstant(b.time, 0, now), now))
    .sort(byClockTime);
  return open.find((b) => b.id === preferredId) ?? open[0] ?? null;
}

/** A DATE column reads back as UTC midnight of the stored day. */
function toIsoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

/** Consecutive IST days ending today (or yesterday — today is still open). */
function computeCurrentStreak(sortedDesc: string[], today: string, yesterday: string): number {
  if (sortedDesc.length === 0) return 0;
  const first = sortedDesc[0];
  if (first !== today && first !== yesterday) return 0;

  let streak = 1;
  for (let i = 1; i < sortedDesc.length; i += 1) {
    const prev = new Date(`${sortedDesc[i - 1]}T00:00:00Z`);
    const cur = new Date(`${sortedDesc[i]}T00:00:00Z`);
    const gapDays = Math.round((prev.getTime() - cur.getTime()) / 86_400_000);
    if (gapDays === 1) streak += 1;
    else break;
  }
  return streak;
}

function computeBestStreak(sortedAsc: string[]): number {
  let best = 0;
  let run = 0;
  for (let i = 0; i < sortedAsc.length; i += 1) {
    if (i === 0) {
      run = 1;
    } else {
      const prev = new Date(`${sortedAsc[i - 1]}T00:00:00Z`);
      const cur = new Date(`${sortedAsc[i]}T00:00:00Z`);
      run = Math.round((cur.getTime() - prev.getTime()) / 86_400_000) === 1 ? run + 1 : 1;
    }
    if (run > best) best = run;
  }
  return best;
}

/**
 * The Tracker payload. `bestStreak` and `attendanceRate` are named in §7.1 but
 * were absent from the design prototype — they are built here (docs/02 §1.2).
 */
export async function getAttendance(
  userId: string,
  subscriptionStartedAt: Date | null,
  now: Date = new Date(),
): Promise<YogaAttendance> {
  const rows = await prisma.attendance.findMany({
    where: { userId },
    orderBy: { date: 'desc' },
    select: { date: true },
  });

  const datesDesc = rows.map((r) => toIsoDate(r.date));
  const datesAsc = [...datesDesc].reverse();

  const today = istDateString(now);
  const yesterday = istDateString(new Date(now.getTime() - 86_400_000));

  // Rate is attendance over days since the subscription began, capped at 100.
  let attendanceRate = 0;
  if (subscriptionStartedAt) {
    const days = Math.max(
      1,
      Math.ceil((now.getTime() - subscriptionStartedAt.getTime()) / 86_400_000),
    );
    attendanceRate = Math.min(100, Math.round((datesDesc.length / days) * 100));
  }

  // The next five starts across today and tomorrow, so the list never goes
  // blank after the last evening class.
  const batches = [...(await allBatches())].sort(byClockTime);
  const upcomingSessions = [0, 1]
    .flatMap((dayOffset) =>
      batches.map((b) => ({ batchId: b.id, title: b.title, at: batchInstant(b.time, dayOffset, now) })),
    )
    .filter((b) => b.at > now)
    .slice(0, 5)
    .map(({ batchId, title, at }) => ({
      date: istDateString(at),
      batchId,
      title,
      startsAt: at.toISOString(),
    }));

  return {
    attendedDates: datesDesc,
    classesAttended: datesDesc.length,
    currentStreak: computeCurrentStreak(datesDesc, today, yesterday),
    bestStreak: computeBestStreak(datesAsc),
    attendanceRate,
    upcomingSessions,
    trackingSince: subscriptionStartedAt ? istDateString(subscriptionStartedAt) : null,
  };
}
