import type { FastifyPluginAsync } from 'fastify';
import { Prisma } from '@prisma/client';
import { z } from 'zod';
import type { CreateOrderResponse, OrderStatusResponse } from '@th/types';
import { prisma } from '../db.js';
import { env, isProd } from '../env.js';
import { resolveEntitlements } from '../services/entitlements.js';

/**
 * Commerce — scaffolded behind a real interface, running against a STUB gateway
 * until a merchant account and the IAP-vs-web decision land (docs/04 §5).
 *
 * The rules that must survive the switch to a real gateway:
 *   1. The amount is computed HERE. The client never sends a price (docs/04 T8).
 *   2. Entitlement is granted on the gateway webhook, never on the client
 *      telling us the payment succeeded.
 *   3. Settlement is EXACTLY-ONCE: gateways retry and overlap webhook
 *      deliveries, so the grant is claimed atomically.
 *   4. Availability is re-checked at settlement — the gap between "Pay" and
 *      the webhook can be hours. Money that arrives for something that is gone
 *      is flagged for refund (PAID_NOT_GRANTED), never silently granted.
 *
 * Swapping STUB for Razorpay means implementing createGatewayOrder() and the
 * webhook signature check. Nothing else in the app changes.
 */

const id = z.string().min(1).max(64);

const createSchema = z
  .object({
    productType: z.enum(['YOGA_SUBSCRIPTION', 'MARATHON_REGISTRATION', 'PREMIUM_UPGRADE', 'WORKSHOP']),
    productId: id,
    eventId: id.optional(),
    category: z.string().min(1).max(16).optional(),
    tier: z.enum(['CLASSIC', 'PREMIUM']).optional(),
    referralCode: z.string().trim().min(1).max(16).optional(),
  })
  .superRefine((d, ctx) => {
    // Without these the price lookup's filter silently vanishes and an
    // arbitrary distance gets charged and registered (findFirst ignores
    // undefined fields).
    if (d.productType === 'MARATHON_REGISTRATION' && (!d.eventId || !d.category)) {
      ctx.addIssue({ code: 'custom', message: 'eventId and category are required for a registration' });
    }
    if (d.productType === 'PREMIUM_UPGRADE' && !d.eventId) {
      ctx.addIssue({ code: 'custom', message: 'eventId is required for an upgrade' });
    }
  });

const YOGA_PLANS: Record<string, { label: string; paise: number; months: number }> = {
  yoga_monthly: { label: 'Monthly Plan', paise: 99900, months: 1 },
  yoga_annual: { label: 'Annual Membership', paise: 499900, months: 12 },
};

/**
 * A deliberate "not purchasable" answer, sent to the client as a 409 with this
 * code. Anything else thrown during pricing (a database fault) is NOT one of
 * these and goes to the global error handler — never echoed to the client.
 */
class PurchaseRefused extends Error {
  constructor(readonly code: string) {
    super(code);
  }
}

type Tx = Prisma.TransactionClient;

/**
 * Row-locks a workshop for the rest of the transaction. Capacity is
 * count-then-insert, and under Postgres's default READ COMMITTED two buyers
 * could otherwise both see "one seat left" and both take it.
 */
export async function lockWorkshop(tx: Tx, workshopId: string): Promise<void> {
  await tx.$queryRaw`SELECT id FROM "LiveWorkshop" WHERE id = ${workshopId} FOR UPDATE`;
}

