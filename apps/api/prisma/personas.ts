/**
 * Test personas — the server-side equivalent of the prototype's QA Lab.
 *
 * PRD §3 is a matrix, not a ladder, and every screen must resolve against it.
 * That makes the persona matrix the highest-value thing to test (docs/03 §5),
 * so these fixtures exist from day one rather than being hand-built each time.
 *
 * Run: npm run personas --workspace @th/api
 *
 * Each persona logs in with a dev bearer token of the form "uid|email|phone".
 */

import 'dotenv/config';
import { PrismaClient } from '@prisma/client';
import { randomUUID } from 'node:crypto';
import { istCalendarDate } from '../src/time.js';

const prisma = new PrismaClient();

const DAY = 86_400_000;

interface PersonaSpec {
  uid: string;
  name: string;
  email: string;
  phone: string;
  concern: string | null;
  yoga: 'ACTIVE' | 'EXPIRED' | null;
  marathon: { eventId: string; tier: 'CLASSIC' | 'PREMIUM'; category: string; bib: string | null; completed?: boolean } | null;
  attendanceDays?: number;
}

const PERSONAS: PersonaSpec[] = [
  {
    uid: 'qa_free',
    name: 'Freya Free',
    email: 'free@th.test',
    phone: '+919000000001',
    concern: null,
    yoga: null,
    marathon: null,
  },
  {
    uid: 'qa_yoga',
    name: 'Yash Yogi',
    email: 'yoga@th.test',
    phone: '+919000000002',
    concern: 'LOWER_BACK',
    yoga: 'ACTIVE',
    marathon: null,
    attendanceDays: 7,
  },
  {
    uid: 'qa_marathon',
    name: 'Meera Runner',
    email: 'marathon@th.test',
    phone: '+919000000003',
    concern: 'KNEES_JOINTS',
    yoga: null,
    marathon: { eventId: 'delhi_half', tier: 'CLASSIC', category: '21K', bib: 'DEL-8892A' },
  },
  {
    uid: 'qa_both',
    name: 'Bhavna Both',
    email: 'both@th.test',
    phone: '+919000000004',
    concern: 'SLEEP_ENERGY',
    yoga: 'ACTIVE',
    marathon: { eventId: 'delhi_half', tier: 'PREMIUM', category: '10K', bib: 'DEL-4410B' },
    attendanceDays: 23,
  },
  {
    uid: 'qa_expired',
    name: 'Evan Expired',
    email: 'expired@th.test',
    phone: '+919000000005',
    concern: 'NECK_SHOULDERS',
    yoga: 'EXPIRED',
    marathon: null,
    attendanceDays: 7,
  },
  {
    // Exercises §8.4: race run, result not yet published → pending state.
    uid: 'qa_finisher',
    name: 'Farah Finisher',
    email: 'finisher@th.test',
    phone: '+919000000006',
    concern: null,
    yoga: null,
    marathon: { eventId: 'pune_run', tier: 'CLASSIC', category: '10K', bib: 'PUN-7731C', completed: true },
  },
];

