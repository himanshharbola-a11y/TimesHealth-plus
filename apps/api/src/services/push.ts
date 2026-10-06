import admin from 'firebase-admin';
import { prisma } from '../db.js';
import { initFirebaseApp } from '../identity/firebase.js';

/**
 * Push delivery — PRD §11.
 *
 * Sent straight to Firebase Cloud Messaging with the project's service
 * account. WhatsApp remains the critical path (§11: "both fire"); push is the
 * enhancement, so every failure here is logged and swallowed, never thrown
 * into the request or scheduler that triggered it.
 *
 * EXACTLY ONCE, AT SCALE:
 *   1. Claim: one transaction inserts a NotificationLog row per recipient
 *      (delivered = -1, "claimed"). A Postgres advisory lock serialises claims,
 *      so two API instances can never both claim the same (user, kind,
 *      occurrence) — the unique constraint is the backstop.
 *   2. Send: FCM sendEach in chunks of 500 messages — one HTTP call per 500
 *      users instead of one per user (a popular batch's reminders must finish
 *      inside their 5-minute window).
 *   3. Settle: delivered rows get their count; a recipient whose send failed
 *      TRANSIENTLY has its claim released, so the next tick inside the window
 *      retries. A claim left at -1 by a crash mid-send is released by the
 *      scheduler after a few minutes (releaseStaleClaims) for the same reason.
 */

export type PushKind =
  | 'SESSION_REMINDER'
  | 'SESSION_LIVE'
  | 'RACE_COUNTDOWN'
  | 'RACE_DAY_INFO'
  | 'RESULT_PUBLISHED'
  | 'TEST';

export interface PushMessage {
  title: string;
  body: string;
  /** In-app route opened when the notification is tapped, e.g. "/race/delhi_half". */
  route?: string;
}

export interface PushRecipient {
  userId: string;
  /** Identifies the occurrence, e.g. "b2:2026-10-05" — unique per user and kind. */
  dedupeKey: string;
  message: PushMessage;
}

export type PushResult = 'sent' | 'duplicate' | 'no-devices' | 'disabled' | 'failed';

/**
 * FCM errors that mean the token is gone for good and should be forgotten.
 * Deliberately NOT included: messaging/invalid-argument — it can also mean our
 * own payload was malformed, and treating that as a dead token would wipe
 * every user's registration the first time we shipped a bad message.
 */
const DEAD_TOKEN_CODES = new Set([
  'messaging/registration-token-not-registered',
  'messaging/invalid-registration-token',
]);

const CLAIM_LOCK_KEY = 731_100;
const FCM_CHUNK = 500;
const CLAIMED = -1;

/** Sends one push to one user. Thin wrapper over sendPushBatch. */
export async function sendPush(
  userId: string,
  kind: PushKind,
  dedupeKey: string,
  message: PushMessage,
): Promise<PushResult> {
  const results = await sendPushBatch(kind, [{ userId, dedupeKey, message }]);
  return results.get(userId) ?? 'failed';
}

