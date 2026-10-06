import type { FastifyBaseLogger } from 'fastify';
import { prisma } from '../db.js';
import { env } from '../env.js';

/**
 * Diet leads → the existing diet lead pipeline (PRD §9: "the same destination
 * as the web 'Free 15-min call' form").
 *
 * Forwarding never runs inside the user's request: a slow or down pipeline
 * must not hold the user's spinner, and a failed forward must not be lost.
 * Every lead is stored first; the scheduler tick forwards whatever has not
 * gone yet, with a timeout, until the pipeline accepts it.
 */

const FORWARD_TIMEOUT_MS = 5000;
const SWEEP_BATCH = 50;

let warnedUnset = false;

export async function forwardPendingDietLeads(log: FastifyBaseLogger): Promise<void> {
  if (!env.dietLeadWebhookUrl) {
    if (!warnedUnset) log.warn('DIET_LEAD_WEBHOOK_URL unset — diet leads are stored locally only');
    warnedUnset = true;
    return;
  }
  const pending = await prisma.dietLead.findMany({
    where: { forwardedAt: null },
    orderBy: { createdAt: 'asc' },
    take: SWEEP_BATCH,
  });
  for (const lead of pending) {
    try {
      const res = await fetch(env.dietLeadWebhookUrl, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          leadId: lead.id,
          source: 'APP',
          name: lead.name,
          phone: lead.phone,
          condition: lead.condition,
          cuisinePreference: lead.cuisinePreference,
          bestTimeToCall: lead.bestTimeToCall,
          createdAt: lead.createdAt.toISOString(),
        }),
        signal: AbortSignal.timeout(FORWARD_TIMEOUT_MS),
      });
      if (!res.ok) throw new Error(`pipeline answered ${res.status}`);
      await prisma.dietLead.update({ where: { id: lead.id }, data: { forwardedAt: new Date() } });
    } catch (err) {
      // Stays pending; the next tick tries again. Stop this sweep — if the
      // pipeline is down, hammering it with the rest of the batch won't help.
      log.error({ err: String(err), leadId: lead.id }, 'diet lead forward failed — will retry');
      return;
    }
  }
}