async function upsertPersona(spec: PersonaSpec) {
  const user = await prisma.user.upsert({
    where: { firebaseUid: spec.uid },
    create: {
      firebaseUid: spec.uid,
      name: spec.name,
      email: spec.email,
      phone: spec.phone,
      concern: spec.concern,
      healthGoal: 'CONSISTENCY',
      onboardingCompleted: true,
      profileCompletion: spec.concern ? 83 : 67,
    },
    update: { name: spec.name, concern: spec.concern },
  });

  // A re-run puts every persona back to its documented state: activity from
  // demos and manual testing (purchases, runs, seats, saves, sent pushes) is
  // cleared rather than left to skew what the persona shows.
  await prisma.$transaction([
    prisma.order.deleteMany({ where: { userId: user.id } }),
    prisma.workshopRegistration.deleteMany({ where: { userId: user.id } }),
    prisma.runRecord.deleteMany({ where: { userId: user.id } }),
    prisma.savedSession.deleteMany({ where: { userId: user.id } }),
    prisma.completedSession.deleteMany({ where: { userId: user.id } }),
    prisma.notificationLog.deleteMany({ where: { userId: user.id } }),
  ]);

  await prisma.yogaSubscription.deleteMany({ where: { userId: user.id } });
  if (spec.yoga) {
    const active = spec.yoga === 'ACTIVE';
    await prisma.yogaSubscription.create({
      data: {
        userId: user.id,
        planId: 'yoga_annual',
        planLabel: 'Annual Membership',
        status: active ? 'ACTIVE' : 'EXPIRED',
        startedAt: new Date(Date.now() - 200 * DAY),
        expiresAt: new Date(Date.now() + (active ? 165 * DAY : -62 * DAY)),
        // No recurring rail yet, so no persona claims one (see orders.ts settleOrder).
        autoRenews: false,
        reminderSlotId: 'b2',
      },
    });
  }

  await prisma.attendance.deleteMany({ where: { userId: user.id } });
  if (spec.attendanceDays) {
    // Consecutive days ending yesterday, so "current streak" is live but today
    // is still open — the most common real state. A lapsed member's streak
    // ends the day before the membership did (62 days ago): nobody attends
    // live classes after their access ends. Dates are IST days, as the API
    // writes them (time.ts istCalendarDate).
    const endsDaysAgo = spec.yoga === 'EXPIRED' ? 63 : 1;
    const rows = Array.from({ length: spec.attendanceDays }, (_, i) => ({
      userId: user.id,
      date: istCalendarDate(new Date(Date.now() - (endsDaysAgo + i) * DAY)),
      batchId: 'b2',
      source: i % 3 === 0 ? 'WHATSAPP' : 'APP',
    }));
    await prisma.attendance.createMany({ data: rows, skipDuplicates: true });
  }

  await prisma.marathonRegistration.deleteMany({ where: { userId: user.id } });
  if (spec.marathon) {
    const reg = await prisma.marathonRegistration.create({
      data: {
        userId: user.id,
        eventId: spec.marathon.eventId,
        registrationRef: `TH-${spec.uid.toUpperCase()}`,
        tier: spec.marathon.tier,
        category: spec.marathon.category,
        bibNumber: spec.marathon.bib,
        status: spec.marathon.completed ? 'COMPLETED' : 'UPCOMING',
        tshirtSize: 'M',
      },
    });
    await prisma.kitDelivery.create({
      data: {
        registrationId: reg.id,
        status: 'IN_TRANSIT',
        courierName: 'BlueDart',
        trackingRef: 'DEL-9921',
        expectedBy: new Date(Date.now() + 5 * DAY),
      },
    });
    if (spec.marathon.completed) {
      await prisma.raceResult.create({
        data: {
          registrationId: reg.id,
          // Deliberately unpublished so the pending state is reachable (§8.4).
          published: false,
        },
      });
    }
  }

  await prisma.referral.upsert({
    where: { userId: user.id },
    create: { userId: user.id, code: `TH${spec.uid.slice(-5).toUpperCase()}`, confirmedReferrals: 1, luckyDrawEntries: 1 },
    update: {},
  });

  if (spec.uid === 'qa_marathon') {
    await prisma.runRecord.createMany({
      data: [
        { id: randomUUID(), userId: user.id, startedAt: new Date(Date.now() - 1 * DAY), endedAt: new Date(Date.now() - 1 * DAY + 1900_000), distanceKm: 5.24, durationSeconds: 1900, avgPaceSecPerKm: 362, caloriesBurned: 318, routePolyline: null, hasAccuracyWarning: false },
        { id: randomUUID(), userId: user.id, startedAt: new Date(Date.now() - 3 * DAY), endedAt: new Date(Date.now() - 3 * DAY + 2902_000), distanceKm: 8.1, durationSeconds: 2902, avgPaceSecPerKm: 358, caloriesBurned: 492, routePolyline: null, hasAccuracyWarning: false },
        { id: randomUUID(), userId: user.id, startedAt: new Date(Date.now() - 5 * DAY), endedAt: new Date(Date.now() - 5 * DAY + 1144_000), distanceKm: 3.06, durationSeconds: 1144, avgPaceSecPerKm: 374, caloriesBurned: 186, routePolyline: null, hasAccuracyWarning: true },
      ],
      skipDuplicates: true,
    });
  }

  return user;
}

async function main() {
  console.log('Creating test personas...\n');
  for (const spec of PERSONAS) {
    await upsertPersona(spec);
    console.log(`  ${spec.name.padEnd(16)} token: ${spec.uid}|${spec.email}|${spec.phone}`);
  }
  console.log('\nUse as:  Authorization: Bearer <token>');
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(() => prisma.$disconnect());
