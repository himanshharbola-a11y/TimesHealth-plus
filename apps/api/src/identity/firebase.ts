import { readFileSync } from 'node:fs';
import path from 'node:path';
import admin from 'firebase-admin';
import { env } from '../env.js';
import { TokenRejected, type IdentityProvider } from './provider.js';

/**
 * Firebase: today's identity provider AND the FCM push credential.
 *
 * Those are two different roles. When Times SSO replaces Firebase for login,
 * push stays on FCM — so the app bootstrap (initFirebaseApp) is exported for
 * services/push.ts and must keep working even when AUTH_PROVIDER != firebase.
 */

let initialised = false;

function loadServiceAccount(): admin.ServiceAccount | null {
  if (env.firebaseServiceAccountFile) {
    const file = path.resolve(process.cwd(), env.firebaseServiceAccountFile);
    return JSON.parse(readFileSync(file, 'utf8')) as admin.ServiceAccount;
  }
  if (env.firebaseServiceAccountB64) {
    return JSON.parse(
      Buffer.from(env.firebaseServiceAccountB64, 'base64').toString('utf8'),
    ) as admin.ServiceAccount;
  }
  return null;
}

/** Idempotent. Returns false when no service account is configured. */
export function initFirebaseApp(): boolean {
  if (initialised) return true;
  const account = loadServiceAccount();
  if (!account) return false;
  admin.initializeApp({ credential: admin.credential.cert(account) });
  initialised = true;
  return true;
}

/** firebase-admin codes that mean "this token is no good" — never an outage. */
const DEFINITIVE = new Set([
  'auth/id-token-expired',
  'auth/id-token-revoked',
  'auth/argument-error',
  'auth/invalid-id-token',
  'auth/user-disabled',
  'auth/user-not-found',
]);

/**
 * Revocation, checked per user at most every REVOCATION_TTL_MS instead of on
 * every request. `verifyIdToken(token, true)` makes a Firebase network call
 * per API request — at the 5:59 AM batch spike that alone costs the latency
 * budget, and an outage of that call used to sign everybody out. ID tokens
 * expire hourly anyway, so a few minutes of revocation lag is the right trade.
 */
const REVOCATION_TTL_MS = 5 * 60_000;
const revocation = new Map<string, { validAfterMs: number; disabled: boolean; checkedAt: number }>();

async function revocationState(uid: string): Promise<{ validAfterMs: number; disabled: boolean } | null> {
  const cached = revocation.get(uid);
  if (cached && Date.now() - cached.checkedAt < REVOCATION_TTL_MS) return cached;
  try {
    const user = await admin.auth().getUser(uid);
    const state = {
      validAfterMs: user.tokensValidAfterTime ? Date.parse(user.tokensValidAfterTime) : 0,
      disabled: user.disabled,
      checkedAt: Date.now(),
    };
    if (revocation.size > 50_000) revocation.clear(); // bounded memory
    revocation.set(uid, state);
    return state;
  } catch (err) {
    const code = (err as { code?: string }).code ?? '';
    if (code === 'auth/user-not-found') throw new TokenRejected('user not found');
    // Firebase unreachable: fall back to the last known state rather than
    // locking a valid user out (or, worse, signing them out).
    return cached ?? null;
  }
}

export const firebaseProvider: IdentityProvider = {
  name: 'firebase',

  configured: () => initFirebaseApp(),

  async verify(token) {
    if (!initFirebaseApp()) throw new Error('Firebase is not configured on this server');
    let decoded: admin.auth.DecodedIdToken;
    try {
      // Signature + expiry checked locally against Google's cached public keys.
      decoded = await admin.auth().verifyIdToken(token);
    } catch (err) {
      const code = (err as { code?: string }).code ?? '';
      if (DEFINITIVE.has(code)) throw new TokenRejected(code);
      throw err; // network / internal → 503, not a sign-out
    }

    const state = await revocationState(decoded.uid);
    if (state?.disabled) throw new TokenRejected('auth/user-disabled');
    // Tokens issued before "sign out everywhere" / a support revocation die.
    if (state && decoded.auth_time * 1000 < state.validAfterMs) {
      throw new TokenRejected('auth/id-token-revoked');
    }

    return {
      uid: decoded.uid,
      email: decoded.email ?? null,
      emailVerified: decoded.email_verified === true,
      phone: decoded.phone_number ?? null,
      name: (decoded.name as string | undefined) ?? null,
    };
  },

  async deleteAccount(uid) {
    if (!initFirebaseApp()) return false;
    await admin.auth().deleteUser(uid);
    revocation.delete(uid);
    return true;
  },
};
