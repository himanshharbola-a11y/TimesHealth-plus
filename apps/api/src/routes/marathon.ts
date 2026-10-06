import { randomInt } from 'node:crypto';
import { Prisma } from '@prisma/client';
import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import type {
  DigitalBib,
  MarathonEvent,
  MarathonListResponse,
  RaceDetailResponse,
  RaceResult,
} from '@th/types';
import { prisma } from '../db.js';
import { resolveEntitlements } from '../services/entitlements.js';
import { signBibToken, verifyBibToken } from '../services/media.js';
import { applyReferralCode } from './orders.js';
import { env, isProd } from '../env.js';
import { timingSafeEqual } from 'node:crypto';
import { formatIstWindow } from '../time.js';
import { normalizePhone } from '../identity/normalize.js';

const participantSchema = z.object({
  tshirtSize: z.string().max(10).optional(),
  emergencyContactName: z.string().max(80).optional(),
  emergencyContactPhone: z.string().max(20).optional(),
});

const nearbySchema = z.object({
  lat: z.coerce.number().min(-90).max(90).optional(),
  lng: z.coerce.number().min(-180).max(180).optional(),
});

/** Great-circle distance in km. */
function haversineKm(aLat: number, aLng: number, bLat: number, bLng: number): number {
  const R = 6371;
  const dLat = ((bLat - aLat) * Math.PI) / 180;
  const dLng = ((bLng - aLng) * Math.PI) / 180;
  const s =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((aLat * Math.PI) / 180) * Math.cos((bLat * Math.PI) / 180) * Math.sin(dLng / 2) ** 2;
  return Math.round(2 * R * Math.asin(Math.sqrt(s)));
}

type EventRow = Awaited<ReturnType<typeof loadEvents>>[number];

function loadEvents() {
  return prisma.marathonEvent.findMany({
    include: { distanceOptions: { orderBy: { sortOrder: 'asc' } } },
    orderBy: { startsAt: 'asc' },
  });
}

function toEventDto(
  e: EventRow,
  registration: MarathonEvent['registration'],
  distanceFromUserKm: number | null,
): MarathonEvent {
  // Registration closes when the race starts, whatever the stored flag says.
  const notStarted = e.startsAt > new Date();
  return {
    id: e.id,
    name: e.name,
    city: e.city,
    venue: e.venue,
    imageUrl: e.imageUrl,
    startsAt: e.startsAt.toISOString(),
    flagOffTime: e.flagOffTime,
    distanceOptions: e.distanceOptions.map((d) => ({
      code: d.code,
      label: d.label,
      pricePaise: { CLASSIC: d.priceClassicPaise, PREMIUM: d.pricePremiumPaise },
      wasPricePaise:
        d.wasPriceClassicPaise && d.wasPricePremiumPaise
          ? { CLASSIC: d.wasPriceClassicPaise, PREMIUM: d.wasPricePremiumPaise }
          : null,
      premiumSoldOut: d.premiumSoldOut,
      registrationOpen: d.registrationOpen && notStarted,
    })),
    registrationOpen: e.registrationOpen && notStarted,
    rescheduledFrom: e.rescheduledFrom?.toISOString() ?? null,
    expo: e.expoVenue
      ? {
          venue: e.expoVenue,
          address: e.expoAddress ?? '',
          startsAt: e.expoStartsAt?.toISOString() ?? '',
          endsAt: e.expoEndsAt?.toISOString() ?? '',
          // From the real expo times when set; the stored text is only a fallback.
          pickupWindow:
            e.expoStartsAt && e.expoEndsAt
              ? formatIstWindow(e.expoStartsAt, e.expoEndsAt)
              : (e.expoPickupWindow ?? ''),
          instructions: e.expoInstructions ?? '',
          requiredDocuments: e.expoDocuments,
        }
      : null,
    registration,
    distanceFromUserKm,
  };
}