/** Server-side price resolution. The single place any amount is decided. */
async function resolveAmountPaise(
  userId: string,
  input: z.infer<typeof createSchema>,
): Promise<number> {
  switch (input.productType) {
    case 'YOGA_SUBSCRIPTION': {
      const plan = YOGA_PLANS[input.productId];
      if (!plan) throw new PurchaseRefused('UNKNOWN_PLAN');
      return plan.paise;
    }
    case 'MARATHON_REGISTRATION': {
      const eventId = input.eventId!;
      // Refuse before any money moves, not after. One registration per edition.
      const existing = await prisma.marathonRegistration.findUnique({
        where: { userId_eventId: { userId, eventId } },
      });
      if (existing) throw new PurchaseRefused('ALREADY_REGISTERED');
      const event = await prisma.marathonEvent.findUnique({ where: { id: eventId } });
      // Never take money for a race that has already started, whatever the
      // stored flag says.
      if (!event?.registrationOpen || event.startsAt <= new Date()) throw new PurchaseRefused('REGISTRATION_CLOSED');
      const opt = await prisma.raceDistanceOption.findUnique({
        where: { eventId_code: { eventId, code: input.category! } },
      });
      if (!opt || !opt.registrationOpen) throw new PurchaseRefused('UNAVAILABLE');
      if (input.tier === 'PREMIUM') {
        if (opt.premiumSoldOut) throw new PurchaseRefused('SOLD_OUT');
        return opt.pricePremiumPaise;
      }
      return opt.priceClassicPaise;
    }
    case 'PREMIUM_UPGRADE': {
      const registration = await prisma.marathonRegistration.findUnique({
        where: { userId_eventId: { userId, eventId: input.eventId! } },
      });
      if (!registration) throw new PurchaseRefused('NOT_REGISTERED');
      if (registration.tier === 'PREMIUM') throw new PurchaseRefused('ALREADY_PREMIUM');
      // An upgrade is for a race morning still ahead — never charge for one
      // that has been run.
      const race = await prisma.marathonEvent.findUnique({
        where: { id: registration.eventId },
        select: { startsAt: true },
      });
      if (!race || race.startsAt <= new Date()) throw new PurchaseRefused('REGISTRATION_CLOSED');
      const opt = await prisma.raceDistanceOption.findUnique({
        where: { eventId_code: { eventId: registration.eventId, code: registration.category } },
      });
      if (!opt) throw new PurchaseRefused('UNAVAILABLE');
      if (opt.premiumSoldOut) throw new PurchaseRefused('SOLD_OUT');
      // §8.3: "Classic holders pay the net difference."
      return Math.max(0, opt.pricePremiumPaise - opt.priceClassicPaise);
    }
    case 'WORKSHOP': {
      const w = await prisma.liveWorkshop.findUnique({
        where: { id: input.productId },
        include: { _count: { select: { registrations: true } } },
      });
      if (!w) throw new PurchaseRefused('UNAVAILABLE');
      // Joining a live session late is fine; paying for one that is over is not.
      const endsAt = w.startsAt.getTime() + w.durationMinutes * 60_000;
      if (endsAt <= Date.now()) throw new PurchaseRefused('WORKSHOP_ENDED');
      // Never charge for a seat the user already holds, or one that is free
      // for them (yoga members get yoga workshops free — they register directly).
      const seated = await prisma.workshopRegistration.findUnique({
        where: { userId_workshopId: { userId, workshopId: w.id } },
      });
      if (seated) throw new PurchaseRefused('ALREADY_REGISTERED');
      const { persona } = await resolveEntitlements(userId);
      if (w.pricePaise === null || (persona.hasYoga && w.category === 'YOGA')) {
        throw new PurchaseRefused('FREE_FOR_YOU');
      }
      if (w._count.registrations >= w.totalCapacity) throw new PurchaseRefused('WORKSHOP_FULL');
      return w.pricePaise;
    }
    default:
      throw new PurchaseRefused('UNAVAILABLE');
  }
}

/**
 * Refer & Win attribution (§8.3). Records the friend whose code this user came
 * with — first code wins, never your own. It earns the friend nothing yet: a
 * referral is credited only on this user's first PAID registration (docs/04 T2,
 * the defence against farming with throwaway signups).
 */
export async function applyReferralCode(
  userId: string,
  rawCode: string,
): Promise<'APPLIED' | 'ALREADY_SET' | 'INVALID' | 'OWN_CODE'> {
  const code = rawCode.trim().toUpperCase();
  const referral = await prisma.referral.findUnique({ where: { code } });
  if (!referral) return 'INVALID';
  if (referral.userId === userId) return 'OWN_CODE';
  const set = await prisma.user.updateMany({
    where: { id: userId, referredByCode: null },
    data: { referredByCode: code },
  });
  return set.count === 1 ? 'APPLIED' : 'ALREADY_SET';
}

