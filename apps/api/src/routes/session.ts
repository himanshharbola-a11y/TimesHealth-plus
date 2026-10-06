import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import type { AppConfigResponse, SessionResponse, UserProfile } from '@th/types';
import { getAppConfig, prisma } from '../db.js';
import { LEGACY_UID_PREFIX } from '../auth.js';
import { identityProvider } from '../identity/index.js';
import { resolveEntitlements } from '../services/entitlements.js';
import { buildHomeFeed } from '../services/feed.js';
import { normalizeEmail, normalizePhone } from '../identity/normalize.js';
import type { User } from '@prisma/client';

export function toProfileDto(user: User): UserProfile {
  return {
    id: user.id,
    name: user.name,
    // The login identifier when there is one, else what the user typed.
    email: user.email ?? user.contactEmail,
    phone: user.phone ?? user.contactPhone,
    emailIsLogin: user.email !== null,
    phoneIsLogin: user.phone !== null,
    dob: user.dob?.toISOString() ?? null,
    gender: user.gender,
    healthGoal: (user.healthGoal as UserProfile['healthGoal']) ?? null,
    concern: (user.concern as UserProfile['concern']) ?? null,
    profileCompletion: user.profileCompletion,
    onboardingCompleted: user.onboardingCompleted,
    units: user.units === 'IMPERIAL' ? 'IMPERIAL' : 'METRIC',
    locale: user.locale,
  };
}

/** 0–100, shown in the profile drawer. Six fields, weighted evenly. */
function computeCompletion(u: Partial<User>): number {
  const fields = [
    u.name,
    u.email ?? u.contactEmail,
    u.phone ?? u.contactPhone,
    u.dob,
    u.healthGoal,
    u.concern,
  ];
  const filled = fields.filter((f) => f !== null && f !== undefined && f !== '').length;
  return Math.round((filled / fields.length) * 100);
}

// The Home rails are chosen from these (§6.3) — only real values are stored.
const healthGoalSchema = z.enum(['WEIGHT_LOSS', 'STRENGTH_FLEXIBILITY', 'STRESS_ANXIETY', 'MARATHON_TRAINING', 'CONSISTENCY']);
const concernSchema = z.enum(['LOWER_BACK', 'KNEES_JOINTS', 'NECK_SHOULDERS', 'HIPS_PELVIS', 'SLEEP_ENERGY', 'NONE']);

const onboardingSchema = z.object({
  step: z.union([z.literal(1), z.literal(2), z.literal(3), z.literal(4)]),
  name: z.string().trim().min(1).max(80).optional(),
  email: z.string().trim().email().max(160).optional(),
  phone: z.string().trim().min(8).max(20).optional(),
  healthGoal: healthGoalSchema.optional(),
  concern: concernSchema.optional(),
});

const profileSchema = z.object({
  name: z.string().trim().min(1).max(80).optional(),
  email: z.string().trim().email().max(160).optional(),
  phone: z.string().trim().min(8).max(20).optional(),
  // An age the app can't sensibly serve (or a date in the future) is a typo.
  dob: z
    .string()
    .datetime()
    .refine((v) => {
      const age = (Date.now() - Date.parse(v)) / (365.25 * 86_400_000);
      return age >= 13 && age <= 100;
    })
    .optional(),
  gender: z.enum(['FEMALE', 'MALE', 'NON_BINARY', 'PREFER_NOT_TO_SAY']).optional(),
  units: z.enum(['METRIC', 'IMPERIAL']).optional(),
  locale: z.string().max(10).optional(),
  // §5's personalisation isn't one-shot: a changed goal or focus re-orders Home.
  healthGoal: healthGoalSchema.optional(),
  concern: concernSchema.optional(),
});

