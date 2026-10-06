import { prisma } from '../db.js';

/**
 * Process-local read-through cache for content that is identical for every
 * user: the daily batches, the catalogue rails, articles, quotes, reels and
 * promo campaigns.
 *
 * Why: the load spike is the minutes before each of the eight daily batches
 * (docs/03 §5), when thousands of people open Home at once. Without this,
 * each of those requests re-read the same ~10 global rows from Postgres.
 *
 * - A TTL of a minute means a content edit is live within 60 seconds,
 *   with no deploy and no cache-busting step.
 * - Concurrent misses share ONE in-flight load (no stampede when the entry
 *   expires mid-spike).
 * - A failed load is not cached, so the next request retries.
 *
 * Per-user data (entitlements, attendance, saved sessions) never goes here.
 */

const TTL_MS = 60_000;

interface Entry<T> {
  value: Promise<T>;
  expiresAt: number;
}

const entries = new Map<string, Entry<unknown>>();

export function cached<T>(key: string, load: () => Promise<T>, ttlMs = TTL_MS): Promise<T> {
  const hit = entries.get(key) as Entry<T> | undefined;
  if (hit && hit.expiresAt > Date.now()) return hit.value;

  const value = load();
  entries.set(key, { value, expiresAt: Date.now() + ttlMs });
  value.catch(() => {
    // Don't serve a failure for a minute — drop it so the next call retries.
    if (entries.get(key)?.value === value) entries.delete(key);
  });
  return value;
}

/** For tests and the seed scripts' in-process callers. */
export function clearContentCache(): void {
  entries.clear();
}

// ── Loaders ─────────────────────────────────────────────────────────────────

/** All daily batches with their instructor — a superset every caller can use. */
export const allBatches = () =>
  cached('batches', () =>
    prisma.yogaBatch.findMany({
      include: { instructor: { select: { name: true, avatarUrl: true } } },
    }),
  );

export const allCategories = () =>
  cached('categories', () => prisma.yogaCategory.findMany({ orderBy: { sortOrder: 'asc' } }));

export const sessionsInCategory = (categoryId: string, take: number) =>
  cached(`sessions:${categoryId}:${take}`, () =>
    prisma.yogaSession.findMany({ where: { categoryId }, include: { instructor: true }, take }),
  );

export const freeSessions = (take: number) =>
  cached(`sessions:free:${take}`, () =>
    prisma.yogaSession.findMany({ where: { isFree: true }, include: { instructor: true }, take }),
  );

export const homeArticles = () =>
  cached('articles', () => prisma.article.findMany({ orderBy: { sortOrder: 'asc' }, take: 8 }));

export const homeQuotes = () =>
  cached('quotes', () => prisma.userQuote.findMany({ orderBy: { sortOrder: 'asc' }, take: 8 }));

export const homeReels = () =>
  cached('reels', () =>
    prisma.reel.findMany({ include: { instructor: true }, orderBy: { sortOrder: 'asc' }, take: 10 }),
  );

/**
 * Active campaigns, filtered to `now` in memory: the start/end window is
 * applied per request, so a cached list can't show a campaign early or late.
 */
export async function activePromos(now: Date) {
  const all = await cached('promos', () =>
    prisma.promoCampaign.findMany({ where: { active: true }, orderBy: { priority: 'desc' } }),
  );
  return all.filter((p) => (!p.startsAt || p.startsAt <= now) && (!p.endsAt || p.endsAt >= now));
}
