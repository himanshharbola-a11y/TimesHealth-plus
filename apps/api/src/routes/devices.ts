import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import type { NotificationKind, NotificationListResponse } from '@th/types';
import { prisma } from '../db.js';
import { isProd } from '../env.js';
import { sendPush } from '../services/push.js';

/**
 * Push token registration — PRD §11.
 *
 * Notifications are dual-channel in V1: WhatsApp continues and push is added,
 * and both fire. Push is therefore an enhancement, never the critical path —
 * a user who declines push loses nothing they cannot get on WhatsApp.
 *
 * Sending lives in services/push.ts (FCM). Only FCM tokens are delivered to;
 * the app registers an FCM token whenever the build has Firebase configured.
 */

const registerSchema = z.object({
  token: z.string().min(10).max(500),
  platform: z.enum(['ANDROID', 'IOS']),
  provider: z.enum(['EXPO', 'FCM', 'APNS']),
});

const routes: FastifyPluginAsync = async (app) => {
  app.post('/devices/push-token', { preHandler: app.requireAuth }, async (req, reply) => {
    const parsed = registerSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ code: 'INVALID_BODY', message: 'Invalid push token payload' });
    }
    const { token, platform, provider } = parsed.data;

    // Upsert on the token, not the user: a reinstall or a second account on the
    // same phone must move the token, never duplicate it, or one device would
    // receive another person's race-day alerts.
    await prisma.deviceToken.upsert({
      where: { token },
      create: { token, userId: req.user.id, platform, provider },
      update: { userId: req.user.id, platform, provider, lastSeenAt: new Date() },
    });
    return { registered: true };
  });

  /**
   * DEVELOPMENT ONLY — sends a push to the caller's own devices, to prove
   * delivery end to end. Hard-disabled in production.
   */
  app.post('/devices/test-push', { preHandler: app.requireAuth }, async (req, reply) => {
    if (isProd) return reply.code(404).send({ code: 'NOT_FOUND', message: 'Not found' });
    const devices = await prisma.deviceToken.count({ where: { userId: req.user.id } });
    const result = await sendPush(req.user.id, 'TEST', String(Date.now()), {
      title: 'TimesHealth+ test notification',
      body: 'Push notifications are working on this device.',
      route: '/(tabs)',
    });
    return { result, registeredDevices: devices };
  });

  /**
   * Called on logout so a shared phone stops receiving the previous user's
   * alerts. A POST with a body rather than a DELETE with the token in the URL:
   * DELETE bodies are poorly supported, and URLs end up in access logs.
   */
  app.post('/devices/push-token/remove', { preHandler: app.requireAuth }, async (req, reply) => {
    const parsed = z.object({ token: z.string().min(1).max(500) }).safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ code: 'INVALID_BODY', message: 'token required' });
    }
    await prisma.deviceToken.deleteMany({
      where: { token: parsed.data.token, userId: req.user.id },
    });
    return { removed: true };
  });

  /**
   * The inbox behind the TopHeader bell (design): every push this user was
   * actually sent in the last 30 days, newest first — so a reminder swiped
   * away from the shade is still findable, and the bell is never a dead tap.
   */
  app.get('/notifications', { preHandler: app.requireAuth }, async (req): Promise<NotificationListResponse> => {
    const rows = await prisma.notificationLog.findMany({
      where: {
        userId: req.user.id,
        delivered: { gt: 0 },
        kind: { not: 'TEST' },
        title: { not: null },
        sentAt: { gt: new Date(Date.now() - 30 * 86_400_000) },
      },
      orderBy: { sentAt: 'desc' },
      take: 50,
    });
    return {
      items: rows.map((r) => ({
        id: r.id,
        kind: r.kind as NotificationKind,
        title: r.title ?? '',
        body: r.body ?? '',
        route: r.route,
        sentAt: r.sentAt.toISOString(),
      })),
    };
  });
};

export default routes;