const routes: FastifyPluginAsync = async (app) => {
  /**
   * Races tab — PRD §8.2 box ordering:
   *   1. registered edition on top
   *   2. else nearest by location, if coordinates were supplied
   *   3. else next upcoming by date
   *
   * Ordering is resolved here so the location fallback is silent. The client
   * sends coordinates if it has them and never asks the user twice.
   */
  app.get('/marathon/events', { preHandler: app.requireAuth }, async (req): Promise<MarathonListResponse> => {
    const coords = nearbySchema.safeParse(req.query);
    const lat = coords.success ? coords.data.lat : undefined;
    const lng = coords.success ? coords.data.lng : undefined;

    const now = new Date();
    const [{ entitlements }, allEvents] = await Promise.all([
      resolveEntitlements(req.user.id),
      loadEvents(),
    ]);
    const byEvent = new Map(entitlements.marathon.map((m) => [m.eventId, m]));

    // Past editions are shown only to people who ran them — their box becomes
    // "Check Result" (§8.4). Everyone else sees upcoming editions only, never a
    // finished race offered as open for registration.
    const events = allEvents.filter((e) => e.startsAt > now || byEvent.has(e.id));

    const dtos = events.map((e) => {
      const dist =
        lat !== undefined && lng !== undefined && e.latitude !== null && e.longitude !== null
          ? haversineKm(lat, lng, e.latitude, e.longitude)
          : null;
      return toEventDto(e, byEvent.get(e.id) ?? null, dist);
    });

    const registered = dtos.filter((d) => d.registration !== null);
    const rest = dtos.filter((d) => d.registration === null);

    let orderedBy: MarathonListResponse['orderedBy'] = 'DATE';
    if (registered.length > 0) {
      orderedBy = 'REGISTRATION';
      // The race still ahead leads; races already run follow, most recent first.
      // Otherwise a finisher who has signed up again would see last year's
      // result in the primary box instead of their next race.
      const nowIso = now.toISOString();
      registered.sort((a, b) => {
        const aAhead = a.startsAt > nowIso;
        const bAhead = b.startsAt > nowIso;
        if (aAhead !== bAhead) return aAhead ? -1 : 1;
        return aAhead ? a.startsAt.localeCompare(b.startsAt) : b.startsAt.localeCompare(a.startsAt);
      });
    }
    if (lat !== undefined && lng !== undefined && rest.some((r) => r.distanceFromUserKm !== null)) {
      if (registered.length === 0) orderedBy = 'LOCATION';
      rest.sort(
        (a, b) => (a.distanceFromUserKm ?? Infinity) - (b.distanceFromUserKm ?? Infinity),
      );
    } else {
      rest.sort((a, b) => a.startsAt.localeCompare(b.startsAt));
    }

    return { events: [...registered, ...rest], orderedBy };
  });

  /** Race detail — everything currently on the web dashboard (§8.3). */
  app.get('/marathon/events/:id', { preHandler: app.requireAuth }, async (req, reply): Promise<RaceDetailResponse | undefined> => {
    const { id } = req.params as { id: string };

    const [{ entitlements }, event] = await Promise.all([
      resolveEntitlements(req.user.id),
      prisma.marathonEvent.findUnique({
        where: { id },
        include: {
          distanceOptions: { orderBy: { sortOrder: 'asc' } },
          faqs: { orderBy: { sortOrder: 'asc' } },
        },
      }),
    ]);
    if (!event) {
      return reply.code(404).send({ code: 'NOT_FOUND', message: 'Event not found' });
    }

    const ent = entitlements.marathon.find((m) => m.eventId === id) ?? null;
    const registration = ent
      ? await prisma.marathonRegistration.findUnique({
          where: { userId_eventId: { userId: req.user.id, eventId: id } },
          include: { result: true, kit: true },
        })
      : null;

    // §8.3 Digital bib. Signed, short TTL — never a bare bib number (docs/04 T1).
    let bib: DigitalBib | null = null;
    if (registration?.bibNumber) {
      const signed = signBibToken(registration.registrationRef, 'LIVE');
      const offline = signBibToken(registration.registrationRef, 'PASS');
      bib = {
        bibNumber: registration.bibNumber,
        participantName: req.user.name ?? '',
        category: registration.category,
        tier: registration.tier === 'PREMIUM' ? 'PREMIUM' : 'CLASSIC',
        eventName: event.name,
        qrToken: signed.token,
        qrExpiresAt: signed.expiresAt.toISOString(),
        offlinePayload: offline.token,
      };
    }

    // §8.4: pending is a state, not an empty screen.
    let result: RaceResult | null = null;
    if (registration?.result) {
      const r = registration.result;
      result = {
        published: r.published,
        finishTime: r.finishTime,
        chipTime: r.chipTime,
        avgPace: r.avgPace,
        overallRank: r.overallRank,
        ageGroupRank: r.ageGroupRank,
        category: registration.category,
        splits: (r.splits as RaceResult['splits'] | null) ?? [],
        certificateUrl: r.certificateUrl,
        medalStatus: r.medalStatus,
        photoUrls: r.photoUrls,
      };
    }

    // §8.3 edge cases: suppressed entirely when sold out or already Premium —
    // and once the race has started, when there is no race morning left to
    // upgrade (or to refer anyone into).
    const raceAhead = event.startsAt > new Date();
    const option = event.distanceOptions.find((d) => d.code === registration?.category);
    const alreadyPremium = registration?.tier === 'PREMIUM';

    const referralRow =
      registration && raceAhead
        ? await prisma.referral.findUnique({ where: { userId: req.user.id } })
        : null;
    // Refer & Win: 5 referrals earn a GUARANTEED upgrade — offered free, and
    // honoured even if paid Premium has sold out ("guaranteed" means it).
    const freeClaim =
      referralRow !== null && referralRow.guaranteedUpgradeUnlocked && referralRow.upgradeClaimedAt === null;

    const upgradeOffer =
      registration && raceAhead && option && !alreadyPremium && (freeClaim || !option.premiumSoldOut)
        ? {
            available: true,
            // Net difference, computed server-side. The client never prices it.
            netDifferencePaise: freeClaim ? 0 : Math.max(0, option.pricePremiumPaise - option.priceClassicPaise),
            benefits: PREMIUM_BENEFITS,
            freeClaim,
          }
        : null;

    return {
      event: toEventDto(event, ent, null),
      bib,
      result,
      kit: registration?.kit
        ? {
            status: registration.kit.status as never,
            courierName: registration.kit.courierName,
            trackingRef: registration.kit.trackingRef,
            tshirtSize: registration.tshirtSize,
            expectedBy: registration.kit.expectedBy?.toISOString() ?? null,
          }
        : null,
      referral: referralRow ? toReferralDto(referralRow) : null,
      upgradeOffer,
      faqs: event.faqs.map((f) => ({ question: f.question, answer: f.answer })),
      participant: registration
        ? {
            tshirtSize: registration.tshirtSize,
            emergencyContactName: registration.emergencyContactName,
            emergencyContactPhone: registration.emergencyContactPhone,
          }
        : null,
    };
  });

  /** Short-TTL bib refresh. The client re-requests while the QR is on screen. */
  app.get('/marathon/events/:id/bib-token', { preHandler: app.requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const registration = await prisma.marathonRegistration.findUnique({
      where: { userId_eventId: { userId: req.user.id, eventId: id } },
    });
    if (!registration?.bibNumber) {
      return reply.code(404).send({ code: 'NOT_FOUND', message: 'No bib for this event' });
    }
    const signed = signBibToken(registration.registrationRef, 'LIVE');
    return { qrToken: signed.token, qrExpiresAt: signed.expiresAt.toISOString() };
  });

  /**
   * Expo scanner validation. Not called by the app — this is the endpoint the
   * venue scanner hits.
   *
   * Only an authenticated SCANNER may call it (x-scanner-key): it returns the
   * runner's name, so it must not be a public lookup. Every scan is recorded
   * and the response lists earlier scans — a pass already used to collect a
   * kit is visible at the counter, and so is an offline (7-day) pass.
   */
  app.post(
    '/marathon/verify-bib',
    { config: { rateLimit: { max: 120, timeWindow: '1 minute' } } },
    async (req, reply) => {
      const scannerId = authenticateScanner(req.headers['x-scanner-key']);
      if (!scannerId) {
        return reply.code(401).send({ valid: false, reason: 'SCANNER_NOT_AUTHORISED' });
      }
      const body = z.object({ token: z.string().max(256) }).safeParse(req.body);
      if (!body.success) {
        return reply.code(400).send({ code: 'INVALID_BODY', message: 'token required' });
      }
      const verified = verifyBibToken(body.data.token);
      if (!verified) {
        return reply.code(401).send({ valid: false, reason: 'INVALID_OR_EXPIRED' });
      }
      const registration = await prisma.marathonRegistration.findUnique({
        where: { registrationRef: verified.registrationRef },
        include: {
          user: { select: { name: true } },
          event: { select: { name: true } },
          scans: { orderBy: { scannedAt: 'asc' }, select: { scannedAt: true, scannerId: true } },
        },
      });
      if (!registration) return reply.code(404).send({ valid: false, reason: 'NOT_FOUND' });

      await prisma.bibScan.create({
        data: { registrationId: registration.id, scannerId, tokenKind: verified.kind },
      });

      return {
        valid: true,
        tokenKind: verified.kind,
        bibNumber: registration.bibNumber,
        participantName: registration.user.name,
        category: registration.category,
        tier: registration.tier,
        eventName: registration.event.name,
        previousScans: registration.scans.map((s) => ({
          scannedAt: s.scannedAt.toISOString(),
          scannerId: s.scannerId,
        })),
      };
    },
  );

  app.patch('/marathon/events/:id/participant', { preHandler: app.requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const parsed = participantSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ code: 'INVALID_BODY', message: 'Invalid participant payload' });
    }
    const existing = await prisma.marathonRegistration.findUnique({
      where: { userId_eventId: { userId: req.user.id, eventId: id } },
      include: { event: { select: { startsAt: true } } },
    });
    if (!existing) {
      return reply.code(404).send({ code: 'NOT_FOUND', message: 'No registration' });
    }
    // After flag-off the organiser's records are final.
    if (existing.event.startsAt <= new Date()) {
      return reply.code(409).send({ code: 'RACE_STARTED', message: 'This race has started, so details can’t be changed.' });
    }
    // An emptied field clears it (null), rather than storing "".
    const data: { tshirtSize?: string | null; emergencyContactName?: string | null; emergencyContactPhone?: string | null } = {};
    for (const [k, v] of Object.entries(parsed.data) as [keyof typeof data, string | undefined][]) {
      if (v !== undefined) data[k] = v.trim() === '' ? null : v.trim();
    }
    // The medical team phones this number on race day — it must be callable.
    if (data.emergencyContactPhone) {
      const phone = normalizePhone(data.emergencyContactPhone);
      if (!phone) return reply.code(400).send({ code: 'INVALID_PHONE', message: 'Enter a valid mobile number' });
      data.emergencyContactPhone = phone;
    }
    await prisma.marathonRegistration.update({ where: { id: existing.id }, data });
    return { ok: true };
  });

  /** §8.3 Refer & Win. Surfaced as its own element in the tab, not buried. */
  app.get('/marathon/referral', { preHandler: app.requireAuth }, async (req) => {
    return toReferralDto(await getOrCreateReferral(req.user.id));
  });

  /**
   * A friend's code captured from their shared link (deep link) or typed by
   * the user. Attribution only — the friend is credited when this user's
   * first race registration is PAID (orders.ts creditReferrer).
   */
  app.post('/marathon/referral/apply', { preHandler: app.requireAuth }, async (req, reply) => {
    const body = z.object({ code: z.string().trim().min(4).max(16) }).safeParse(req.body);
    if (!body.success) {
      return reply.code(400).send({ code: 'INVALID_BODY', message: 'code required' });
    }
    const outcome = await applyReferralCode(req.user.id, body.data.code);
    if (outcome === 'INVALID') {
      return reply.code(404).send({ code: 'INVALID_REFERRAL_CODE', message: 'That referral code isn’t valid.' });
    }
    if (outcome === 'OWN_CODE') {
      return reply.code(409).send({ code: 'OWN_REFERRAL_CODE', message: 'You can’t use your own referral code.' });
    }
    return { applied: outcome === 'APPLIED' };
  });

  /**
   * Claims the GUARANTEED Premium upgrade earned with 5 referrals (§8.3) on
   * one upcoming registration. Free, and once only — the claim is atomic, so
   * two taps (or two races) can't both use it.
   */
  app.post('/marathon/events/:id/claim-upgrade', { preHandler: app.requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const registration = await prisma.marathonRegistration.findUnique({
      where: { userId_eventId: { userId: req.user.id, eventId: id } },
      include: { event: { select: { startsAt: true } } },
    });
    if (!registration) return reply.code(404).send({ code: 'NOT_REGISTERED', message: 'No registration' });
    if (registration.tier === 'PREMIUM') {
      return reply.code(409).send({ code: 'ALREADY_PREMIUM', message: 'You already have Premium VIP.' });
    }
    if (registration.event.startsAt <= new Date()) {
      return reply.code(409).send({ code: 'REGISTRATION_CLOSED', message: 'This race has already started.' });
    }

    const claimed = await prisma.$transaction(async (tx) => {
      const used = await tx.referral.updateMany({
        where: { userId: req.user.id, guaranteedUpgradeUnlocked: true, upgradeClaimedAt: null },
        data: { upgradeClaimedAt: new Date() },
      });
      if (used.count === 0) return false;
      await tx.marathonRegistration.update({ where: { id: registration.id }, data: { tier: 'PREMIUM' } });
      return true;
    });
    if (!claimed) {
      return reply.code(409).send({ code: 'NO_FREE_UPGRADE', message: 'No earned upgrade to claim.' });
    }
    return { ok: true, tier: 'PREMIUM' };
  });
};