const routes: FastifyPluginAsync = async (app) => {
  /**
   * Public launch config — no auth. The app checks this before anything else,
   * so the force-update gate and maintenance switch work even for a version
   * whose sign-in is broken.
   */
  app.get('/config', async (): Promise<AppConfigResponse> => {
    const config = await getAppConfig();
    return {
      minSupportedAppVersion: config.minSupportedAppVersion,
      maintenance: { active: config.maintenanceActive, message: config.maintenanceMessage },
      serverTime: new Date().toISOString(),
    };
  });

  /**
   * Called once on launch. Resolves identity, entitlement and the first screen.
   * PRD §5: a returning subscriber never sees onboarding again.
   */
  app.get('/session', { preHandler: app.requireAuth }, async (req): Promise<SessionResponse> => {
    const now = new Date();
    const [{ entitlements, persona }, config] = await Promise.all([
      resolveEntitlements(req.user.id, now),
      getAppConfig(),
    ]);

    const knownAccount = entitlements.yoga !== null || entitlements.marathon.length > 0;
    const needsOnboarding = !req.user.onboardingCompleted && !knownAccount;
    // Persist it, so the answer can never regress once the account is known.
    if (knownAccount && !req.user.onboardingCompleted) {
      await prisma.user.update({ where: { id: req.user.id }, data: { onboardingCompleted: true } });
    }

    return {
      profile: toProfileDto(req.user),
      entitlements,
      persona,
      // §5: a known account — ANY yoga subscription (active OR lapsed) or race
      // registration — never sees onboarding, ever. Keying this on an ACTIVE
      // plan sent a lapsed subscriber back through onboarding the day their
      // plan expired.
      needsOnboarding: needsOnboarding,
      serverTime: now.toISOString(),
      minSupportedAppVersion: config.minSupportedAppVersion,
      maintenance: {
        active: config.maintenanceActive,
        message: config.maintenanceMessage,
      },
    };
  });

  /**
   * PRD §5: every step is skippable and progress is saved as it goes, so a
   * partial drop still leaves usable lead data. Hence one endpoint per step
   * rather than a single submit at the end.
   */
  app.post('/onboarding/step', { preHandler: app.requireAuth }, async (req, reply) => {
    const parsed = onboardingSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ code: 'INVALID_BODY', message: 'Invalid onboarding payload' });
    }
    const { step, ...fields } = parsed.data;

    const data: Record<string, unknown> = {};
    if (fields.name !== undefined) data.name = fields.name;
    // Step 2 ("Email + mobile") is lead and contact data. It is stored apart
    // from the login identifiers so typing someone else's number can never
    // block — or claim — that person's account merge (resolveUser).
    // A field that is already the sign-in identifier is shown pre-filled and
    // read-only; anything sent for it is ignored rather than stored unseen.
    if (fields.email !== undefined && req.user.email === null) data.contactEmail = normalizeEmail(fields.email);
    if (fields.phone !== undefined && req.user.phone === null) {
      const phone = normalizePhone(fields.phone);
      if (!phone) return reply.code(400).send({ code: 'INVALID_PHONE', message: 'Enter a valid mobile number' });
      data.contactPhone = phone;
    }
    if (fields.healthGoal !== undefined) data.healthGoal = fields.healthGoal;
    if (fields.concern !== undefined) data.concern = fields.concern;

    const merged = { ...req.user, ...data } as User;
    data.profileCompletion = computeCompletion(merged);
    if (step === 4) data.onboardingCompleted = true;

    const user = await prisma.user.update({ where: { id: req.user.id }, data });
    return { profile: toProfileDto(user) };
  });

  /** Skipping everything still lands on Home with a generic feed. Never blocked. */
  app.post('/onboarding/skip', { preHandler: app.requireAuth }, async (req) => {
    const user = await prisma.user.update({
      where: { id: req.user.id },
      data: { onboardingCompleted: true },
    });
    return { profile: toProfileDto(user) };
  });

  app.patch('/profile', { preHandler: app.requireAuth }, async (req, reply) => {
    const parsed = profileSchema.safeParse(req.body);
    if (!parsed.success) {
      return reply.code(400).send({ code: 'INVALID_BODY', message: 'Invalid profile payload' });
    }
    const { dob, email, phone, ...rest } = parsed.data;
    const contact: Partial<User> = {};
    // The sign-in email/phone belong to the identity provider: an edit here
    // would only write a contact field the profile never shows. Re-sending
    // the same value is fine; changing it is refused with a reason.
    const loginLocked = (field: 'email' | 'phone') =>
      reply.code(409).send({
        code: 'LOGIN_IDENTIFIER',
        message: `This is the ${field === 'email' ? 'email' : 'mobile number'} you sign in with, so it can’t be changed here.`,
      });
    if (email !== undefined) {
      const normalized = normalizeEmail(email);
      if (req.user.email !== null) {
        if (normalized !== req.user.email) return loginLocked('email');
      } else contact.contactEmail = normalized;
    }
    if (phone !== undefined) {
      const normalized = normalizePhone(phone);
      if (!normalized) return reply.code(400).send({ code: 'INVALID_PHONE', message: 'Enter a valid mobile number' });
      if (req.user.phone !== null) {
        if (normalized !== req.user.phone) return loginLocked('phone');
      } else contact.contactPhone = normalized;
    }
    const merged = { ...req.user, ...rest, ...contact, dob: dob ? new Date(dob) : req.user.dob };
    const user = await prisma.user.update({
      where: { id: req.user.id },
      data: {
        ...rest,
        ...contact,
        ...(dob ? { dob: new Date(dob) } : {}),
        profileCompletion: computeCompletion(merged),
      },
    });
    return { profile: toProfileDto(user) };
  });

  /** Home. The entire layout is decided server-side — see services/feed.ts. */
  app.get('/home', { preHandler: app.requireAuth }, async (req) => {
    const now = new Date();
    const { entitlements, persona } = await resolveEntitlements(req.user.id, now);
    return buildHomeFeed(req.user, entitlements, persona, now);
  });

  /**
   * Account deletion — Google Play requirement and DPDP right to erasure.
   *
   * Two stores hold this person's data and BOTH must be erased:
   *   - our database (cascades remove attendance, runs, registrations, devices)
   *   - their Firebase Auth record (email / phone / uid)
   * Deleting only our row would leave the login alive, and the next request
   * would silently recreate an empty account — defeating the deletion.
   */
  app.delete('/account', { preHandler: app.requireAuth }, async (req) => {
    const uid = req.user.firebaseUid;
    await prisma.user.delete({ where: { id: req.user.id } });

    // Persona and imported-legacy users have no real provider record.
    const isRealUser = !uid.includes('|') && !uid.startsWith(LEGACY_UID_PREFIX) && !uid.startsWith('qa_');
    let authRecordDeleted = false;
    if (isRealUser) {
      try {
        authRecordDeleted = await identityProvider.deleteAccount(uid);
      } catch (err) {
        // Our data is already gone. Log loudly so support can finish the job.
        req.log.error({ err: String(err), uid }, 'account deleted but the identity record was not');
      }
    }
    return { deleted: true, authRecordDeleted };
  });
};

export default routes;
