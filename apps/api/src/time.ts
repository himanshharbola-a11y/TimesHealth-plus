/**
 * All scheduling in this product is IST. Yoga batches are defined as local
 * wall-clock times ("05:15", "18:30") and attendance is bucketed by IST
 * calendar day, so every date boundary here is +05:30 — never the server's
 * local zone, which will be UTC in production.
 */

const IST_OFFSET_MINUTES = 5 * 60 + 30;
const MS_PER_MINUTE = 60_000;

export function nowUtc(): Date {
  return new Date();
}

/** Wall-clock IST for an instant, expressed as a Date in UTC fields. */
function toIstWallClock(instant: Date): Date {
  return new Date(instant.getTime() + IST_OFFSET_MINUTES * MS_PER_MINUTE);
}

/**
 * The IST calendar day for an instant, as midnight-IST stored in UTC.
 * This is the value written to Attendance.date, and it is what makes
 * UNIQUE(userId, date) mean "one mark per IST day".
 */
export function istDateOnly(instant: Date = new Date()): Date {
  const ist = toIstWallClock(instant);
  return new Date(
    Date.UTC(ist.getUTCFullYear(), ist.getUTCMonth(), ist.getUTCDate()) -
      IST_OFFSET_MINUTES * MS_PER_MINUTE,
  );
}

/** The first instant of the IST calendar month containing `instant`. */
export function istMonthStart(instant: Date = new Date()): Date {
  const ist = toIstWallClock(instant);
  return new Date(
    Date.UTC(ist.getUTCFullYear(), ist.getUTCMonth(), 1) - IST_OFFSET_MINUTES * MS_PER_MINUTE,
  );
}

/**
 * The IST calendar day as a value for a Postgres DATE column (@db.Date):
 * UTC midnight OF THAT DAY. A DATE keeps only the UTC date part, so writing
 * istDateOnly() (IST midnight = 18:30 UTC the previous day) would store the
 * previous day — every attendance mark would land a day early.
 */
export function istCalendarDate(instant: Date = new Date()): Date {
  const ist = toIstWallClock(instant);
  return new Date(Date.UTC(ist.getUTCFullYear(), ist.getUTCMonth(), ist.getUTCDate()));
}

/** "YYYY-MM-DD" in IST. */
export function istDateString(instant: Date = new Date()): string {
  const ist = toIstWallClock(instant);
  return ist.toISOString().slice(0, 10);
}

/**
 * Resolves a batch's "HH:mm" IST wall-clock time to a real instant on the
 * given IST day.
 */
export function batchInstant(time: string, dayOffset = 0, from: Date = new Date()): Date {
  const [hh, mm] = time.split(':').map(Number);
  const ist = toIstWallClock(from);
  return new Date(
    Date.UTC(
      ist.getUTCFullYear(),
      ist.getUTCMonth(),
      ist.getUTCDate() + dayOffset,
      hh ?? 0,
      mm ?? 0,
    ) - IST_OFFSET_MINUTES * MS_PER_MINUTE,
  );
}

/**
 * "27–28 Oct · 10 AM to 6 PM" in IST. Derived from the stored instants so the
 * text can never disagree with them (a typed-in "Oct 18–19" did, once the race
 * dates moved).
 */
export function formatIstWindow(start: Date, end: Date): string {
  const s = toIstWallClock(start);
  const e = toIstWallClock(end);
  const month = (d: Date) => d.toLocaleString('en-IN', { month: 'short', timeZone: 'UTC' });
  const clock = (d: Date) => {
    const h = d.getUTCHours();
    const m = d.getUTCMinutes();
    return `${h % 12 || 12}${m ? `:${String(m).padStart(2, '0')}` : ''} ${h < 12 ? 'AM' : 'PM'}`;
  };
  const sameMonth = s.getUTCMonth() === e.getUTCMonth() && s.getUTCFullYear() === e.getUTCFullYear();
  const days = !sameMonth
    ? `${s.getUTCDate()} ${month(s)} – ${e.getUTCDate()} ${month(e)}`
    : s.getUTCDate() === e.getUTCDate()
      ? `${s.getUTCDate()} ${month(s)}`
      : `${s.getUTCDate()}–${e.getUTCDate()} ${month(s)}`;
  return `${days} · ${clock(s)} to ${clock(e)}`;
}

/** Minutes past midnight for an "HH:mm" (or "H:mm") batch time. */
export function minutesOfDay(time: string): number {
  const [hh, mm] = time.split(':').map(Number);
  return (hh ?? 0) * 60 + (mm ?? 0);
}

/**
 * Orders batches by the clock: 05:15 before 16:45. Never sort on `period` —
 * alphabetically EVENING comes before MORNING.
 */
export const byClockTime = (a: { time: string }, b: { time: string }): number =>
  minutesOfDay(a.time) - minutesOfDay(b.time);

export function addMinutes(d: Date, minutes: number): Date {
  return new Date(d.getTime() + minutes * MS_PER_MINUTE);
}

export function secondsBetween(from: Date, to: Date): number {
  return Math.round((to.getTime() - from.getTime()) / 1000);
}

/** Yoga batches run one hour (PRD §2). */
export const YOGA_BATCH_DURATION_MINUTES = 60;

export function isLiveNow(start: Date, now: Date = new Date()): boolean {
  const end = addMinutes(start, YOGA_BATCH_DURATION_MINUTES);
  return now >= start && now < end;
}

/**
 * How early the class link opens ("Join Wait Room" on the Home hero). Shared
 * by the hero and the join endpoint so what the app offers and what the
 * server counts can never disagree.
 */
export const WAIT_ROOM_MINUTES = 60;

/**
 * True from the wait room opening until the class ends. A join in this window
 * counts as attendance (§7.1: joining late still counts); outside it there is
 * no class to attend, so a tap at 10 PM for tomorrow's batch must not write a
 * streak day.
 */
export function inJoinWindow(start: Date, now: Date = new Date()): boolean {
  return (
    now >= addMinutes(start, -WAIT_ROOM_MINUTES) &&
    now < addMinutes(start, YOGA_BATCH_DURATION_MINUTES)
  );
}

/** Hour of day (0–23) in IST. */
export function istHour(instant: Date = new Date()): number {
  return toIstWallClock(instant).getUTCHours();
}

/**
 * Whole IST calendar days from `from` until `to` — 1 means "tomorrow" in IST,
 * regardless of the server's own time zone.
 */
export function istDaysUntil(to: Date, from: Date = new Date()): number {
  return Math.round((istDateOnly(to).getTime() - istDateOnly(from).getTime()) / 86_400_000);
}

/** "06:30" → "6:30 AM". */
export function formatBatchTime(time: string): string {
  const [hh = 0, mm = 0] = time.split(':').map(Number);
  const suffix = hh >= 12 ? 'PM' : 'AM';
  const h12 = hh % 12 === 0 ? 12 : hh % 12;
  return `${h12}:${String(mm).padStart(2, '0')} ${suffix}`;
}

/** "Good morning · Thursday" — matches the prototype's Home greeting. */
export function greetingFor(instant: Date = new Date()): string {
  const ist = toIstWallClock(instant);
  const hour = ist.getUTCHours();
  const part = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';
  const day = [
    'Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday',
  ][ist.getUTCDay()];
  return `${part} · ${day}`;
}
