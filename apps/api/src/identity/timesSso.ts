import type { IdentityProvider } from './provider.js';

/**
 * Times Internet SSO — NOT IMPLEMENTED YET. This stub exists so the swap has
 * an exact shape and fails loudly, never silently.
 *
 * To implement, the Times SSO team must supply (tracked in docs/06):
 *   1. How to verify a token server-side: a JWKS URL (we verify the JWT
 *      locally) or an introspection endpoint (we call per request + cache).
 *   2. Claim names for: stable user id (sub?), email, phone, display name —
 *      and whether email/phone arrive verified.
 *   3. Revocation semantics: do tokens die on SSO logout, and how fast must
 *      this API notice? (Firebase today: checkRevoked on every request.)
 *   4. Whether in-app account deletion must call an SSO endpoint, or SSO
 *      identities outlive the app (then deleteAccount correctly returns false
 *      and we erase only our own data).
 *   5. Client-side: the login SDK / OAuth redirect for the RN app — it slots
 *      in behind apps/mobile/src/lib/identity.ts.
 *   6. Test accounts for every login method.
 *
 * Migration note: User.firebaseUid stores the ACTIVE provider's subject. SSO
 * subjects must not collide with Firebase uids (28-char alphanumeric) or the
 * reserved prefixes 'legacy:' and 'qa_', and never contain '|' or be a
 * three-segment dot string that another provider could mistake for its own
 * token format. If collision cannot be ruled out, prefix new subjects
 * ('times:<id>') and migrate existing rows in one script.
 */
export const timesSsoProvider: IdentityProvider = {
  name: 'times-sso',
  configured: () => false,
  verify() {
    return Promise.reject(
      new Error('AUTH_PROVIDER=times-sso is selected but not implemented — see docs/06 and src/identity/timesSso.ts'),
    );
  },
  deleteAccount() {
    return Promise.resolve(false);
  },
};
