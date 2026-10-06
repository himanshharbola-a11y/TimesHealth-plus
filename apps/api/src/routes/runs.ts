import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import type { RunHistoryResponse } from '@th/types';
import { prisma } from '../db.js';
import { istMonthStart } from '../time.js';

/**
 * Run tracker sync — PRD §8.6. Free for everyone, no entitlement check.
 *
 * Runs are recorded on-device first and uploaded afterwards. The client owns
 * the id so an upload is idempotent: a retry after a crash, a flaky network or
 * an app kill mid-run re-sends the same row rather than creating a duplicate.
 * That is what satisfies "app killed mid-run → run must be recoverable".
 */

const uploadSchema = z.object({
  id: z.string().uuid(),
  startedAt: z.string().datetime(),
  endedAt: z.string().datetime(),
  distanceKm: z.number().min(0).max(500),
  durationSeconds: z.number().int().min(0).max(24 * 3600),
  avgPaceSecPerKm: z.number().int().min(0).max(36000),
  caloriesBurned: z.number().int().min(0).max(20000),
  routePolyline: z.string().max(200_000).nullable(),
  hasAccuracyWarning: z.boolean(),
}).refine((r) => Date.parse(r.endedAt) >= Date.parse(r.startedAt), {
  message: 'endedAt must not be before startedAt',
});

const routes: FastifyPluginAsync = async (app) => {
  app.post('/runs', { preHandler: app.requireAuth }, async (req, reply) => {
    const parsed = uploadSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({
        code: 'INVALID_BODY',
        message: 'Invalid run payload',
        fields: parsed.error.flatten().fieldErrors as Record<string, string>,
      });
    }
    const d = parsed.data;

    const run = await prisma.runRecord.upsert({
      where: { id: d.id },
      // Re-uploading an existing run is a no-op rather than an error, and a run
      // belonging to someone else is never overwritten.
      update: {},
      create: {
        id: d.id,
        userId: req.user.id,
        startedAt: new Date(d.startedAt),
        endedAt: new Date(d.endedAt),
        distanceKm: d.distanceKm,
        durationSeconds: d.durationSeconds,
        avgPaceSecPerKm: d.avgPaceSecPerKm,
        caloriesBurned: d.caloriesBurned,
        routePolyline: d.routePolyline,
        hasAccuracyWarning: d.hasAccuracyWarning,
      },
    });

    if (run.userId !== req.user.id) {
      return reply.code(409).send({ code: 'ID_CONFLICT', message: 'Run id already in use' });
    }
    return { id: run.id, synced: true };
  });

  app.get('/runs', { preHandler: app.requireAuth }, async (req): Promise<RunHistoryResponse> => {
    const [rows, totals, thisMonth] = await Promise.all([
      prisma.runRecord.findMany({
        where: { userId: req.user.id },
        orderBy: { startedAt: 'desc' },
        take: 100,
        // The list never ships routes: each can be ~200 KB, and the history
        // rows don't draw them.
        omit: { routePolyline: true },
      }),
      // Lifetime totals over EVERY run, not just the page shown.
      prisma.runRecord.aggregate({
        where: { userId: req.user.id },
        _count: { _all: true },
        _sum: { distanceKm: true, durationSeconds: true },
        _max: { distanceKm: true },
      }),
      prisma.runRecord.aggregate({
        where: { userId: req.user.id, startedAt: { gte: istMonthStart() } },
        _sum: { distanceKm: true },
      }),
    ]);
    const km = (v: number | null | undefined) => Math.round((v ?? 0) * 100) / 100;
    return {
      runs: rows.map((r) => ({
        id: r.id,
        startedAt: r.startedAt.toISOString(),
        endedAt: r.endedAt.toISOString(),
        distanceKm: r.distanceKm,
        durationSeconds: r.durationSeconds,
        avgPaceSecPerKm: r.avgPaceSecPerKm,
        caloriesBurned: r.caloriesBurned,
        routePolyline: null,
        hasAccuracyWarning: r.hasAccuracyWarning,
        synced: true,
      })),
      totals: {
        runs: totals._count._all,
        distanceKm: km(totals._sum.distanceKm),
        durationSeconds: totals._sum.durationSeconds ?? 0,
        longestKm: km(totals._max.distanceKm),
        monthDistanceKm: km(thisMonth._sum.distanceKm),
      },
    };
  });

  /** Route deletion — DPDP right to erasure, applied to the most sensitive data. */
  app.delete('/runs/:id', { preHandler: app.requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const run = await prisma.runRecord.findUnique({ where: { id } });
    if (!run || run.userId !== req.user.id) {
      return reply.code(404).send({ code: 'NOT_FOUND', message: 'Run not found' });
    }
    await prisma.runRecord.delete({ where: { id } });
    return { deleted: true };
  });
};

export default routes;