const PREMIUM_BENEFITS = [
  'Reserved parking',
  'Kit delivery to your door',
  'Separate start wave',
  'On-site physio support',
];

function toReferralDto(row: {
  code: string;
  confirmedReferrals: number;
  luckyDrawEntries: number;
  guaranteedUpgradeUnlocked: boolean;
  upgradeClaimedAt: Date | null;
}) {
  return {
    code: row.code,
    // Until the web funnel domain exists (docs/01 F6) the share text also
    // carries the code itself, which the friend types at race checkout.
    shareUrl: `${env.shareBaseUrl}/r/${row.code}`,
    confirmedReferrals: row.confirmedReferrals,
    luckyDrawEntries: row.luckyDrawEntries,
    guaranteedUpgradeUnlocked: row.guaranteedUpgradeUnlocked,
    upgradeClaimed: row.upgradeClaimedAt !== null,
  };
}

/**
 * The scanner's identity, or null. SCANNER_KEYS is "id:key,id:key" — one key
 * per expo counter so a leaked key can be revoked alone. Outside production
 * with no keys configured, a local scanner is allowed for testing.
 */
function authenticateScanner(header: string | string[] | undefined): string | null {
  const presented = typeof header === 'string' ? header : '';
  if (env.scannerKeys.length === 0) return isProd ? null : 'dev-scanner';
  for (const { id: scannerId, key } of env.scannerKeys) {
    const a = Buffer.from(presented);
    const b = Buffer.from(key);
    if (a.length === b.length && timingSafeEqual(a, b)) return scannerId;
  }
  return null;
}

// No 0/O or 1/I: codes are read aloud and retyped from WhatsApp.
const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

/**
 * A random code, not one derived from the user id: the tail of an id repeats
 * across users long before 100k accounts, and a duplicate would lock that user
 * out of Refer & Win. 32^6 ≈ 1 billion codes, so a redraw is rare even at 10M.
 */
function newReferralCode(): string {
  let code = 'TH';
  for (let i = 0; i < 6; i += 1) code += CODE_ALPHABET[randomInt(CODE_ALPHABET.length)];
  return code;
}

async function getOrCreateReferral(userId: string) {
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const existing = await prisma.referral.findUnique({ where: { userId } });
    if (existing) return existing;
    try {
      return await prisma.referral.create({ data: { userId, code: newReferralCode() } });
    } catch (e) {
      // P2002 means either this user's row was created by a concurrent request
      // (found on the next pass) or the code is taken (a fresh one is drawn).
      if (!(e instanceof Prisma.PrismaClientKnownRequestError && e.code === 'P2002')) throw e;
    }
  }
  throw new Error('Could not allocate a referral code');
}

export default routes;
