import * as SecureStore from 'expo-secure-store';
import type { DigitalBib, MarathonEvent, RaceExpoInfo } from '@th/types';

/**
 * Race passes kept on the device for the stadium gate (PRD §8.3).
 *
 * A 20,000-runner venue is exactly where mobile data fails, so the pass has to
 * open from a cold start with no signal. Only what the pass displays is kept,
 * plus its 7-day offline signature — never a session token. Everything here is
 * wiped on sign-out so the next person on the phone never sees it.
 */

export interface OfflinePass {
  eventId: string;
  bibNumber: string;
  participantName: string;
  category: string;
  tier: 'CLASSIC' | 'PREMIUM';
  eventName: string;
  offlinePayload: string;
  savedAt: string;
  /**
   * Race-morning facts shown beside the pass — flag-off and the expo card —
   * so they too survive a no-signal start. Optional: passes saved by older
   * builds don't have them.
   */
  flagOffTime?: string | null;
  expo?: RaceExpoInfo | null;
}

/** Keep each stored pass comfortably inside SecureStore's per-value budget. */
const clip = (s: string, max: number) => (s.length > max ? `${s.slice(0, max - 1)}…` : s);

const INDEX_KEY = 'th_offline_passes';
const keyFor = (eventId: string) => `th_pass_${eventId.replace(/[^A-Za-z0-9._-]/g, '_')}`;

/**
 * Bumped by every wipe. A save carries the generation from when its fetch
 * STARTED, so a response that lands after sign-out can't write the previous
 * user's pass back.
 */
let generation = 0;
export const offlinePassGeneration = () => generation;

// Writes and wipes run one at a time, so a wipe never interleaves with a save
// and leaves a pass outside the index.
let queue: Promise<unknown> = Promise.resolve();
function serial<T>(task: () => Promise<T>): Promise<T> {
  const next = queue.then(task, task);
  queue = next.catch(() => undefined);
  return next;
}

/** Expiry of the offline signature (`ref.userId.expires.sig`), in ms. */
function expiresAtMs(payload: string): number {
  const expires = Number(payload.split('.')[2]);
  return Number.isFinite(expires) ? expires * 1000 : 0;
}

async function readIndex(): Promise<string[]> {
  try {
    const raw = await SecureStore.getItemAsync(INDEX_KEY);
    const ids: unknown = raw ? JSON.parse(raw) : [];
    return Array.isArray(ids) ? ids.filter((id): id is string => typeof id === 'string') : [];
  } catch {
    return [];
  }
}

async function read(eventId: string): Promise<OfflinePass | null> {
  try {
    const raw = await SecureStore.getItemAsync(keyFor(eventId));
    const pass = raw ? (JSON.parse(raw) as OfflinePass) : null;
    if (!pass || pass.eventId !== eventId || typeof pass.offlinePayload !== 'string') return null;
    // Past its signature the gate would reject it — don't offer it.
    return expiresAtMs(pass.offlinePayload) > Date.now() ? pass : null;
  } catch {
    return null;
  }
}

export function saveOfflinePass(
  eventId: string,
  bib: DigitalBib,
  gen: number,
  event?: Pick<MarathonEvent, 'flagOffTime' | 'expo'> | null,
): Promise<void> {
  return serial(async () => {
    if (gen !== generation) return;
    const pass: OfflinePass = {
      eventId,
      bibNumber: bib.bibNumber,
      participantName: bib.participantName,
      category: bib.category,
      tier: bib.tier,
      eventName: bib.eventName,
      offlinePayload: bib.offlinePayload,
      savedAt: new Date().toISOString(),
      flagOffTime: event?.flagOffTime ?? null,
      expo: event?.expo
        ? {
            ...event.expo,
            venue: clip(event.expo.venue, 160),
            instructions: clip(event.expo.instructions, 400),
            requiredDocuments: event.expo.requiredDocuments.slice(0, 6),
          }
        : null,
    };
    await SecureStore.setItemAsync(keyFor(eventId), JSON.stringify(pass));
    const ids = await readIndex();
    if (!ids.includes(eventId)) {
      await SecureStore.setItemAsync(INDEX_KEY, JSON.stringify([...ids, eventId]));
    }
  });
}

/** The server says there is no bib for this event (any more): forget ours. */
export function removeOfflinePass(eventId: string, gen: number): Promise<void> {
  return serial(async () => {
    if (gen !== generation) return;
    const ids = await readIndex();
    if (!ids.includes(eventId)) return;
    await SecureStore.deleteItemAsync(keyFor(eventId));
    await SecureStore.setItemAsync(INDEX_KEY, JSON.stringify(ids.filter((id) => id !== eventId)));
  });
}

export function loadOfflinePass(eventId: string): Promise<OfflinePass | null> {
  return serial(() => read(eventId));
}

/** Every pass still inside its signature window, in the order they were saved. */
export function listOfflinePasses(): Promise<OfflinePass[]> {
  return serial(async () => {
    const passes = await Promise.all((await readIndex()).map(read));
    return passes.filter((p): p is OfflinePass => p !== null);
  });
}

export function clearOfflinePasses(): Promise<void> {
  generation += 1;
  return serial(async () => {
    const ids = await readIndex();
    await Promise.all(ids.map((id) => SecureStore.deleteItemAsync(keyFor(id)).catch(() => undefined)));
    await SecureStore.deleteItemAsync(INDEX_KEY).catch(() => undefined);
  });
}
