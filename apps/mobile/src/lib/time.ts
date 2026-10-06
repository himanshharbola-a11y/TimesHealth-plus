import { useEffect, useRef, useState } from 'react';

/**
 * Countdowns tick locally from the server's clock, never the device's.
 *
 * PRD §6.1 promises "Starts in 4 min". Device clocks are wrong often enough
 * that trusting them would show the wrong number, and polling the server every
 * second would hammer it at exactly the moment 100k subscribers open the app
 * for the 6:00 AM batch.
 */

let skewMs = 0;

/** Call once per /home or /session response. */
export function syncServerTime(serverTimeIso: string): void {
  skewMs = new Date(serverTimeIso).getTime() - Date.now();
}

export function serverNow(): number {
  return Date.now() + skewMs;
}

/**
 * The server-corrected time, re-rendering every `intervalMs`. For anything
 * whose state flips at a moment in time (a class link opening, a QR token
 * expiring) on a screen that may sit open across that moment.
 */
export function useServerNow(intervalMs: number): number {
  const [now, setNow] = useState(serverNow);
  useEffect(() => {
    const id = setInterval(() => setNow(serverNow()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
  return now;
}

/** Seconds remaining until an ISO instant, ticking once per second. */
export function useCountdown(targetIso: string | null): number | null {
  const [, force] = useState(0);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (!targetIso) return;
    timer.current = setInterval(() => force((n) => n + 1), 1000);
    return () => {
      if (timer.current) clearInterval(timer.current);
    };
  }, [targetIso]);

  if (!targetIso) return null;
  return Math.max(0, Math.round((new Date(targetIso).getTime() - serverNow()) / 1000));
}

export function formatCountdown(seconds: number): string {
  if (seconds <= 0) return 'Starting now';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (h > 0) return `Starts in ${h}h ${m}m`;
  if (m > 0) return `Starts in ${m} min`;
  return `Starts in ${seconds}s`;
}

/** "Thu, 15 Oct · 6:00 AM" — matches the prototype's date format. */
export function formatSessionDate(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleString('en-IN', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  });
}

export function formatTimeOfDay(iso: string): string {
  return new Date(iso).toLocaleTimeString('en-IN', {
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  });
}

/** "Today · 7:30 pm", "Tomorrow · 5:15 am", otherwise "Wed · 6:00 am". */
export function formatDayAndTime(iso: string): string {
  const d = new Date(iso);
  const midnight = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const days = Math.round((midnight(d) - midnight(new Date(serverNow()))) / 86_400_000);
  const day =
    days === 0
      ? 'Today'
      : days === 1
        ? 'Tomorrow'
        : d.toLocaleDateString('en-IN', { weekday: 'short' });
  return `${day} · ${formatTimeOfDay(iso)}`;
}

export function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
}

/** Pace as 5'58" /km. */
export function formatPace(secPerKm: number): string {
  if (!Number.isFinite(secPerKm) || secPerKm <= 0) return "--'--\" /km";
  // Round the total first: splitting then rounding turned 13:59.6 into 13'60".
  const total = Math.round(secPerKm);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}'${String(s).padStart(2, '0')}" /km`;
}

/** Integer paise → "₹1,977". */
export function formatPaise(paise: number): string {
  return `₹${Math.round(paise / 100).toLocaleString('en-IN')}`;
}
