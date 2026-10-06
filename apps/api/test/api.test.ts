/**
 * API rule tests — the behaviours the PRD and the money/security reviews
 * depend on, run against the real routes and the real database.
 *
 *   npm test            (from the repo root, with Postgres up: npm run db:up)
 *
 * Read-only checks use the six QA personas. Anything that changes data uses
 * its own throwaway user (qa_test_*) and is cleaned up afterwards, so running
 * the suite never disturbs the personas used for demos.
 *
 * If date-based checks fail after a few weeks, refresh the fixtures:
 *   npm run seed && npm run personas
 */

import { after, before, describe, test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { getTodaySchedule } from '../src/services/feed.js';
import { buildWhatsAppJoinUrl } from '../src/services/media.js';
import { pinClockForTests } from '../src/clock.js';
import { batchInstant, istDateString } from '../src/time.js';

/** Today's IST date at the given "HH:mm" wall-clock time. */
const istToday = (hhmm: string) => batchInstant(hhmm, 0, new Date());
import { prisma } from '../src/db.js';

const P = {
  free: 'qa_free|free@th.test|+919000000001',
  yoga: 'qa_yoga|yoga@th.test|+919000000002',
  marathon: 'qa_marathon|marathon@th.test|+919000000003',
  both: 'qa_both|both@th.test|+919000000004',
  expired: 'qa_expired|expired@th.test|+919000000005',
  finisher: 'qa_finisher|finisher@th.test|+919000000006',
} as const;

let app: FastifyInstance;
const RUN = Date.now();
let counter = 0;
/** A brand-new free user, unique to this run. */
const freshUser = () => `qa_test_${RUN}_${(counter += 1)}|t${counter}@th.test|`;

before(async () => {
  app = await buildApp({ logger: false });
  await app.ready();
});

after(async () => {
  // Throwaway users cascade to their runs, attendance and devices. Orders
  // outlive an account (kept for refunds/audit), so test orders go first.
  await prisma.order.deleteMany({ where: { user: { firebaseUid: { startsWith: 'qa_test_' } } } });
  await prisma.user.deleteMany({ where: { firebaseUid: { startsWith: 'qa_test_' } } });
  await prisma.dietLead.deleteMany({ where: { name: 'Test Runner' } });
  await app.close();
  await prisma.$disconnect();
});

type Res = { status: number; body: any };

async function call(method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE', url: string, token?: string, payload?: unknown): Promise<Res> {
  const r = await app.inject({
    method,
    url,
    headers: token ? { authorization: `Bearer ${token}` } : {},
    ...(payload !== undefined ? { payload: payload as object } : {}),
  });
  return { status: r.statusCode, body: r.body ? r.json() : null };
}
const get = (url: string, token?: string) => call('GET', url, token);
const post = (url: string, token?: string, body?: unknown) => call('POST', url, token, body ?? {});

async function buyYoga(token: string): Promise<void> {
  const order = await post('/v1/orders', token, { productType: 'YOGA_SUBSCRIPTION', productId: 'yoga_annual' });
  assert.equal(order.status, 200);
  const paid = await post(`/v1/orders/${order.body.orderId}/simulate-payment`, token);
  assert.equal(paid.body.result, 'GRANTED');
}

// ─────────────────────────────────────────────────────────────────────────────

describe('health & auth', () => {
  test('health reports the database', async () => {
    const r = await get('/health');
    assert.equal(r.status, 200);
    assert.equal(r.body.ok, true);
  });

  test('no token → 401 in the app error shape', async () => {
    const r = await get('/v1/session');
    assert.equal(r.status, 401);
    assert.equal(r.body.code, 'UNAUTHORIZED');
  });

  test('a forged JWT is rejected', async () => {
    const r = await get('/v1/session', 'eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.ZmFrZQ');
    assert.equal(r.status, 401);
  });

  test('launch config is public — the update gate must work before sign-in', async () => {
    const r = await get('/v1/config');
    assert.equal(r.status, 200);
    assert.match(r.body.minSupportedAppVersion, /^\d+\.\d+\.\d+$/);
    assert.equal(typeof r.body.maintenance.active, 'boolean');
  });

  test('persona tokens work on a test server', async () => {
    const r = await get('/v1/session', P.free);
    assert.equal(r.status, 200);
    assert.equal(r.body.persona.persona, 'FREE');
  });

  test('a persona token can never claim a real person’s email or number', async () => {
    // resolveUser adopts accounts by verified email/phone, so a mintable token
    // carrying a real identifier would be an account takeover.
    assert.equal((await get('/v1/session', `qa_test_${RUN}_x|someone@gmail.com|`)).status, 401);
    assert.equal((await get('/v1/session', `qa_test_${RUN}_y||+919876543210`)).status, 401);
    assert.equal((await get('/v1/session', 'admin|a@th.test|')).status, 401);
  });
});

describe('Home — PRD §6', () => {
  const heroOf = async (token: string) =>
    (await get('/v1/home', token)).body.components.find((c: any) => c.type === 'HERO_STACK').slots.map((s: any) => s.kind);

  // §6.1 matrix: a yoga subscriber's session ALWAYS wins slot 1; the loser drops to slot 2.
  const MATRIX: [keyof typeof P, string[]][] = [
    ['free', ['SELL_YOGA', 'SELL_MARATHON']],
    ['yoga', ['YOGA_SESSION', 'SELL_MARATHON']],
    ['marathon', ['MY_RACE', 'SELL_YOGA']],
    ['both', ['YOGA_SESSION', 'MY_RACE']],
    ['expired', ['YOGA_RENEW', 'SELL_MARATHON']], // §5: warm renew, not a cold sell
    ['finisher', ['RACE_RESULT', 'SELL_YOGA']], // §8.4: race run → result state
  ];
  for (const [persona, expected] of MATRIX) {
    test(`hero priority: ${persona} → ${expected.join(' | ')}`, async () => {
      assert.deepEqual(await heroOf(P[persona]), expected);
    });
  }

  // Design copy (HomeScreen.kt). The prototype only matched 2 of 6 concerns.
  const HEADINGS: [keyof typeof P, string][] = [
    ['free', 'Sessions for weight loss & agility'],
    ['yoga', 'Sessions for lower back relief'],
    ['marathon', 'Sessions for knee & joint stability'],
    ['both', 'Sessions for deep evening sleep'],
    ['expired', 'Sessions for neck & shoulder release'],
  ];
  for (const [persona, heading] of HEADINGS) {
    test(`concern rail: ${persona} → "${heading}"`, async () => {
      const r = await get('/v1/home', P[persona]);
      const first = r.body.components.find((c: any) => c.type === 'VIDEO_RAIL');
      assert.equal(first.title, heading);
    });
  }

  test('layout order matches the design, promo strip after the first rail (§6.2)', async () => {
    const r = await get('/v1/home', P.free);
    assert.deepEqual(
      r.body.components.map((c: any) => c.type),
      ['HERO_STACK', 'VIDEO_RAIL', 'PROMO_STRIP', 'VIDEO_RAIL', 'ENTRY_TILE', 'WORKSHOP_RAIL', 'REEL_RAIL', 'ARTICLE_RAIL', 'QUOTE_RAIL'],
    );
  });

  test('promo strip never sells a product the user already holds', async () => {
    const promo = async (t: string) => (await get('/v1/home', t)).body.components.find((c: any) => c.type === 'PROMO_STRIP')?.campaignId;
    assert.notEqual(await promo(P.marathon), 'marathon-delhi');
    assert.notEqual(await promo(P.yoga), 'yoga-annual');
  });
});

describe('Yoga — PRD §7', () => {
  test('tracker maths: streak, best streak, classes', async () => {
    const r = await get('/v1/yoga/attendance', P.both);
    assert.equal(r.status, 200);
    assert.equal(r.body.currentStreak, 23);
    assert.equal(r.body.bestStreak, 23);
    assert.equal(r.body.classesAttended, 23);
  });

  test('the 8 daily batches come in clock order, morning first (§7.1)', async () => {
    const times: string[] = (await get('/v1/yoga/today', P.yoga)).body.batches.map((b: any) => b.time);
    assert.equal(times.length, 8);
    assert.equal(times[0], '05:15');
    assert.deepEqual(times, [...times].sort());
  });

  test('after the last class, "next" is tomorrow’s first batch — 05:15, not an evening one (§6.1)', async () => {
    const tenPmIst = new Date('2026-10-05T16:30:00Z');
    const { nextBatch } = await getTodaySchedule(tenPmIst);
    assert.equal(nextBatch?.isToday, false);
    assert.equal(nextBatch?.startsAt.toISOString(), '2026-10-05T23:45:00.000Z'); // 05:15 IST, 6 Oct
  });

  test('the tracker does not exist for a free user (§7.2)', async () => {
    assert.equal((await get('/v1/yoga/attendance', P.free)).status, 403);
  });

  test('a free user cannot join a live class', async () => {
    assert.equal((await post('/v1/yoga/join', P.free, { batchId: 'b1' })).status, 403);
  });

  test('single-source attendance: two joins in one day = one mark (§7.1)', async () => {
    const u = freshUser();
    await buyYoga(u);
    try {
      pinClockForTests(istToday('05:30')); // b1 (05:15) is live
      assert.equal((await post('/v1/yoga/join', u, { batchId: 'b1' })).status, 200);
      pinClockForTests(istToday('18:10')); // b6 (18:00) is live, same IST day
      assert.equal((await post('/v1/yoga/join', u, { batchId: 'b6' })).status, 200);
    } finally {
      pinClockForTests(null);
    }
    const att = await get('/v1/yoga/attendance', u);
    assert.equal(att.body.classesAttended, 1);
    // …and it is TODAY's IST date, even for a 05:30 IST class (00:00 UTC):
    // a DATE column once stored these a day early.
    assert.deepEqual(att.body.attendedDates, [istDateString(istToday('05:30'))]);
  });

  test('a join with no class open is refused and writes no attendance — no 10 PM streak farming', async () => {
    const u = freshUser();
    await buyYoga(u);
    try {
      pinClockForTests(istToday('22:00')); // last class (20:30) ended at 21:30
      const r = await post('/v1/yoga/join', u, { batchId: 'b1' });
      assert.equal(r.status, 409);
      assert.equal(r.body.code, 'CLASS_NOT_OPEN');
      pinClockForTests(istToday('17:50')); // b6 wait room is open, class starts 18:00
      assert.equal((await post('/v1/yoga/join', u, { batchId: 'b6' })).status, 200);
    } finally {
      pinClockForTests(null);
    }
    assert.equal((await get('/v1/yoga/attendance', u)).body.classesAttended, 1);
  });

  test('WhatsApp link: a forged or raw user id never reaches the class or the ledger', async () => {
    const u = freshUser();
    await buyYoga(u);
    const user = await prisma.user.findUniqueOrThrow({ where: { firebaseUid: u.split('|')[0] } });
    try {
      pinClockForTests(istToday('18:10'));
      const raw = await app.inject({ method: 'GET', url: `/v1/yoga/wa-join?u=${user.id}&b=b6` });
      assert.equal(raw.statusCode, 302);
      assert.ok(!String(raw.headers.location).includes('/live'), 'unsigned link must not open the class');
      const forged = await app.inject({ method: 'GET', url: `/v1/yoga/wa-join?t=${user.id}.9999999999.deadbeef` });
      assert.ok(!String(forged.headers.location).includes('/live'));
      assert.equal((await get('/v1/yoga/attendance', u)).body.classesAttended, 0);

      // A properly signed link from a member: into the class, and counted once.
      const url = new URL(buildWhatsAppJoinUrl('http://x', user.id, 'b6'));
      const ok = await app.inject({ method: 'GET', url: url.pathname + url.search });
      assert.equal(ok.statusCode, 302);
      assert.ok(String(ok.headers.location).includes('/live'));
    } finally {
      pinClockForTests(null);
    }
    assert.equal((await get('/v1/yoga/attendance', u)).body.classesAttended, 1);
  });

  test('WhatsApp link: a non-member is sent to the landing page, not the class', async () => {
    const u = freshUser();
    await get('/v1/session', u);
    const user = await prisma.user.findUniqueOrThrow({ where: { firebaseUid: u.split('|')[0] } });
    const url = new URL(buildWhatsAppJoinUrl('http://x', user.id));
    const r = await app.inject({ method: 'GET', url: url.pathname + url.search });
    assert.equal(r.statusCode, 302);
    assert.ok(!String(r.headers.location).includes('/live'));
  });
});

describe('Marathon — PRD §8', () => {
  test('past editions are hidden from people who did not run them', async () => {
    const ids = (await get('/v1/marathon/events', P.free)).body.events.map((e: any) => e.id);
    assert.ok(!ids.includes('pune_run'));
  });

  test('a finisher still sees the race they ran, first', async () => {
    const r = await get('/v1/marathon/events', P.finisher);
    assert.equal(r.body.events[0].id, 'pune_run');
    assert.equal(r.body.events[0].registrationOpen, false);
  });

  test('a finisher who signs up again sees the race ahead first, last year after', async () => {
    const u = freshUser();
    await get('/v1/session', u); // creates the user
    const user = await prisma.user.findUniqueOrThrow({ where: { firebaseUid: u.split('|')[0] } });
    await prisma.marathonRegistration.create({
      data: {
        userId: user.id, eventId: 'pune_run', registrationRef: `QA-${RUN}-${counter}`,
        tier: 'CLASSIC', category: '10K', status: 'COMPLETED',
      },
    });
    const order = await post('/v1/orders', u, {
      productType: 'MARATHON_REGISTRATION', productId: 'x', eventId: 'delhi_half', category: '10K', tier: 'CLASSIC',
    });
    assert.equal((await post(`/v1/orders/${order.body.orderId}/simulate-payment`, u)).body.result, 'GRANTED');

    const ids = (await get('/v1/marathon/events', u)).body.events.map((e: any) => e.id);
    assert.deepEqual(ids.slice(0, 2), ['delhi_half', 'pune_run']);
  });

  test('after the race: no upgrade offer and no Refer & Win on its detail', async () => {
    const r = await get('/v1/marathon/events/pune_run', P.finisher);
    assert.equal(r.status, 200);
    assert.equal(r.body.upgradeOffer, null);
    assert.equal(r.body.referral, null);
  });

  test('referral codes are random, unambiguous, and stable per user', async () => {
    const u = freshUser();
    const first = await get('/v1/marathon/referral', u);
    assert.match(first.body.code, /^TH[A-HJ-NP-Z2-9]{6}$/);
    assert.equal((await get('/v1/marathon/referral', u)).body.code, first.body.code);
  });

  test('digital bib QR verifies; a tampered token does not (docs/04 T1)', async () => {
    const detail = await get('/v1/marathon/events/delhi_half', P.marathon);
    const token: string = detail.body.bib.qrToken;
    const ok = await post('/v1/marathon/verify-bib', undefined, { token });
    assert.equal(ok.status, 200);
    assert.equal(ok.body.bibNumber, 'DEL-8892A');
    assert.equal(ok.body.tokenKind, 'LIVE');
    // Every scan is logged, so a gate can see a pass that was already used.
    const again = await post('/v1/marathon/verify-bib', undefined, { token });
    assert.ok(again.body.previousScans.length >= 1);
    const tampered = token.slice(0, -1) + (token.endsWith('0') ? '1' : '0');
    assert.equal((await post('/v1/marathon/verify-bib', undefined, { token: tampered })).status, 401);
  });
});

describe('Money — docs/04 T8', () => {
  test('the server prices orders; a client-sent price is ignored', async () => {
    const r = await post('/v1/orders', freshUser(), { productType: 'YOGA_SUBSCRIPTION', productId: 'yoga_annual', amountPaise: 1 });
    assert.equal(r.status, 200);
    assert.equal(r.body.amountPaise, 499900);
  });

  test('purchase grants entitlement once; a webhook retry is a no-op', async () => {
    const u = freshUser();
    const order = await post('/v1/orders', u, { productType: 'YOGA_SUBSCRIPTION', productId: 'yoga_annual' });
    assert.equal((await post(`/v1/orders/${order.body.orderId}/simulate-payment`, u)).body.result, 'GRANTED');
    assert.equal((await get('/v1/session', u)).body.persona.persona, 'YOGA_SUBSCRIBER');
    assert.equal((await post(`/v1/orders/${order.body.orderId}/simulate-payment`, u)).body.result, 'ALREADY_GRANTED');
  });

  test('renewing before expiry extends the term — a mid-term payment never eats paid time', async () => {
    const u = freshUser();
    await buyYoga(u); // annual
    const before = (await get('/v1/session', u)).body.entitlements.yoga.expiresAt;
    const order = await post('/v1/orders', u, { productType: 'YOGA_SUBSCRIPTION', productId: 'yoga_monthly' });
    assert.equal((await post(`/v1/orders/${order.body.orderId}/simulate-payment`, u)).body.result, 'GRANTED');
    const after = (await get('/v1/session', u)).body.entitlements.yoga.expiresAt;
    const gainedDays = (Date.parse(after) - Date.parse(before)) / 86_400_000;
    assert.ok(gainedDays >= 27 && gainedDays <= 32, `expected ~1 month extension, got ${gainedDays} days`);
  });

  test('cannot buy an edition you are already registered for — refused before payment', async () => {
    const r = await post('/v1/orders', P.marathon, {
      productType: 'MARATHON_REGISTRATION', productId: 'x', eventId: 'delhi_half', category: '10K', tier: 'CLASSIC',
    });
    assert.equal(r.status, 409);
    assert.equal(r.body.code, 'ALREADY_REGISTERED');
  });

  test('cannot pay for a race that has already happened', async () => {
    const r = await post('/v1/orders', freshUser(), {
      productType: 'MARATHON_REGISTRATION', productId: 'x', eventId: 'pune_run', category: '10K', tier: 'CLASSIC',
    });
    assert.equal(r.status, 409);
    assert.equal(r.body.code, 'REGISTRATION_CLOSED');
  });

  test('cannot pay to upgrade a race that has already been run', async () => {
    const r = await post('/v1/orders', P.finisher, {
      productType: 'PREMIUM_UPGRADE', productId: 'pune_run', eventId: 'pune_run',
    });
    assert.equal(r.status, 409);
    assert.equal(r.body.code, 'REGISTRATION_CLOSED');
  });

  test('a paid workshop seat cannot be claimed without paying', async () => {
    // A fresh user, so seats taken while demoing the personas can't skew it.
    const u = freshUser();
    const ws = (await get('/v1/workshops', u)).body.workshops.find((w: any) => w.pricePaise > 0);
    assert.equal((await post(`/v1/workshops/${ws.id}/register`, u)).status, 402);
  });

  test('a paid workshop seat is never cancelled in-app — that would forfeit the money', async () => {
    const u = freshUser();
    const ws = (await get('/v1/workshops', u)).body.workshops.find((w: any) => w.pricePaise > 0);
    const order = await post('/v1/orders', u, { productType: 'WORKSHOP', productId: ws.id });
    assert.equal((await post(`/v1/orders/${order.body.orderId}/simulate-payment`, u)).body.result, 'GRANTED');

    const mine = (await get('/v1/workshops', u)).body.workshops.find((w: any) => w.id === ws.id);
    assert.equal(mine.isRegistered, true);
    assert.equal(mine.paidSeat, true);

    const cancel = await post(`/v1/workshops/${ws.id}/register`, u);
    assert.equal(cancel.status, 409);
    assert.equal(cancel.body.code, 'PAID_SEAT');
  });
});

describe('Runs & leads', () => {
  test('run upload is idempotent — a retry after a crash does not duplicate', async () => {
    const u = freshUser();
    const run = {
      id: randomUUID(),
      startedAt: new Date(Date.now() - 1_800_000).toISOString(),
      endedAt: new Date().toISOString(),
      distanceKm: 5.2, durationSeconds: 1800, avgPaceSecPerKm: 346, caloriesBurned: 322,
      routePolyline: null, hasAccuracyWarning: false,
    };
    assert.equal((await post('/v1/runs', u, run)).status, 200);
    assert.equal((await post('/v1/runs', u, run)).status, 200);
    assert.equal((await get('/v1/runs', u)).body.runs.length, 1);
  });

  test('diet lead: validated, then stored', async () => {
    const u = freshUser();
    assert.equal((await post('/v1/diet/leads', u, { name: 'Test Runner' })).status, 400);
    const ok = await post('/v1/diet/leads', u, { name: 'Test Runner', phone: '9876543210' });
    assert.equal(ok.status, 200);
    assert.ok(ok.body.leadId);
  });
});

describe('Refer & Win — PRD §8.3', () => {
  const RACE = (eventId: string, extra: Record<string, unknown> = {}) => ({
    productType: 'MARATHON_REGISTRATION', productId: eventId, eventId, category: '10K', tier: 'CLASSIC', ...extra,
  });
  const pay = async (token: string, orderId: string) =>
    (await post(`/v1/orders/${orderId}/simulate-payment`, token)).body.result;
  const registerAndPay = async (token: string, eventId: string, extra?: Record<string, unknown>) => {
    const order = await post('/v1/orders', token, RACE(eventId, extra));
    assert.equal(order.status, 200);
    assert.equal(await pay(token, order.body.orderId), 'GRANTED');
  };
  const referralOf = async (token: string) => (await get('/v1/marathon/referral', token)).body;

  test('your own code and unknown codes are refused — before any payment', async () => {
    const u = freshUser();
    const { code } = await referralOf(u);
    const own = await post('/v1/marathon/referral/apply', u, { code });
    assert.equal(own.status, 409);
    assert.equal(own.body.code, 'OWN_REFERRAL_CODE');
    // "0" is not in the code alphabet, so this can never be a real code.
    assert.equal((await post('/v1/marathon/referral/apply', u, { code: 'TH000000' })).status, 404);
    const atCheckout = await post('/v1/orders', u, RACE('hyd_half', { referralCode: 'TH000000' }));
    assert.equal(atCheckout.status, 409);
    assert.equal(atCheckout.body.code, 'INVALID_REFERRAL_CODE');
  });

  test('a friend counts once, only when paid; five unlock a free upgrade that can be claimed once', async () => {
    const referrer = freshUser();
    const { code } = await referralOf(referrer);
    await registerAndPay(referrer, 'hyd_half');
    await registerAndPay(referrer, 'blr_run');

    // An unpaid order with the code earns nothing.
    const friend = freshUser();
    const pending = await post('/v1/orders', friend, RACE('hyd_half', { referralCode: code }));
    assert.equal(pending.status, 200);
    assert.equal((await referralOf(referrer)).confirmedReferrals, 0);
    assert.equal(await pay(friend, pending.body.orderId), 'GRANTED');
    assert.equal((await referralOf(referrer)).confirmedReferrals, 1);

    // The same friend's next race is not a second referral.
    await registerAndPay(friend, 'blr_run');
    assert.equal((await referralOf(referrer)).confirmedReferrals, 1);

    for (let i = 0; i < 4; i += 1) await registerAndPay(freshUser(), 'hyd_half', { referralCode: code });
    const unlocked = await referralOf(referrer);
    assert.equal(unlocked.confirmedReferrals, 5);
    assert.equal(unlocked.guaranteedUpgradeUnlocked, true);
    assert.equal(unlocked.upgradeClaimed, false);

    const detail = await get('/v1/marathon/events/hyd_half', referrer);
    assert.equal(detail.body.upgradeOffer.freeClaim, true);

    const claim = await post('/v1/marathon/events/hyd_half/claim-upgrade', referrer);
    assert.equal(claim.status, 200);
    assert.equal(claim.body.tier, 'PREMIUM');
    // Now Premium: no offer at all on that race…
    assert.equal((await get('/v1/marathon/events/hyd_half', referrer)).body.upgradeOffer, null);
    // …and the earned upgrade is spent, so the second race can't take it too.
    const second = await post('/v1/marathon/events/blr_run/claim-upgrade', referrer);
    assert.equal(second.status, 409);
    assert.equal(second.body.code, 'NO_FREE_UPGRADE');
    assert.equal((await referralOf(referrer)).upgradeClaimed, true);
  });
});

describe('Money — settlement edge cases', () => {
  test('opening checkout twice for the same thing returns the same unpaid order — one purchase, one charge', async () => {
    const u = freshUser();
    const body = { productType: 'YOGA_SUBSCRIPTION', productId: 'yoga_monthly' };
    const a = await post('/v1/orders', u, body);
    const b = await post('/v1/orders', u, body);
    assert.equal(b.body.orderId, a.body.orderId);
    assert.equal((await post(`/v1/orders/${a.body.orderId}/simulate-payment`, u)).body.result, 'GRANTED');
    // Once paid it is never handed out again: a deliberate second purchase is a new order.
    assert.notEqual((await post('/v1/orders', u, body)).body.orderId, a.body.orderId);
  });

  test('paying for a race twice in different categories: the second payment is flagged for refund, never double-granted', async () => {
    const u = freshUser();
    const race = { productType: 'MARATHON_REGISTRATION', productId: 'hyd_half', eventId: 'hyd_half', tier: 'CLASSIC' };
    // Two checkouts opened before either is paid (two devices).
    const a = await post('/v1/orders', u, { ...race, category: '5K' });
    const b = await post('/v1/orders', u, { ...race, category: '10K' });
    assert.equal(b.status, 200);
    assert.notEqual(b.body.orderId, a.body.orderId);
    assert.equal((await post(`/v1/orders/${a.body.orderId}/simulate-payment`, u)).body.result, 'GRANTED');
    assert.equal((await post(`/v1/orders/${b.body.orderId}/simulate-payment`, u)).body.result, 'NEEDS_REFUND');

    const status = await get(`/v1/orders/${b.body.orderId}`, u);
    assert.equal(status.body.status, 'PAID_NOT_GRANTED');
    assert.equal(status.body.entitlementGranted, false);
    // A gateway retry of that payment stays a refund, not a grant.
    assert.equal((await post(`/v1/orders/${b.body.orderId}/simulate-payment`, u)).body.result, 'NEEDS_REFUND');
  });

  test('the payment webhook fails closed until its signature is verified', async () => {
    const r = await post('/v1/webhooks/payment', undefined, { orderId: 'anything', status: 'PAID' });
    assert.equal(r.status, 503);
  });
});

describe('Profile & identity — PRD §5, §10', () => {
  const patch = (token: string, body: unknown) => call('PATCH', '/v1/profile', token, body);

  test('a typed mobile number is stored in one format; an uncallable one is refused', async () => {
    const u = freshUser(); // signs in by email, so the mobile is an editable contact
    const ok = await patch(u, { phone: '098765 43210' });
    assert.equal(ok.status, 200);
    assert.equal(ok.body.profile.phone, '+919876543210');
    assert.equal(ok.body.profile.phoneIsLogin, false);
    const bad = await patch(u, { phone: '1234567890' });
    assert.equal(bad.status, 400);
    assert.equal(bad.body.code, 'INVALID_PHONE');
  });

  test('the sign-in email is not editable from the profile', async () => {
    const u = freshUser();
    const me = (await get('/v1/session', u)).body.profile;
    assert.equal(me.emailIsLogin, true);
    const changed = await patch(u, { email: 'someone.else@example.com' });
    assert.equal(changed.status, 409);
    assert.equal(changed.body.code, 'LOGIN_IDENTIFIER');
    assert.equal((await patch(u, { email: me.email })).status, 200);
  });

  test('an impossible date of birth is refused', async () => {
    const u = freshUser();
    const future = new Date(Date.now() + 86_400_000).toISOString();
    assert.equal((await patch(u, { dob: future })).status, 400);
    assert.equal((await patch(u, { dob: '1990-06-15T00:00:00.000Z' })).status, 200);
  });

  test('a lapsed member is not sent through onboarding again; a new user is (§5)', async () => {
    assert.equal((await get('/v1/session', P.expired)).body.needsOnboarding, false);
    assert.equal((await get('/v1/session', freshUser())).body.needsOnboarding, true);
  });
});

describe('Inbox, recordings, diet limits', () => {
  test('the bell inbox lists delivered alerts newest first — never test pushes or undelivered ones', async () => {
    const u = freshUser();
    await get('/v1/session', u); // creates the account
    const user = await prisma.user.findUniqueOrThrow({ where: { firebaseUid: u.split('|')[0] } });
    await prisma.notificationLog.createMany({
      data: [
        { userId: user.id, kind: 'SESSION_REMINDER', dedupeKey: 't1', title: 'Older', body: 'b', route: '/yoga', delivered: 1, sentAt: new Date(Date.now() - 3_600_000) },
        { userId: user.id, kind: 'RESULT_PUBLISHED', dedupeKey: 't2', title: 'Newer', body: 'b', route: '/race/pune_run/results', delivered: 1 },
        { userId: user.id, kind: 'TEST', dedupeKey: 't3', title: 'Test push', delivered: 1 },
        { userId: user.id, kind: 'SESSION_LIVE', dedupeKey: 't4', title: 'Never delivered', delivered: 0 },
      ],
    });
    const r = await get('/v1/notifications', u);
    assert.equal(r.status, 200);
    assert.deepEqual(r.body.items.map((i: any) => i.title), ['Newer', 'Older']);
    assert.equal(r.body.items[0].route, '/race/pune_run/results');
  });

  test('recordings: free ones play for everyone, paid ones only for members, via a fresh signed link', async () => {
    const free = freshUser();
    assert.equal((await get('/v1/yoga/sessions/ys_spine_1/playback', free)).status, 200);
    const locked = await get('/v1/yoga/sessions/ys_spine_2/playback', free);
    assert.equal(locked.status, 403);
    assert.equal(locked.body.code, 'NOT_ENTITLED');
    const member = await get('/v1/yoga/sessions/ys_spine_2/playback', P.yoga);
    assert.equal(member.status, 200);
    assert.match(member.body.playbackUrl, /[?&]s=[0-9a-f]{64}/);
    assert.equal((await get('/v1/yoga/sessions/nope/playback', P.yoga)).status, 404);
  });

  test('diet lead: an uncallable number is refused, and a fourth request in a day is throttled', async () => {
    const u = freshUser();
    const bad = await post('/v1/diet/leads', u, { name: 'Test Runner', phone: '1234567890' });
    assert.equal(bad.status, 400);
    assert.equal(bad.body.code, 'INVALID_PHONE');
    for (let i = 0; i < 3; i += 1) {
      assert.equal((await post('/v1/diet/leads', u, { name: 'Test Runner', phone: '9876543210' })).status, 200);
    }
    const fourth = await post('/v1/diet/leads', u, { name: 'Test Runner', phone: '9876543210' });
    assert.equal(fourth.status, 429);
  });
});

describe('Race participant details — §10', () => {
  test('emergency contact is saved callable and shown back; nonsense numbers are refused', async () => {
    const u = freshUser();
    const order = await post('/v1/orders', u, {
      productType: 'MARATHON_REGISTRATION', productId: 'hyd_half', eventId: 'hyd_half', category: '10K', tier: 'CLASSIC',
    });
    assert.equal((await post(`/v1/orders/${order.body.orderId}/simulate-payment`, u)).body.result, 'GRANTED');

    const edit = (body: unknown) => call('PATCH', '/v1/marathon/events/hyd_half/participant', u, body);
    const bad = await edit({ emergencyContactPhone: '12345' });
    assert.equal(bad.status, 400);
    assert.equal(bad.body.code, 'INVALID_PHONE');
    assert.equal((await edit({ tshirtSize: 'L', emergencyContactName: 'Asha', emergencyContactPhone: '98765 43210' })).status, 200);

    const detail = await get('/v1/marathon/events/hyd_half', u);
    assert.deepEqual(detail.body.participant, {
      tshirtSize: 'L', emergencyContactName: 'Asha', emergencyContactPhone: '+919876543210',
    });
  });

  test('details are frozen once the race has started', async () => {
    const r = await call('PATCH', '/v1/marathon/events/pune_run/participant', P.finisher, { tshirtSize: 'M' });
    assert.equal(r.status, 409);
    assert.equal(r.body.code, 'RACE_STARTED');
  });
});

describe('Run totals — §8.6', () => {
  test('longest run and this month’s distance cover every run, not just the listed page', async () => {
    const u = freshUser();
    const run = (km: number, startedAt: Date) => ({
      id: randomUUID(),
      startedAt: startedAt.toISOString(),
      endedAt: new Date(startedAt.getTime() + 1_800_000).toISOString(),
      distanceKm: km, durationSeconds: 1800, avgPaceSecPerKm: Math.round(1800 / km), caloriesBurned: 100,
      routePolyline: null, hasAccuracyWarning: false,
    });
    const now = new Date();
    assert.equal((await post('/v1/runs', u, run(4, new Date(now.getTime() - 3_600_000)))).status, 200);
    // Last year: counts for "longest", never for "this month".
    assert.equal((await post('/v1/runs', u, run(12, new Date(now.getTime() - 400 * 86_400_000)))).status, 200);

    const totals = (await get('/v1/runs', u)).body.totals;
    assert.equal(totals.runs, 2);
    assert.equal(totals.longestKm, 12);
    assert.equal(totals.monthDistanceKm, 4);
  });
});

describe('Recordings & accounts', () => {
  test('save / complete set the state asked for — a double tap never flips it back', async () => {
    const u = freshUser();
    const mine = async () => (await get('/v1/yoga/me/sessions', u)).body;
    for (let i = 0; i < 2; i += 1) {
      assert.equal((await post('/v1/yoga/sessions/ys_spine_1/save', u, { saved: true })).body.saved, true);
    }
    assert.deepEqual((await mine()).savedSessionIds, ['ys_spine_1']);
    assert.equal((await post('/v1/yoga/sessions/ys_spine_1/save', u, { saved: false })).body.saved, false);
    assert.deepEqual((await mine()).savedSessionIds, []);
    assert.equal((await post('/v1/yoga/sessions/ys_spine_1/complete', u, { completed: true })).body.completed, true);
    assert.equal((await post('/v1/yoga/sessions/ys_spine_1/complete', u, { completed: true })).body.completed, true);
    assert.deepEqual((await mine()).completedSessionIds, ['ys_spine_1']);
    assert.equal((await post('/v1/yoga/sessions/nope/complete', u, { completed: true })).status, 404);
  });

  test('deleting an account keeps its payment records, detached from the person', async () => {
    const u = freshUser();
    await buyYoga(u);
    const user = await prisma.user.findUniqueOrThrow({ where: { firebaseUid: u.split('|')[0] } });
    const ids = (await prisma.order.findMany({ where: { userId: user.id }, select: { id: true } })).map((o) => o.id);
    assert.ok(ids.length > 0);

    assert.equal((await call('DELETE', '/v1/account', u)).status, 200);
    const kept = await prisma.order.findMany({ where: { id: { in: ids } } });
    assert.equal(kept.length, ids.length);
    assert.ok(kept.every((o) => o.userId === null && o.status === 'PAID'));
    await prisma.order.deleteMany({ where: { id: { in: ids } } });
  });
});
