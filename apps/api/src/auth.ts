import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import fp from 'fastify-plugin';
import type { User } from '@prisma/client';
import { prisma } from './db.js';
import { env, isProd } from './env.js';
import { identityProvider, type VerifiedIdentity } from './identity/index.js';
import { TokenRejected } from './identity/provider.js';
import { normalizeEmail, normalizePhone } from './identity/normalize.js';

declare module 'fastify' {
  interface FastifyRequest {
    user: User;
  }
}

/**
 * Placeholder UID prefix for customers imported from the existing yoga and
 * marathon systems who have never signed in. Only records carrying it may be
 * adopted by a first app login — see resolveUser().
 */
export const LEGACY_UID_PREFIX = 'legacy:';

/**
 * QA persona tokens look like "uid|email|phone". A real provider token is a
 * JWT — three base64 segments separated by dots, and never contains "|". So
 * the two can be told apart without trying one and falling back to the other.
 */
function isPersonaToken(token: string): boolean {
  return token.includes('|') && token.split('.').length !== 3;
}

/**
 * Persona identities are confined to a QA namespace: a "qa_" uid, an email on
 * the reserved .test domain, a number in the QA range. Anyone can mint a
 * persona token, and resolveUser adopts an account by verified email/phone —
 * so without this, a QA server would let a stranger sign in as any real user
 * just by putting that user's email or number in the token.
 */
const PERSONA_UID = /^qa_[\w-]{1,80}$/;
const PERSONA_EMAIL = /^[\w.+-]{1,64}@th\.test$/i;
const PERSONA_PHONE = /^\+9190000000\d{2}$/;

async function verifyToken(token: string): Promise<VerifiedIdentity> {
  if (isPersonaToken(token)) {
    // Allowed when explicitly enabled, or in pure local dev with no real
    // provider configured — but never on a server exposed through a public
    // tunnel without that explicit opt-in (scripts/serve.mjs --qa). All of it
    // is refused in production by env.ts; this check is the second line.
    const allowed =
      !isProd && (env.allowDevTokens || (!identityProvider.configured() && !env.publicTunnel));
    if (!allowed) throw new TokenRejected('Persona tokens are not accepted on this server');
    const [uid = '', email = '', phone = ''] = token.split('|');
    if (!PERSONA_UID.test(uid)) throw new TokenRejected('Persona token must be "qa_uid|email|phone"');
    if ((email && !PERSONA_EMAIL.test(email)) || (phone && !PERSONA_PHONE.test(phone))) {
      throw new TokenRejected('Persona tokens are limited to QA identities');
    }
    return { uid, email: email || null, emailVerified: true, phone: phone || null, name: null };
  }

  // Firebase today, Times SSO tomorrow — src/identity/ is the seam.
  return identityProvider.verify(token);
}

/**
 * Resolves a verified identity to exactly one User row.
 *
 * PRD §5: "Existing web user logs in with a different identifier (phone on app,
 * email on web) must resolve to one account."
 *
 * Order matters. We match on the provider UID first because that is the only
 * identifier we issued ourselves. Email and phone are claims about the world
 * and can collide, so they are only used to *adopt* an account that has no UID
 * yet — never to take over one that already belongs to another UID.
 */