const routes: FastifyPluginAsync = async (app) => {
  app.post('/orders', { preHandler: app.requireAuth }, async (req, reply): Promise<CreateOrderResponse | undefined> => {
    const parsed = createSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ code: 'INVALID_BODY', message: 'Invalid order payload' });
    }

    let amountPaise: number;
    try {
      amountPaise = await resolveAmountPaise(req.user.id, parsed.data);
    } catch (cause) {
      if (!(cause instanceof PurchaseRefused)) throw cause;
      return reply.code(409).send({
        code: cause.code,
        message: 'This product is not purchasable right now',
      });
    }

    // A friend's code typed at race checkout. A wrong code is the user's to
    // fix before paying — say so rather than quietly dropping it.
    if (parsed.data.referralCode && parsed.data.productType === 'MARATHON_REGISTRATION') {
      const outcome = await applyReferralCode(req.user.id, parsed.data.referralCode);
      if (outcome === 'INVALID' || outcome === 'OWN_CODE') {
        return reply.code(409).send({
          code: outcome === 'OWN_CODE' ? 'OWN_REFERRAL_CODE' : 'INVALID_REFERRAL_CODE',
          message: outcome === 'OWN_CODE' ? 'You can’t use your own referral code.' : 'That referral code isn’t valid.',
        });
      }
    }

    const product = {
      userId: req.user.id,
      productType: parsed.data.productType,
      productId: parsed.data.productId,
      eventId: parsed.data.eventId ?? null,
      category: parsed.data.category ?? null,
      tier: parsed.data.tier ?? null,
      amountPaise,
    };
    // Idempotent: an unpaid order for exactly this, at this price, from the
    // last 30 minutes is handed back instead of a second one (app restarted
    // mid-checkout, a double tap, two devices). Paying it is idempotent at the
    // gateway, so one purchase can never become two charges.
    const reusable = await prisma.order.findFirst({
      where: { ...product, status: 'CREATED', createdAt: { gt: new Date(Date.now() - REUSE_ORDER_MS) } },
      orderBy: { createdAt: 'desc' },
    });
    const order =
      reusable ??
      (await prisma.order.create({
        data: { ...product, gateway: env.razorpayKeyId ? 'RAZORPAY' : 'STUB', status: 'CREATED' },
      }));

    return {
      orderId: order.id,
      gateway: order.gateway === 'RAZORPAY' ? 'RAZORPAY' : 'STUB',
      // TODO(payments): create a real gateway order once a merchant account
      // exists. Until then the stub id lets the client exercise the full flow.
      gatewayOrderId: order.gatewayOrderId ?? `stub_${order.id}`,
      amountPaise,
      currency: 'INR',
      gatewayKeyId: env.razorpayKeyId,
    };
  });

  app.get('/orders/:id', { preHandler: app.requireAuth }, async (req, reply): Promise<OrderStatusResponse | undefined> => {
    const { id: orderId } = req.params as { id: string };
    const order = await prisma.order.findUnique({ where: { id: orderId } });
    if (!order || order.userId !== req.user.id) {
      return reply.code(404).send({ code: 'NOT_FOUND', message: 'Order not found' });
    }
    return {
      orderId: order.id,
      status: order.status as OrderStatusResponse['status'],
      entitlementGranted: order.entitlementGranted,
    };
  });

  /**
   * Gateway webhook. The ONLY place a real payment grants entitlement.
   *
   * FAILS CLOSED: until the gateway's signature check (and the paid amount +
   * currency check against order.amountPaise) is implemented, this refuses
   * every call — in every environment. An unauthenticated webhook is a
   * free-everything endpoint for anyone who can guess an order id. Development
   * settles through the authenticated simulator below instead.
   */
  const WEBHOOK_SIGNATURE_VERIFIED = false;
  app.post('/webhooks/payment', async (req, reply) => {
    if (!WEBHOOK_SIGNATURE_VERIFIED) {
      return reply.code(503).send({ code: 'NOT_CONFIGURED', message: 'Payment gateway not configured' });
    }
    const body = z
      .object({ orderId: id, status: z.enum(['PAID', 'FAILED']), paymentId: z.string().max(128).optional() })
      .safeParse(req.body);
    if (!body.success) {
      return reply.code(400).send({ code: 'INVALID_BODY', message: 'Invalid webhook payload' });
    }
    // TODO(payments): verify X-Razorpay-Signature against RAZORPAY_KEY_SECRET
    // and that the captured amount/currency equal the order's, THEN flip the
    // flag above.
    const result = await settleOrder(body.data.orderId, body.data.status, body.data.paymentId);
    if (result === 'NOT_FOUND') {
      return reply.code(404).send({ code: 'NOT_FOUND', message: 'Order not found' });
    }
    return { ok: true, result };
  });

  /**
   * DEVELOPMENT ONLY — stands in for the payment gateway until a merchant
   * account exists. It runs settleOrder(), the exact function the real webhook
   * runs, so the whole purchase → entitlement → UI loop can be exercised.
   *
   * Hard-disabled in production. It is authenticated and restricted to the
   * caller's own orders, so it cannot grant entitlement to anyone else.
   */
  app.post('/orders/:id/simulate-payment', { preHandler: app.requireAuth }, async (req, reply) => {
    if (isProd) {
      return reply.code(404).send({ code: 'NOT_FOUND', message: 'Not found' });
    }
    const { id: orderId } = req.params as { id: string };
    const order = await prisma.order.findUnique({ where: { id: orderId } });
    if (!order || order.userId !== req.user.id) {
      return reply.code(404).send({ code: 'NOT_FOUND', message: 'Order not found' });
    }
    const result = await settleOrder(order.id, 'PAID', `sim_${Date.now()}`);
    return { ok: true, result };
  });
};

