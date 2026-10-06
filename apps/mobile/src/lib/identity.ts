import {
  AuthError,
  type PhoneSession,
  firebaseEnabled,
  firebaseSignOut,
  getFirebaseIdToken,
  googleEnabled as firebaseGoogleEnabled,
  onFirebaseUserChanged,
  sendPasswordReset as firebaseSendPasswordReset,
  signInWithEmail as firebaseSignInWithEmail,
  signInWithGoogle as firebaseSignInWithGoogle,
  startPhoneSignIn as firebaseStartPhoneSignIn,
  toIndianE164,
} from './firebaseAuth';

/**
 * THE identity seam.
 *
 * Firebase Auth is a placeholder: Times Internet will swap in its own SSO.
 * Everything outside this file talks "identity", never "Firebase" — so the
 * swap means rewriting the implementations below (and apps/api/src/auth.ts's
 * provider on the server) and NOTHING else.
 *
 * Deliberately NOT behind this seam: push messaging. FCM needs the Firebase
 * APP configured (google-services.json) and survives an auth-provider swap —
 * src/lib/notifications.ts keeps its own `firebaseEnabled` import for that.
 *
 * What the swap needs from the Times SSO team is listed in docs/06.
 */

export { AuthError, toIndianE164 };
export type { PhoneSession };

/** The one fact screens may know about the signed-in person. */
export interface IdentityUser {
  uid: string;
}

/** True when a real identity provider is configured (else dev personas only). */
export const identityConfigured = firebaseEnabled;
export const googleEnabled = firebaseGoogleEnabled;

export const signInWithGoogle = firebaseSignInWithGoogle;
export const signInWithEmail = firebaseSignInWithEmail;
export const sendPasswordReset = firebaseSendPasswordReset;
export const startPhoneSignIn = firebaseStartPhoneSignIn;

/** Bearer token for the API; null when signed out. */
export const getIdToken = getFirebaseIdToken;

/** Fires with the current user immediately, then on every sign-in/out. */
export function onIdentityChanged(callback: (user: IdentityUser | null) => void): () => void {
  return onFirebaseUserChanged((user) => callback(user ? { uid: user.uid } : null));
}

export const identitySignOut = firebaseSignOut;
