import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import type { ContentResponse, WorkshopListResponse } from '@th/types';
import { prisma } from '../db.js';
import { resolveEntitlements } from '../services/entitlements.js';
import { loadWorkshopsFor } from '../services/workshops.js';
import { lockWorkshop } from './orders.js';

/**
 * Yoga FAQs — copy from the design prototype. Kept server-side (later: CMS).
 *
 * ⚠ LAUNCH CHECK: the WhatsApp answer is only TRUE once the yoga team sends
 * per-user class links (docs/01 §B4) — today a WhatsApp join is not attributed
 * to anyone. If that work slips past launch, change this answer here; no app
 * release is needed.
 */
// Design order (YogaScreen FAQ).
const YOGA_FAQS: ContentResponse['yogaFaqs'] = [
  {
    question: 'Can I attend a batch other than my chosen slot?',
    answer:
      'Yes! Your chosen slot sets your calendar notification, but you can attend any of the 8 batches morning or evening using the same single pass.',
  },
  {
    question: 'Does watching a recording count toward my streak?',
    answer:
      'No. Per the single-source rule, live session participation counts toward streaks to foster genuine daily practice consistency.',
  },
  {
    question: 'Can I join from WhatsApp or only the app?',
    answer:
      'Both fire to the exact same attendance ledger! Tapping the WhatsApp class link updates your app streak automatically.',
  },
];

/**
 * Chief mentor — the design prototype's own placeholder (it names "Surakshit
 * Goswami, Master Teacher at The Yoga Institute"). The real name, photo and bio
 * must come from the yoga team before launch.
 */
const MENTOR: ContentResponse['mentor'] = {
  name: 'Surakshit Goswami',
  title: 'Chief Mentor · Master Teacher at The Yoga Institute',
  bio: 'Leads 12 monthly deep-dive intensives and guides the curriculum every TimesHealth+ batch is taught from.',
  avatarUrl: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?auto=format&fit=crop&w=300&q=80',
};

const routes: FastifyPluginAsync = async (app) => {
  app.get('/content', { preHandler: app.requireAuth }, async (): Promise<ContentResponse> => {
    const [articles, quotes, instructors, reels] = await Promise.all([
      prisma.article.findMany({ orderBy: { sortOrder: 'asc' } }),
      prisma.userQuote.findMany({ orderBy: { sortOrder: 'asc' } }),
      prisma.instructor.findMany(),
      prisma.reel.findMany({ include: { instructor: true }, orderBy: { sortOrder: 'asc' }, take: 10 }),
    ]);
    return {
      articles: articles.map((a) => ({
        id: a.id,
        title: a.title,
        source: a.source,
        category: a.category,
        imageUrl: a.imageUrl,
        readTimeMinutes: a.readTimeMinutes,
        url: a.url,
      })),
      quotes: quotes.map((q) => ({ id: q.id, quote: q.quote, author: q.author, role: q.role })),
      instructors: instructors.map((i) => ({
        id: i.id,
        name: i.name,
        title: i.title,
        specialty: i.specialty,
        bio: i.bio,
        experience: i.experience,
        avatarUrl: i.avatarUrl,
        rating: i.rating,
        handle: i.handle,
        reelCount: i.reelCount,
      })),
      yogaFaqs: YOGA_FAQS,
      mentor: MENTOR,
      reels: reels.map((r) => ({
        id: r.id,
        instructorId: r.instructorId,
        instructorName: r.instructor.name,
        instructorHandle: r.instructor.handle ?? '',
        thumbnailUrl: r.thumbnailUrl,
        playbackUrl: r.playbackUrl,
        durationSeconds: r.durationSeconds,
      })),
    };
  });

  /**
   * Live workshops — present in the design, absent from the PRD; in scope by
   * explicit decision. Capacity-limited, so spotsRemaining is computed from
   * live registration counts rather than stored and drifting.
   */
  app.get('/workshops', { preHandler: app.requireAuth }, async (req): Promise<WorkshopListResponse> => {
    const { persona } = await resolveEntitlements(req.user.id);
    return { workshops: await loadWorkshopsFor(req.user.id, persona.hasYoga) };
  });

  // Takes the state the user WANTS ({ registered: true | false }), so a double
  // tap or a network retry can't cancel the seat it just booked. With no body
  // it toggles, as older app builds expect.
  app.post('/workshops/:id/register', { preHandler: app.requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const want = z.object({ registered: z.boolean().optional() }).safeParse(req.body ?? {});
    if (!want.success) {
      return reply.code(400).send({ code: 'INVALID_BODY', message: 'registered must be a boolean' });
    }

    const existing = await prisma.workshopRegistration.findUnique({
      where: { userId_workshopId: { userId: req.user.id, workshopId: id } },
    });
    const register = want.data.registered ?? !existing;
    // Already in the state asked for: nothing to do (idempotent).
    if (register && existing) return { registered: true };
    if (!register && !existing) return { registered: false };
    if (!register && existing) {
      // Cancelling a paid seat here would simply forfeit the money. Refunds
      // are an operations decision, so paid seats are cancelled via support.
      const paid = await prisma.order.findFirst({
        where: { userId: req.user.id, productType: 'WORKSHOP', productId: id, status: 'PAID' },
        select: { id: true },
      });
      if (paid) {
        return reply.code(409).send({
          code: 'PAID_SEAT',
          message: 'Paid seats are cancelled through support so your refund can be processed.',
        });
      }
      await prisma.workshopRegistration.delete({ where: { id: existing.id } });
      return { registered: false };
    }

    // A paid seat must go through POST /orders, where the price is resolved
    // server-side and the seat is granted on payment. Only seats that are free
    // for this user — yoga workshops for yoga subscribers — register directly.
    const [{ persona }, target] = await Promise.all([
      resolveEntitlements(req.user.id),
      prisma.liveWorkshop.findUnique({ where: { id }, select: { category: true, pricePaise: true } }),
    ]);
    if (!target) {
      return reply.code(404).send({ code: 'NOT_FOUND', message: 'Workshop not found' });
    }
    const freeForUser = target.pricePaise === null || (persona.hasYoga && target.category === 'YOGA');
    if (!freeForUser) {
      return reply.code(402).send({
        code: 'PAYMENT_REQUIRED',
        message: 'This workshop is paid. Complete checkout to reserve your seat.',
      });
    }

    // Capacity: the workshop ROW is locked first, so two simultaneous
    // requests queue up and the second sees the first one's seat. (A plain
    // transaction under READ COMMITTED let both read "one left" and both book
    // it.) The paid path locks the same row at settlement (orders.ts).
    try {
      await prisma.$transaction(async (tx) => {
        await lockWorkshop(tx, id);
        const workshop = await tx.liveWorkshop.findUnique({
          where: { id },
          include: { _count: { select: { registrations: true } } },
        });
        if (!workshop) throw new Error('NOT_FOUND');
        if (workshop._count.registrations >= workshop.totalCapacity) throw new Error('FULL');
        await tx.workshopRegistration.create({ data: { userId: req.user.id, workshopId: id } });
      });
    } catch (cause) {
      const msg = String((cause as Error).message);
      if (msg.includes('NOT_FOUND')) {
        return reply.code(404).send({ code: 'NOT_FOUND', message: 'Workshop not found' });
      }
      if (msg.includes('FULL')) {
        return reply.code(409).send({ code: 'WORKSHOP_FULL', message: 'No spots remaining' });
      }
      throw cause;
    }
    return { registered: true };
  });
};

export default routes;