/** How long an unpaid order is reused for the same purchase (see POST /orders). */
const REUSE_ORDER_MS = 30 * 60_000;

type SettleResult = 'GRANTED' | 'ALREADY_GRANTED' | 'FAILED' | 'NOT_FOUND' | 'NEEDS_REFUND';

/**
 * The single place entitlement is granted for a purchase. Called by the gateway
 * webhook and by the development simulator, never by the client directly.
 *
 * EXACTLY ONCE. The claim is a conditional update inside the transaction:
 * whichever delivery flips entitlementGranted false→true does the grant; a
 * concurrent or retried delivery finds nothing to claim and stops. (A plain
 * read-then-write let two overlapping webhooks both extend a subscription.)
 */
export async function settleOrder(
  orderId: string,
  status: 'PAID' | 'FAILED',
  paymentId?: string,
): Promise<SettleResult> {
  const order = await prisma.order.findUnique({ where: { id: orderId } });
  if (!order) return 'NOT_FOUND';

  if (status === 'FAILED') {
    // Never downgrade an order that has already been paid and settled.
    await prisma.order.updateMany({
      where: { id: order.id, entitlementGranted: false, status: { in: ['CREATED', 'PENDING'] } },
      data: { status: 'FAILED' },
    });
    return order.entitlementGranted ? 'ALREADY_GRANTED' : 'FAILED';
  }

  return prisma.$transaction(async (tx) => {
    const claimed = await tx.order.updateMany({
      where: { id: order.id, entitlementGranted: false, status: { not: 'PAID_NOT_GRANTED' } },
      data: { status: 'PAID', gatewayPaymentId: paymentId ?? null, entitlementGranted: true },
    });
    if (claimed.count === 0) {
      // A retry of a payment already flagged for refund stays flagged.
      return order.status === 'PAID_NOT_GRANTED' ? ('NEEDS_REFUND' as const) : ('ALREADY_GRANTED' as const);
    }

    const refusal = await grantProduct(tx, order);
    if (refusal) {
      // Paid, but there is nothing left to give. Record it for a refund —
      // and still commit, so the gateway stops retrying a captured payment.
      await tx.order.update({
        where: { id: order.id },
        data: { status: 'PAID_NOT_GRANTED', entitlementGranted: false, settlementNote: refusal },
      });
      return 'NEEDS_REFUND' as const;
    }
    return 'GRANTED' as const;
  });
}

type OrderRow = NonNullable<Awaited<ReturnType<typeof prisma.order.findUnique>>>;

/**
 * Grants what the order bought, re-checking availability at THIS moment.
 * Returns null on success, or a short reason when it can no longer be granted.
 */