async function resolveUser(
  identity: VerifiedIdentity,
  log: FastifyRequest['log'],
): Promise<User> {
  const byUid = await prisma.user.findUnique({
    where: { firebaseUid: identity.uid },
  });
  if (byUid) return byUid;

  // Normalised so "Ravi@Gmail.com" / "9876543210" match "ravi@gmail.com" /
  // "+919876543210". An UNVERIFIED email is never used to match: anyone can
  // sign up with someone else's address, and adopting on it would hand them
  // that person's imported subscription, race and bib.
  const email = normalizeEmail(identity.email);
  const matchEmail = identity.emailVerified ? email : null;
  const phone = normalizePhone(identity.phone);

  const matchers = [
    matchEmail ? { email: matchEmail } : null,
    phone ? { phone } : null,
  ].filter((m): m is NonNullable<typeof m> => m !== null);

  let conflict: { matchedUserIds: string[]; reason: string } | null = null;

  if (matchers.length > 0) {
    const candidates = await prisma.user.findMany({
      where: { OR: matchers },
      take: 5,
    });

    // Imported records (existing yoga/marathon customers who have never opened
    // the app) carry a placeholder UID. Only those may be adopted.
    const unclaimed = candidates.filter((c) => c.firebaseUid.startsWith(LEGACY_UID_PREFIX));
    const claimed = candidates.filter((c) => !c.firebaseUid.startsWith(LEGACY_UID_PREFIX));

    // Exactly one unclaimed match and nothing already claimed → link it.
    // This is the migration path that keeps a web subscriber's streak intact.
    const target = unclaimed[0];
    if (unclaimed.length === 1 && claimed.length === 0 && target) {
      return prisma.user.update({
        where: { id: target.id },
        data: {
          firebaseUid: identity.uid,
          email: target.email ?? matchEmail,
          phone: target.phone ?? phone,
          name: target.name ?? identity.name,
          // A known (imported) customer never sees onboarding (§5).
          onboardingCompleted: true,
        },
      });
    }

    // Ambiguous — several records match, or one already belongs to a different
    // login. Do NOT guess: creating a fresh account is recoverable, merging the
    // wrong two accounts is not. But never SILENTLY — the clash is recorded
    // below so support can find and merge it.
    if (candidates.length > 0) {
      conflict = {
        matchedUserIds: candidates.map((c) => c.id),
        reason: unclaimed.length > 1 ? 'MULTIPLE_UNCLAIMED' : 'ALREADY_CLAIMED',
      };
    }
  }

  const created = await prisma.user.create({
    data: {
      firebaseUid: identity.uid,
      // Login identifiers only when the provider vouches for them; an
      // unverified email is kept as contact data, never as an identity.
      email: matchEmail,
      contactEmail: identity.emailVerified ? null : email,
      phone,
      name: identity.name,
    },
  });

  if (conflict) {
    await prisma.identityConflict.create({
      data: { newUserId: created.id, matchedUserIds: conflict.matchedUserIds, reason: conflict.reason },
    });
    log.warn(
      { newUserId: created.id, matched: conflict.matchedUserIds.length, reason: conflict.reason },
      'identity collision — new account created, recorded for support merge',
    );
  }
  return created;
}

const authPlugin: FastifyPluginAsync = async (app) => {
  app.decorateRequest('user', null as unknown as User);

  app.decorate(
    'requireAuth',
    async (req: FastifyRequest): Promise<void> => {
      const header = req.headers.authorization;
      if (!header?.startsWith('Bearer ')) {
        const err = new Error('Missing bearer token') as Error & { statusCode: number };
        err.statusCode = 401;
        throw err;
      }
      // Two failure modes, and they must not be confused. A bad token is the
      // user's problem and earns a 401, which the app treats as "sign out". A
      // database or Firebase outage is OUR problem and earns a 503, which the
      // app treats as "try again". Collapsing both into 401 would sign every
      // active user out during a two-second database blip.
      let identity: VerifiedIdentity;
      try {
        identity = await verifyToken(header.slice(7));
      } catch (cause) {
        // Only a DEFINITIVE rejection signs the app out. A provider outage
        // (network, quota) is a 503 — the app retries and the user stays in.
        const definitive = cause instanceof TokenRejected;
        const err = new Error(
          definitive ? 'Invalid or expired token' : 'Sign-in service temporarily unavailable',
        ) as Error & { statusCode: number };
        err.statusCode = definitive ? 401 : 503;
        if (definitive) req.log.warn({ cause: String(cause) }, 'token rejected');
        else req.log.error({ cause: String(cause) }, 'identity provider unavailable');
        throw err;
      }

      try {
        req.user = await resolveUser(identity, req.log);
      } catch (cause) {
        const err = new Error('Service temporarily unavailable') as Error & { statusCode: number };
        err.statusCode = 503;
        req.log.error({ cause: String(cause) }, 'user resolution failed');
        throw err;
      }
    },
  );
};

declare module 'fastify' {
  interface FastifyInstance {
    requireAuth: (req: FastifyRequest) => Promise<void>;
  }
}

export default fp(authPlugin, { name: 'auth' });
