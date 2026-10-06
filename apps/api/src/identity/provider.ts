/**
 * THE server-side identity seam.
 *
 * Firebase Auth is a placeholder: Times Internet will swap in its own SSO.
 * Everything outside src/identity/ consumes this interface — auth.ts verifies
 * tokens through it and account deletion erases the provider record through
 * it. Swapping providers means implementing this interface once and setting
 * AUTH_PROVIDER; resolveUser, routes and the client contract do not change.
 *
 * NOT part of this seam: FCM push. Messaging needs the Firebase APP
 * credentials regardless of who verifies logins, so services/push.ts depends
 * on identity/firebase.ts's app bootstrap directly, never on the provider.
 */

/** What a provider must prove about the caller. Provider-neutral. */
export interface VerifiedIdentity {
  /** The provider's stable subject. Stored in User.firebaseUid (see docs/06). */
  uid: string;
  email: string | null;
  /**
   * True only when the provider has verified the caller OWNS the email. An
   * unverified email must never be used to claim an existing account — anyone
   * can sign up with someone else's address.
   */
  emailVerified: boolean;
  phone: string | null;
  name: string | null;
}

/**
 * A DEFINITIVE "this token is no good" (expired, revoked, malformed, disabled
 * user). auth.ts maps it to 401, which signs the app out. Anything else a
 * provider throws — a network blip, an outage, a quota spike — becomes 503
 * ("try again"), so an outage never signs every active user out.
 */
export class TokenRejected extends Error {}

export interface IdentityProvider {
  readonly name: string;
  /** True when credentials/config are present. Cheap and idempotent. */
  configured(): boolean;
  /**
   * Verifies a bearer token and returns who it belongs to. Must throw on any
   * invalid, expired or revoked token — the caller maps every throw to 401.
   */
  verify(token: string): Promise<VerifiedIdentity>;
  /**
   * Erases the account at the provider (DELETE /account). Returns false when
   * the provider holds no erasable record (unconfigured, or N/A for SSO where
   * the identity belongs to the company directory, not to this app).
   */
  deleteAccount(uid: string): Promise<boolean>;
}