async function grantProduct(tx: Tx, order: OrderRow): Promise<string | null> {
  // The buyer deleted their account while the payment was in flight: there is
  // no one to grant it to, so it is refunded like any other unfulfillable order.
  const userId = order.userId;
  if (!userId) return 'Account deleted before the payment settled';
  const now = new Date();
  switch (order.productType) {
    case 'YOGA_SUBSCRIPTION': {
      const plan = YOGA_PLANS[order.productId] ?? YOGA_PLANS.yoga_annual!;
      // A renewal bought BEFORE expiry extends from the current expiry —
      // paying mid-term must never eat the time already paid for. Expired,
      // cancelled (e.g. refunded) or no subscription starts its term today:
      // a refunded remainder is not handed back on the next purchase.
      const existing = await tx.yogaSubscription.findUnique({ where: { userId: userId } });
      const base =
        existing && existing.status === 'ACTIVE' && existing.expiresAt > now ? existing.expiresAt : now;
      const expires = new Date(base);
      expires.setMonth(expires.getMonth() + plan.months);
      await tx.yogaSubscription.upsert({
        where: { userId: userId },
        create: {
          userId: userId,
          planId: order.productId,
          planLabel: plan.label,
          status: 'ACTIVE',
          startedAt: now,
          expiresAt: expires,
          // Nothing charges a renewal until a recurring rail exists (IAP /
          // e-mandate — docs/04 §5), so the app must not promise one.
          autoRenews: false,
        },
        // A renewal keeps the original startedAt, so attendance history and
        // the "streak history is preserved" promise (§5) both hold.
        update: {
          status: 'ACTIVE',
          planId: order.productId,
          planLabel: plan.label,
          expiresAt: expires,
          autoRenews: false,
        },
      });
      return null;
    }

    case 'MARATHON_REGISTRATION': {
      if (!order.eventId || !order.category) return 'ORDER_INCOMPLETE';
      const event = await tx.marathonEvent.findUnique({ where: { id: order.eventId } });
      if (!event?.registrationOpen || event.startsAt <= now) return 'REGISTRATION_CLOSED';
      const opt = await tx.raceDistanceOption.findUnique({
        where: { eventId_code: { eventId: order.eventId, code: order.category } },
      });
      if (!opt || !opt.registrationOpen) return 'DISTANCE_CLOSED';
      if (order.tier === 'PREMIUM' && opt.premiumSoldOut) return 'PREMIUM_SOLD_OUT';
      const existing = await tx.marathonRegistration.findUnique({
        where: { userId_eventId: { userId: userId, eventId: order.eventId } },
      });
      if (existing) return 'ALREADY_REGISTERED';
      await tx.marathonRegistration.create({
        data: {
          userId: userId,
          eventId: order.eventId,
          registrationRef: `TH-${order.id.slice(-8).toUpperCase()}`,
          tier: order.tier ?? 'CLASSIC',
          category: order.category,
          bibNumber: null,
        },
      });
      await creditReferrer(tx, order);
      return null;
    }

    case 'PREMIUM_UPGRADE': {
      if (!order.eventId) return 'ORDER_INCOMPLETE';
      const registration = await tx.marathonRegistration.findUnique({
        where: { userId_eventId: { userId: userId, eventId: order.eventId } },
        include: { event: { select: { startsAt: true } } },
      });
      // Deleted account / removed registration: refund rather than crash the
      // settlement (a throw here would make the gateway retry forever).
      if (!registration) return 'NOT_REGISTERED';
      if (registration.tier === 'PREMIUM') return 'ALREADY_PREMIUM';
      if (registration.event.startsAt <= now) return 'RACE_STARTED';
      const opt = await tx.raceDistanceOption.findUnique({
        where: { eventId_code: { eventId: order.eventId, code: registration.category } },
      });
      if (!opt || opt.premiumSoldOut) return 'PREMIUM_SOLD_OUT';
      await tx.marathonRegistration.update({
        where: { id: registration.id },
        data: { tier: 'PREMIUM' },
      });
      return null;
    }

    case 'WORKSHOP': {
      await lockWorkshop(tx, order.productId);
      const w = await tx.liveWorkshop.findUnique({
        where: { id: order.productId },
        include: { _count: { select: { registrations: true } } },
      });
      if (!w) return 'WORKSHOP_GONE';
      if (w.startsAt.getTime() + w.durationMinutes * 60_000 <= now.getTime()) return 'WORKSHOP_ENDED';
      const seated = await tx.workshopRegistration.findUnique({
        where: { userId_workshopId: { userId: userId, workshopId: w.id } },
      });
      if (seated) return 'ALREADY_REGISTERED';
      if (w._count.registrations >= w.totalCapacity) return 'WORKSHOP_FULL';
      await tx.workshopRegistration.create({ data: { userId: userId, workshopId: w.id } });
      return null;
    }

    default:
      return 'UNKNOWN_PRODUCT';
  }
}

/**
 * Refer & Win (§8.3): 1 referral = 1 lucky-draw entry; 5 referrals = a
 * guaranteed Premium upgrade. A referral is a FRIEND'S FIRST PAID registration
 * (never a signup — docs/04 T2), credited at most once per friend per referrer.
 */
async function creditReferrer(tx: Tx, order: OrderRow): Promise<void> {
  const buyerId = order.userId;
  if (!buyerId) return;
  const buyer = await tx.user.findUnique({
    where: { id: buyerId },
    select: { referredByCode: true },
  });
  if (!buyer?.referredByCode) return;
  const referral = await tx.referral.findUnique({ where: { code: buyer.referredByCode } });
  if (!referral || referral.userId === buyerId) return;

  // ON CONFLICT DO NOTHING: a friend who already earned this referrer a credit
  // (an earlier race) adds nothing. Catching a unique violation instead would
  // leave the Postgres transaction aborted and fail the whole settlement.
  const inserted = await tx.referralCredit.createMany({
    data: [{ referralId: referral.id, referredUserId: buyerId, orderId: order.id }],
    skipDuplicates: true,
  });
  if (inserted.count === 0) return;

  const updated = await tx.referral.update({
    where: { id: referral.id },
    data: { confirmedReferrals: { increment: 1 }, luckyDrawEntries: { increment: 1 } },
  });
  if (updated.confirmedReferrals >= 5 && !updated.guaranteedUpgradeUnlocked) {
    await tx.referral.update({ where: { id: referral.id }, data: { guaranteedUpgradeUnlocked: true } });
  }
}

export default routes;