/** Sends one KIND of push to many users. Returns each user's outcome. */
export async function sendPushBatch(
  kind: PushKind,
  recipients: PushRecipient[],
): Promise<Map<string, PushResult>> {
  const outcome = new Map<string, PushResult>();
  if (recipients.length === 0) return outcome;
  // Needs the Firebase APP (FCM credential), not the auth provider — push
  // keeps working after the Times SSO swap.
  if (!initFirebaseApp()) {
    for (const r of recipients) outcome.set(r.userId, 'disabled');
    return outcome;
  }

  try {
    // Devices first: a user who never enabled push gets no log row, so if they
    // turn notifications on later they are still eligible inside the window.
    const userIds = [...new Set(recipients.map((r) => r.userId))];
    const devices = await prisma.deviceToken.findMany({
      where: { userId: { in: userIds }, provider: 'FCM' },
      select: { userId: true, token: true },
    });
    const tokensByUser = new Map<string, string[]>();
    for (const d of devices) tokensByUser.set(d.userId, [...(tokensByUser.get(d.userId) ?? []), d.token]);

    const reachable = recipients.filter((r) => {
      if (tokensByUser.has(r.userId)) return true;
      outcome.set(r.userId, 'no-devices');
      return false;
    });
    if (reachable.length === 0) return outcome;

    const claimed = await claim(kind, reachable);
    for (const r of reachable) if (!claimed.has(r.userId)) outcome.set(r.userId, 'duplicate');
    const toSend = reachable.filter((r) => claimed.has(r.userId));
    if (toSend.length === 0) return outcome;

    // One FCM message per device token.
    const messages: { userId: string; token: string; msg: admin.messaging.Message }[] = [];
    for (const r of toSend) {
      for (const token of tokensByUser.get(r.userId) ?? []) {
        messages.push({
          userId: r.userId,
          token,
          msg: {
            token,
            notification: { title: r.message.title, body: r.message.body },
            data: { kind, ...(r.message.route ? { route: r.message.route } : {}) },
            android: {
              priority: 'high',
              // Matches the channel the app creates (src/lib/notifications.ts).
              notification: { channelId: 'reminders', color: '#E8533A' },
            },
          },
        });
      }
    }

    const delivered = new Map<string, number>();
    const transientFailure = new Set<string>();
    const deadTokens: string[] = [];

    for (let i = 0; i < messages.length; i += FCM_CHUNK) {
      const chunk = messages.slice(i, i + FCM_CHUNK);
      try {
        const res = await admin.messaging().sendEach(chunk.map((c) => c.msg));
        res.responses.forEach((r, j) => {
          const c = chunk[j]!;
          if (r.success) delivered.set(c.userId, (delivered.get(c.userId) ?? 0) + 1);
          else if (DEAD_TOKEN_CODES.has(r.error?.code ?? '')) deadTokens.push(c.token);
          else transientFailure.add(c.userId);
        });
      } catch (err) {
        // The whole call failed (network, quota): every user in it can retry.
        for (const c of chunk) transientFailure.add(c.userId);
        console.error('[push] FCM call failed', { kind, error: String(err) });
      }
    }

    if (deadTokens.length > 0) {
      await prisma.deviceToken.deleteMany({ where: { token: { in: deadTokens } } });
    }

    const sentIds: string[] = [];
    const releaseIds: string[] = [];
    const noneIds: string[] = [];
    for (const r of toSend) {
      const rowId = claimed.get(r.userId)!;
      if ((delivered.get(r.userId) ?? 0) > 0) {
        sentIds.push(rowId);
        outcome.set(r.userId, 'sent');
      } else if (transientFailure.has(r.userId)) {
        releaseIds.push(rowId); // retry next tick, still inside the window
        outcome.set(r.userId, 'failed');
      } else {
        noneIds.push(rowId); // every token dead: nothing to retry
        outcome.set(r.userId, 'no-devices');
      }
    }
    if (sentIds.length) await prisma.notificationLog.updateMany({ where: { id: { in: sentIds } }, data: { delivered: 1 } });
    if (noneIds.length) await prisma.notificationLog.updateMany({ where: { id: { in: noneIds } }, data: { delivered: 0 } });
    if (releaseIds.length) await prisma.notificationLog.deleteMany({ where: { id: { in: releaseIds } } });
    return outcome;
  } catch (err) {
    console.error('[push] batch failed', { kind, error: String(err) });
    for (const r of recipients) if (!outcome.has(r.userId)) outcome.set(r.userId, 'failed');
    return outcome;
  }
}

/**
 * Claims sends for recipients not already notified for this occurrence.
 * Returns userId → the claimed NotificationLog row id.
 */
async function claim(kind: PushKind, recipients: PushRecipient[]): Promise<Map<string, string>> {
  return prisma.$transaction(async (tx) => {
    // Serialise claims across API instances for the length of this
    // transaction; released automatically at commit.
    await tx.$executeRaw`SELECT pg_advisory_xact_lock(${CLAIM_LOCK_KEY})`;

    const keys = [...new Set(recipients.map((r) => r.dedupeKey))];
    const existing = await tx.notificationLog.findMany({
      where: { kind, dedupeKey: { in: keys }, userId: { in: recipients.map((r) => r.userId) } },
      select: { userId: true, dedupeKey: true },
    });
    const done = new Set(existing.map((e) => `${e.userId}|${e.dedupeKey}`));
    const fresh = recipients.filter((r) => !done.has(`${r.userId}|${r.dedupeKey}`));
    if (fresh.length === 0) return new Map<string, string>();

    await tx.notificationLog.createMany({
      data: fresh.map((r) => ({
        userId: r.userId,
        kind,
        dedupeKey: r.dedupeKey,
        delivered: CLAIMED,
        title: r.message.title,
        body: r.message.body,
        route: r.message.route ?? null,
      })),
      skipDuplicates: true,
    });
    const rows = await tx.notificationLog.findMany({
      where: { kind, dedupeKey: { in: keys }, userId: { in: fresh.map((r) => r.userId) }, delivered: CLAIMED },
      select: { id: true, userId: true, dedupeKey: true },
    });
    const wanted = new Set(fresh.map((r) => `${r.userId}|${r.dedupeKey}`));
    return new Map(rows.filter((row) => wanted.has(`${row.userId}|${row.dedupeKey}`)).map((row) => [row.userId, row.id]));
  });
}

/**
 * A claim still at -1 after a few minutes means the process died between
 * claiming and sending. Releasing it lets the next tick inside the window
 * send — "never zero times" holds across crashes and restarts too.
 */
export async function releaseStaleClaims(now: Date): Promise<number> {
  const res = await prisma.notificationLog.deleteMany({
    where: { delivered: CLAIMED, sentAt: { lt: new Date(now.getTime() - 3 * 60_000) } },
  });
  return res.count;
}

/** The inbox keeps 90 days; older log rows are deleted so the table stays small. */
export async function pruneNotificationLog(now: Date): Promise<number> {
  const res = await prisma.notificationLog.deleteMany({
    where: { sentAt: { lt: new Date(now.getTime() - 90 * 86_400_000) } },
  });
  return res.count;
}
