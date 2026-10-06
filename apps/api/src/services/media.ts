import { createHmac } from 'node:crypto';
import { env } from '../env.js';

/**
 * Entitlement-gated playback URLs — docs/04 T4.
 *
 * A signed, short-TTL URL bound to the user and the asset. Never hand out a
 * permanent media URL: the full session library is the main thing the yoga
 * subscription buys, and a stable URL is a link anyone can forward.
 *
 * In production this signs a CDN path (CloudFront/Cloudflare signed URLs).
 * The signature shape here is deliberately the same so swapping the CDN is a
 * one-function change.
 */
export function signPlaybackUrl(
  mediaKey: string,
  userId: string,
  now: Date = new Date(),
): string {
  const expires = Math.floor(now.getTime() / 1000) + env.mediaUrlTtlSeconds;
  const payload = `${mediaKey}:${userId}:${expires}`;
  const sig = createHmac('sha256', env.mediaSigningSecret).update(payload).digest('hex');
  const url = new URL(`${env.mediaBaseUrl}/${mediaKey}`);
  url.searchParams.set('u', userId);
  url.searchParams.set('e', String(expires));
  url.searchParams.set('s', sig);
  return url.toString();
}

export function verifyPlaybackUrl(
  mediaKey: string,
  userId: string,
  expires: number,
  sig: string,
  now: Date = new Date(),
): boolean {
  if (expires * 1000 < now.getTime()) return false;
  const expected = createHmac('sha256', env.mediaSigningSecret)
    .update(`${mediaKey}:${userId}:${expires}`)
    .digest('hex');
  return timingSafeEqualHex(expected, sig);
}

/**
 * Digital bib QR — docs/04 T1.
 *
 * The QR encodes a signed token, never a bare bib number, in two KINDS:
 *   LIVE — ~60s TTL, refreshed while the pass is on screen. A screenshot of
 *          someone else's pass stops scanning within a minute.
 *   PASS — 7-day signature kept on the phone for venues with no signal. The
 *          app shows it ONLY when it can't fetch a live one, and the scanner
 *          is told which kind it saw (and of every earlier scan), so a reused
 *          screenshot is visible at the counter.
 * The kind is inside the HMAC, so one can never be passed off as the other.
 * The token carries the registration ref only — no internal user id.
 */
export type BibTokenKind = 'LIVE' | 'PASS';

function bibSignature(kind: BibTokenKind, ref: string, expires: number): string {
  return createHmac('sha256', env.bibSigningSecret).update(`bib:${kind}:${ref}:${expires}`).digest('hex');
}

export function signBibToken(
  registrationRef: string,
  kind: BibTokenKind = 'LIVE',
  now: Date = new Date(),
): { token: string; expiresAt: Date } {
  const ttl = kind === 'LIVE' ? env.bibTokenTtlSeconds : 7 * 24 * 3600;
  const expires = Math.floor(now.getTime() / 1000) + ttl;
  return {
    token: `${kind}.${registrationRef}.${expires}.${bibSignature(kind, registrationRef, expires)}`,
    expiresAt: new Date(expires * 1000),
  };
}

export interface VerifiedBib {
  registrationRef: string;
  kind: BibTokenKind;
}

/**
 * Returns the bib identity only when the signature is valid and unexpired.
 * Used by the expo scanner endpoint. Never trust the ref without this.
 */
export function verifyBibToken(token: string, now: Date = new Date()): VerifiedBib | null {
  const parts = token.split('.');
  if (parts.length !== 4) return null;
  const [kind, ref, expiresRaw, sig] = parts as [string, string, string, string];
  if (kind !== 'LIVE' && kind !== 'PASS') return null;

  const expires = Number(expiresRaw);
  if (!ref || !Number.isFinite(expires) || expires * 1000 < now.getTime()) return null;
  if (!timingSafeEqualHex(bibSignature(kind, ref, expires), sig)) return null;

  return { registrationRef: ref, kind };
}

/**
 * Per-user WhatsApp class link — docs/01 §B4 option 2.
 *
 * The daily WhatsApp message carries `/v1/yoga/wa-join?t=<token>`. The token
 * names the user, so a WhatsApp join writes to the same attendance ledger as
 * an app join (§7.1 single-source) — and it is SIGNED, so nobody can mark
 * someone else present by guessing their id.
 *
 * Valid for 48h: a link sent the evening before still works next morning.
 * The WhatsApp sender (BSP integration) builds links with buildWhatsAppJoinUrl.
 */
export function signWaJoinToken(userId: string, ttlSeconds = 48 * 3600, now: Date = new Date()): string {
  const expires = Math.floor(now.getTime() / 1000) + ttlSeconds;
  const sig = createHmac('sha256', env.waJoinSigningSecret)
    .update(`wa:${userId}:${expires}`)
    .digest('hex');
  return `${userId}.${expires}.${sig}`;
}

/** The user id when the signature is valid and unexpired; otherwise null. */
export function verifyWaJoinToken(token: string, now: Date = new Date()): string | null {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const [userId, expiresRaw, sig] = parts as [string, string, string];
  const expires = Number(expiresRaw);
  if (!userId || !Number.isFinite(expires) || expires * 1000 < now.getTime()) return null;
  const expected = createHmac('sha256', env.waJoinSigningSecret)
    .update(`wa:${userId}:${expires}`)
    .digest('hex');
  return timingSafeEqualHex(expected, sig) ? userId : null;
}

export function buildWhatsAppJoinUrl(apiBaseUrl: string, userId: string, batchId?: string): string {
  const url = new URL('/v1/yoga/wa-join', apiBaseUrl);
  url.searchParams.set('t', signWaJoinToken(userId));
  if (batchId) url.searchParams.set('b', batchId);
  return url.toString();
}

function timingSafeEqualHex(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
