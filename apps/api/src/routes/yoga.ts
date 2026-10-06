import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import type {
  JoinSessionResponse,
  YogaCatalogResponse,
  YogaTodayResponse,
} from '@th/types';
import { prisma } from '../db.js';
import { canAccessLiveYoga, canAccessYogaTracker, resolveEntitlements } from '../services/entitlements.js';
import { attendableBatch, getAttendance, recordAttendance } from '../services/attendance.js';
import { getTodaySchedule } from '../services/feed.js';
import { allBatches } from '../services/contentCache.js';
import { signPlaybackUrl, verifyWaJoinToken } from '../services/media.js';
import { env } from '../env.js';
import { now as clockNow } from '../clock.js';
import { batchInstant, byClockTime, formatBatchTime, inJoinWindow, isLiveNow } from '../time.js';

const joinSchema = z.object({ batchId: z.string().min(1) });
const slotSchema = z.object({ batchId: z.string().min(1) });

const routes: FastifyPluginAsync = async (app) => {
  /** All 8 daily batches, with the user's reminder slot flagged (§7.1). */
  app.get('/yoga/today', { preHandler: app.requireAuth }, async (req): Promise<YogaTodayResponse> => {
    const now = new Date();
    const [{ entitlements }, batches, schedule] = await Promise.all([
      resolveEntitlements(req.user.id, now),
      allBatches(),
      getTodaySchedule(now),
    ]);

    const reminderSlotId = entitlements.yoga?.reminderSlotId ?? null;

    return {
      batches: [...batches].sort(byClockTime).map((b) => {
        const startsAt = batchInstant(b.time, 0, now);
        return {
          id: b.id,
          title: b.title,
          time: b.time,
          period: b.period === 'EVENING' ? 'EVENING' : 'MORNING',
          instructorName: b.instructor.name,
          isUserReminderSlot: b.id === reminderSlotId,
          isLiveNow: isLiveNow(startsAt, now),
          startsAt: startsAt.toISOString(),
          endsAt: new Date(startsAt.getTime() + 60 * 60_000).toISOString(),
        };
      }),
      liveBatchId: schedule.liveBatch?.id ?? null,
      nextBatchId: schedule.nextBatch?.id ?? null,
      nextSessionStartsAt: schedule.nextBatch?.startsAt.toISOString() ?? null,
    };
  });

  /**
   * Joining a live class. Two things happen here and the order matters:
   * the entitlement is checked, then attendance is written server-side.
   *
   * Attendance is NEVER taken from a client assertion (docs/04 T9). The same
   * recordAttendance() call backs the WhatsApp redirect below, which is what
   * makes the ledger single-source (§7.1).
   */
  app.post('/yoga/join', { preHandler: app.requireAuth }, async (req, reply): Promise<JoinSessionResponse | undefined> => {
    const parsed = joinSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ code: 'INVALID_BODY', message: 'batchId required' });
    }

    const { entitlements } = await resolveEntitlements(req.user.id);
    if (!canAccessLiveYoga(entitlements)) {
      return reply.code(403).send({ code: 'NOT_ENTITLED', message: 'Yoga subscription required' });
    }

    const batch = await prisma.yogaBatch.findUnique({ where: { id: parsed.data.batchId } });
    if (!batch) {
      return reply.code(404).send({ code: 'NOT_FOUND', message: 'Batch not found' });
    }

    // Attendance means a class to attend: the link opens with the wait room
    // (1h before) and closes when the class ends. §7.1: joining LATE still
    // counts — so there is no lateness cut-off inside that window — but a tap
    // at 10 PM for tomorrow's batch is not a class and must not be a streak day.
    const now = clockNow();
    if (!inJoinWindow(batchInstant(batch.time, 0, now), now)) {
      return reply.code(409).send({
        code: 'CLASS_NOT_OPEN',
        message: `${batch.title} isn't open yet — the class link opens an hour before ${formatBatchTime(batch.time)}.`,
      });
    }

    const result = await recordAttendance(req.user.id, batch.id, 'APP', now);

    return {
      // TODO(yoga-team): replace with the per-user join link from B4.
      // Until that exists this is the shared class link, which means a
      // WhatsApp join cannot be attributed. See docs/01 §B4/B5.
      joinUrl: process.env.YOGA_CLASS_LINK ?? 'https://timeshealthplus.invalid/live',
      mode: 'EXTERNAL_APP',
      attendanceRecorded: result.recorded,
      source: 'APP',
    };
  });

  /**
   * The WhatsApp class link points here, carrying a SIGNED user token
   * (services/media.ts signWaJoinToken), and we redirect on to the room. This
   * is the hook that lets a WhatsApp join write to the same ledger as an app
   * join — the cheapest of the three options in docs/01 §B4/B5. It proves
   * intent-to-join rather than presence.
   *
   * Every outcome is a redirect — someone tapping a link in WhatsApp must
   * never land on a JSON error:
   *   - unsigned, forged or expired token, or no live subscription → the yoga
   *     landing page, never the class room
   *   - a member → the class room, and attendance is recorded only while a
   *     class is actually open (same window as the in-app join)
   */
  app.get('/yoga/wa-join', async (req, reply) => {
    const q = req.query as { t?: string; b?: string };
    const classLink = process.env.YOGA_CLASS_LINK ?? 'https://timeshealthplus.invalid/live';

    const userId = typeof q.t === 'string' ? verifyWaJoinToken(q.t) : null;
    if (!userId) return reply.redirect(env.yogaRenewUrl);

    const { entitlements } = await resolveEntitlements(userId);
    if (!canAccessLiveYoga(entitlements)) return reply.redirect(env.yogaRenewUrl);

    try {
      const now = clockNow();
      const batch = await attendableBatch(now, typeof q.b === 'string' ? q.b : null);
      if (batch) await recordAttendance(userId, batch.id, 'WHATSAPP', now);
    } catch (err) {
      // The class matters more than the mark: log it and still let them in.
      req.log.error({ err: String(err) }, 'wa-join attendance write failed');
    }
    return reply.redirect(classLink);
  });

  /** Tracker — §7.1. Subscriber only; the tracker does not exist for free users. */
  app.get('/yoga/attendance', { preHandler: app.requireAuth }, async (req, reply) => {
    const { entitlements } = await resolveEntitlements(req.user.id);
    if (!canAccessYogaTracker(entitlements)) {
      return reply.code(403).send({ code: 'NOT_ENTITLED', message: 'Yoga subscription required' });
    }
    const sub = await prisma.yogaSubscription.findUnique({ where: { userId: req.user.id } });
    return getAttendance(req.user.id, sub?.startedAt ?? null);
  });

  app.put('/yoga/reminder-slot', { preHandler: app.requireAuth }, async (req, reply) => {
    const parsed = slotSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ code: 'INVALID_BODY', message: 'batchId required' });
    }
    const sub = await prisma.yogaSubscription.findUnique({ where: { userId: req.user.id } });
    if (!sub) {
      return reply.code(403).send({ code: 'NOT_ENTITLED', message: 'No active subscription' });
    }
    // A stale id would silently switch reminders off (the scheduler matches on it).
    const batch = await prisma.yogaBatch.findUnique({ where: { id: parsed.data.batchId }, select: { id: true } });
    if (!batch) {
      return reply.code(404).send({ code: 'NOT_FOUND', message: 'Batch not found' });
    }
    await prisma.yogaSubscription.update({
      where: { userId: req.user.id },
      data: { reminderSlotId: parsed.data.batchId },
    });
    return { ok: true };
  });

  /** Session explorer catalogue — categories plus the full library. */
  app.get('/yoga/catalog', { preHandler: app.requireAuth }, async (req): Promise<YogaCatalogResponse> => {
    const { persona } = await resolveEntitlements(req.user.id);
    const [categories, sessions] = await Promise.all([
      prisma.yogaCategory.findMany({ orderBy: { sortOrder: 'asc' } }),
      prisma.yogaSession.findMany({ include: { instructor: true } }),
    ]);

    return {
      categories: categories.map((c) => ({
        id: c.id,
        name: c.name,
        tagline: c.tagline,
        bodyTargetSummary: c.bodyTargetSummary,
        imageUrl: c.imageUrl,
        bannerTheme: c.bannerTheme as never,
        sessionCount: sessions.filter((s) => s.categoryId === c.id).length,
        totalYogisJoined: c.totalYogisJoined,
      })),
      sessions: sessions.map((s) => ({
        id: s.id,
        categoryId: s.categoryId,
        title: s.title,
        description: s.description,
        imageUrl: s.imageUrl,
        durationMinutes: s.durationMinutes,
        level: s.level,
        intensity: s.intensity,
        caloriesBurned: s.caloriesBurned,
        isFree: s.isFree,
        isLive: false,
        startsAt: null,
        bodyFocusTitle: s.bodyFocusTitle,
        targetBodyParts: s.targetBodyParts,
        keyPoses: s.keyPoses,
        lifestyleImpact: s.lifestyleImpact,
        instructor: {
          id: s.instructor.id,
          name: s.instructor.name,
          title: s.instructor.title,
          specialty: s.instructor.specialty,
          bio: s.instructor.bio,
          experience: s.instructor.experience,
          avatarUrl: s.instructor.avatarUrl,
          rating: s.instructor.rating,
          handle: s.instructor.handle,
          reelCount: s.instructor.reelCount,
        },
        joinedCountTillDate: s.joinedCountTillDate,
        todayActiveCount: s.todayActiveCount,
        playbackUrl:
          s.mediaKey && (s.isFree || persona.hasYoga)
            ? signPlaybackUrl(s.mediaKey, req.user.id)
            : null,
      })),
    };
  });

  /**
   * A FRESH signed playback URL, fetched when the player opens. The catalogue's
   * URLs are signed for a few minutes and cached by the app for as long, so
   * playing straight from that cache could hand the CDN an expired signature.
   */
  app.get('/yoga/sessions/:id/playback', { preHandler: app.requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const session = await prisma.yogaSession.findUnique({
      where: { id },
      select: { mediaKey: true, isFree: true },
    });
    if (!session?.mediaKey) {
      return reply.code(404).send({ code: 'NOT_FOUND', message: 'This video isn’t available' });
    }
    if (!session.isFree) {
      const { persona } = await resolveEntitlements(req.user.id);
      if (!persona.hasYoga) {
        return reply.code(403).send({ code: 'NOT_ENTITLED', message: 'Yoga subscription required' });
      }
    }
    return { playbackUrl: signPlaybackUrl(session.mediaKey, req.user.id) };
  });

  /** Save / complete toggles — design additions, synced so they survive reinstall. */
  //
  // Both take the state the user WANTS ({ saved: true }), so a double tap or a
  // retried request lands on the same answer instead of flipping it back.
  // With no body they toggle, as older app builds expect.
  app.post('/yoga/sessions/:id/save', { preHandler: app.requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const want = z.object({ saved: z.boolean().optional() }).safeParse(req.body ?? {});
    if (!want.success) return reply.code(400).send({ code: 'INVALID_BODY', message: 'saved must be a boolean' });
    const key = { userId: req.user.id, sessionId: id };
    const saved =
      want.data.saved ?? !(await prisma.savedSession.findUnique({ where: { userId_sessionId: key } }));
    if (saved) {
      if (!(await prisma.yogaSession.findUnique({ where: { id }, select: { id: true } }))) {
        return reply.code(404).send({ code: 'NOT_FOUND', message: 'Session not found' });
      }
      await prisma.savedSession.createMany({ data: [key], skipDuplicates: true });
    } else {
      await prisma.savedSession.deleteMany({ where: key });
    }
    return { saved };
  });

  app.post('/yoga/sessions/:id/complete', { preHandler: app.requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const want = z.object({ completed: z.boolean().optional() }).safeParse(req.body ?? {});
    if (!want.success) return reply.code(400).send({ code: 'INVALID_BODY', message: 'completed must be a boolean' });
    const key = { userId: req.user.id, sessionId: id };
    const completed =
      want.data.completed ?? !(await prisma.completedSession.findUnique({ where: { userId_sessionId: key } }));
    if (completed) {
      if (!(await prisma.yogaSession.findUnique({ where: { id }, select: { id: true } }))) {
        return reply.code(404).send({ code: 'NOT_FOUND', message: 'Session not found' });
      }
      // Note: completing a recording does NOT write attendance (§7.1).
      await prisma.completedSession.createMany({ data: [key], skipDuplicates: true });
    } else {
      await prisma.completedSession.deleteMany({ where: key });
    }
    return { completed };
  });

  app.get('/yoga/me/sessions', { preHandler: app.requireAuth }, async (req) => {
    const [saved, completed] = await Promise.all([
      prisma.savedSession.findMany({ where: { userId: req.user.id }, select: { sessionId: true } }),
      prisma.completedSession.findMany({ where: { userId: req.user.id }, select: { sessionId: true } }),
    ]);
    return {
      savedSessionIds: saved.map((s) => s.sessionId),
      completedSessionIds: completed.map((s) => s.sessionId),
    };
  });
};

export default routes;
