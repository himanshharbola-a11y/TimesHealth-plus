import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import type { DietLeadResponse } from '@th/types';
import { prisma } from '../db.js';
import { normalizePhone } from '../identity/normalize.js';

/**
 * Diet — PRD §9. Lead capture only. Identical for every user, no entitlement
 * logic, no plans, no prices, no payment in V1.
 *
 * The lead is stored here and forwarded to the existing diet pipeline in the
 * background (services/dietLeads.ts) — the user's submission never waits on,
 * or fails because of, the downstream system.
 */

/** A dietitian calls each lead; more than a few a day from one account is abuse. */
const MAX_LEADS_PER_DAY = 3;

const leadSchema = z.object({
  name: z.string().trim().min(1).max(80),
  // Must be a number a dietitian can actually call (normalised below).
  phone: z.string().trim().min(8).max(20),
  condition: z.string().max(80).nullable().optional(),
  cuisinePreference: z.string().max(80).nullable().optional(),
  bestTimeToCall: z.string().max(40).nullable().optional(),
});

const routes: FastifyPluginAsync = async (app) => {
  app.post('/diet/leads', { preHandler: app.requireAuth }, async (req, reply): Promise<DietLeadResponse | undefined> => {
    const parsed = leadSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({
        code: 'INVALID_BODY',
        message: 'Invalid lead payload',
        fields: parsed.error.flatten().fieldErrors as Record<string, string[]>,
      });
    }
    const d = parsed.data;
    const phone = normalizePhone(d.phone);
    if (!phone) {
      return reply.code(400).send({ code: 'INVALID_PHONE', message: 'Enter a valid 10-digit mobile number' });
    }

    const recent = await prisma.dietLead.count({
      where: { userId: req.user.id, createdAt: { gt: new Date(Date.now() - 86_400_000) } },
    });
    if (recent >= MAX_LEADS_PER_DAY) {
      return reply.code(429).send({
        code: 'TOO_MANY_REQUESTS',
        message: 'We already have your request — a dietitian will call you within one working day.',
      });
    }

    const lead = await prisma.dietLead.create({
      data: {
        userId: req.user.id,
        name: d.name,
        phone,
        condition: d.condition ?? null,
        cuisinePreference: d.cuisinePreference ?? null,
        bestTimeToCall: d.bestTimeToCall ?? null,
      },
    });

    return {
      leadId: lead.id,
      message:
        'A TimesHealth+ certified dietitian will call your registered number within one working day to conduct your 15-minute consultation.',
    };
  });
};

export default routes;
