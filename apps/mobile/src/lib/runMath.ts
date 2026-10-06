import type { GeoPoint } from '@th/types';

/**
 * The run tracker's distance rules — pure, so they can be tested without a
 * phone (runMath.test.ts). runTracker.ts applies the plan to SQLite.
 */

/** Points less accurate than this are discarded. ~25m is a weak urban fix. */
export const ACCURACY_GATE_M = 25;
/** Below this, movement is almost certainly GPS jitter while standing still. */
export const MIN_SEGMENT_M = 4;
/**
 * Faster than any runner (~43 km/h). Judged against the time between fixes, so
 * 150 m covered during a minute in an underpass still counts, while a fix
 * that teleports 500 m in three seconds does not.
 */
export const MAX_SPEED_MPS = 12;
/**
 * A fix this much older than the start/resume moment is a replay from cache.
 * The slack lets a genuinely fresh fix, stamped a beat before the request,
 * still count.
 */
export const STALE_FIX_GRACE_MS = 2000;

/** The last stored point a new fix is measured from. */
export interface Anchor {
  lat: number;
  lng: number;
  t: number;
}

export function haversineM(a: { lat: number; lng: number }, b: { lat: number; lng: number }): number {
  const R = 6_371_000;
  const dLat = ((b.lat - a.lat) * Math.PI) / 180;
  const dLng = ((b.lng - a.lng) * Math.PI) / 180;
  const s =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((a.lat * Math.PI) / 180) * Math.cos((b.lat * Math.PI) / 180) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}

export interface BatchPlan {
  /** Fixes to append to the route, in order. */
  store: GeoPoint[];
  addedM: number;
  dropped: number;
  total: number;
  /**
   * Still waiting to re-anchor: the start/resume time (epoch ms) fixes must be
   * newer than, or null once a fresh fix has anchored the segment.
   */
  reanchorAfter: number | null;
  /** An impossible-speed fix held for the next batch to confirm or discard. */
  candidate: GeoPoint | null;
}

/**
 * Decides what a batch of fixes does to a run.
 *
 * - A fix worse than ACCURACY_GATE_M is dropped, never averaged in (§8.6).
 * - Movement under MIN_SEGMENT_M is jitter: skipped, the anchor stays put.
 * - `reanchorAfter` (the start or resume time) or no anchor yet: the next
 *   fresh fix starts a new segment and adds no distance — ground covered while
 *   paused was not run. Fixes timestamped before that moment are ignored:
 *   restarting location updates often replays a cached pre-pause fix first,
 *   and anchoring on it let the paused stretch count as a slow "run".
 * - A fix implying more than MAX_SPEED_MPS is never counted, but it is held
 *   as a candidate. If the next fix agrees with it, the runner really is there
 *   (signal regained after a gap) and the run re-anchors on it; otherwise it
 *   was a one-off error. Without this, one jump pinned the run to the old
 *   point and every later fix was "too far" — the rest of the run was lost.
 */
export function planBatch(
  anchor: Anchor | null,
  points: GeoPoint[],
  reanchorAfterIn: number | null,
  candidateIn: GeoPoint | null,
): BatchPlan {
  const store: GeoPoint[] = [];
  let prev = anchor;
  let reanchorAfter = reanchorAfterIn;
  let candidate = candidateIn;
  let addedM = 0;
  let dropped = 0;
  let total = 0;

  const keep = (p: GeoPoint) => {
    store.push(p);
    prev = { lat: p.lat, lng: p.lng, t: p.timestamp };
  };
  const speed = (from: Anchor, p: GeoPoint, d: number) => d / Math.max(1, (p.timestamp - from.t) / 1000);

  for (const p of points) {
    total += 1;

    if (p.accuracy > ACCURACY_GATE_M) {
      dropped += 1;
      continue;
    }

    if (reanchorAfter !== null || !prev) {
      // A replayed fix from before the start/resume says nothing about now.
      if (reanchorAfter !== null && p.timestamp < reanchorAfter - STALE_FIX_GRACE_MS) continue;
      keep(p);
      reanchorAfter = null;
      candidate = null;
      continue;
    }

    const d = haversineM(prev, p);
    if (d < MIN_SEGMENT_M) continue;

    if (speed(prev, p, d) <= MAX_SPEED_MPS) {
      addedM += d;
      candidate = null;
      keep(p);
      continue;
    }

    dropped += 1;
    const dc = candidate ? haversineM(candidate, p) : Infinity;
    if (candidate && speed({ lat: candidate.lat, lng: candidate.lng, t: candidate.timestamp }, p, dc) <= MAX_SPEED_MPS) {
      // Two fixes agree on the new position: re-anchor there and carry on.
      keep(candidate);
      if (dc >= MIN_SEGMENT_M) {
        addedM += dc;
        keep(p);
      }
      candidate = null;
    } else {
      candidate = p;
    }
  }

  return { store, addedM, dropped, total, reanchorAfter, candidate };
}

export const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * RFC 4122 v4 — the server only accepts a UUID run id (it makes uploads
 * idempotent). Hermes has no crypto.randomUUID, and the old fallback
 * ("<ms>-<hex>") was rejected with a 400, so no run from a phone ever synced.
 * A run id needs uniqueness, not secrecy: 122 random bits is plenty.
 */
export function uuidV4(): string {
  const native = globalThis.crypto?.randomUUID?.();
  if (native) return native;
  const h = Array.from({ length: 32 }, () => Math.floor(Math.random() * 16));
  h[12] = 4; // version
  h[16] = (h[16]! & 0x3) | 0x8; // variant 10xx
  const s = h.map((n) => n.toString(16)).join('');
  return `${s.slice(0, 8)}-${s.slice(8, 12)}-${s.slice(12, 16)}-${s.slice(16, 20)}-${s.slice(20)}`;
}
