import Constants from 'expo-constants';
import type { ConfirmationResult, User } from '@react-native-firebase/auth';

/**
 * Real sign-in — PRD §5: Google / Email / Phone.
 *
 * Firebase is only present in builds made with google-services.json (see
 * app.config.js). Every function here checks that first and loads the native
 * modules lazily, so a build without Firebase can never crash by calling in.
 *
 * The API never sees a password or an OTP. Firebase verifies the user and
 * issues a short-lived ID token; the app sends only that token, and the server
 * verifies it with the Firebase Admin SDK (apps/api/src/auth.ts).
 */

const extra = (Constants.expoConfig?.extra ?? {}) as {
  firebaseEnabled?: boolean;
  googleWebClientId?: string | null;
};

export const firebaseEnabled = extra.firebaseEnabled === true;
export const googleEnabled = firebaseEnabled && Boolean(extra.googleWebClientId);

type AuthModule = typeof import('@react-native-firebase/auth');
type GoogleModule = typeof import('@react-native-google-signin/google-signin');

function authModule(): AuthModule {
  if (!firebaseEnabled) throw new AuthError('Sign-in is not available in this build.');
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  return require('@react-native-firebase/auth') as AuthModule;
}

let googleConfigured = false;
function googleModule(): GoogleModule {
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const mod = require('@react-native-google-signin/google-signin') as GoogleModule;
  if (!googleConfigured && extra.googleWebClientId) {
    mod.GoogleSignin.configure({ webClientId: extra.googleWebClientId });
    googleConfigured = true;
  }
  return mod;
}

/** An error whose message is already fit to show the user. */
export class AuthError extends Error {
  constructor(message: string, readonly cancelled = false) {
    super(message);
    this.name = 'AuthError';
  }
}

/**
 * Firebase and Google Sign-In error codes, translated into something a person
 * can act on. Anything unrecognised falls back to a generic line rather than
 * leaking an SDK message like "auth/internal-error".
 */
const MESSAGES: Record<string, string> = {
  'auth/invalid-email': 'That email address doesn’t look right.',
  'auth/invalid-credential': 'Email or password is incorrect.',
  'auth/wrong-password': 'Email or password is incorrect.',
  'auth/user-not-found': 'No account with that email. Create one instead?',
  'auth/email-already-in-use': 'An account with this email already exists. Sign in instead.',
  'auth/weak-password': 'Choose a password of at least 6 characters.',
  'auth/too-many-requests': 'Too many attempts. Wait a few minutes and try again.',
  'auth/network-request-failed': 'No connection. Check your network and try again.',
  'auth/invalid-phone-number': 'Enter a valid 10-digit mobile number.',
  'auth/invalid-verification-code': 'That code is incorrect. Check the SMS and try again.',
  'auth/code-expired': 'That code has expired. Request a new one.',
  'auth/session-expired': 'That code has expired. Request a new one.',
  // Spark plan: real SMS is not sent until the project is on Blaze.
  'auth/quota-exceeded': 'SMS sign-in is unavailable right now. Use Google or email instead.',
  'auth/billing-not-enabled': 'SMS sign-in is unavailable right now. Use Google or email instead.',
  'auth/user-disabled': 'This account has been disabled. Contact support.',
};

function toAuthError(err: unknown): AuthError {
  if (err instanceof AuthError) return err;
  const code = (err as { code?: string | number })?.code;
  if (typeof code === 'string' && MESSAGES[code]) return new AuthError(MESSAGES[code]);
  return new AuthError('Sign-in failed. Please try again.');
}

// ── Google ──────────────────────────────────────────────────────────────────

export async function signInWithGoogle(): Promise<void> {
  if (!googleEnabled) throw new AuthError('Google sign-in is not available in this build.');
  const { GoogleSignin, isSuccessResponse, isErrorWithCode, statusCodes } = googleModule();
  const auth = authModule();

  try {
    await GoogleSignin.hasPlayServices({ showPlayServicesUpdateDialog: true });
    const result = await GoogleSignin.signIn();
    if (!isSuccessResponse(result)) throw new AuthError('Sign-in cancelled.', true);

    const idToken = result.data.idToken;
    if (!idToken) throw new AuthError('Google did not return an ID token. Please try again.');

    const credential = auth.GoogleAuthProvider.credential(idToken);
    await auth.signInWithCredential(auth.getAuth(), credential);
  } catch (err) {
    if (isErrorWithCode(err)) {
      if (err.code === statusCodes.SIGN_IN_CANCELLED) throw new AuthError('Sign-in cancelled.', true);
      if (err.code === statusCodes.IN_PROGRESS) throw new AuthError('Sign-in already in progress.');
      if (err.code === statusCodes.PLAY_SERVICES_NOT_AVAILABLE) {
        throw new AuthError('Google Play Services is needed for Google sign-in.');
      }
      // DEVELOPER_ERROR (10): this build's signing fingerprint is not
      // registered in Firebase. A setup fault, not the user's — say so plainly.
      if (String(err.code) === '10' || err.code === 'DEVELOPER_ERROR') {
        throw new AuthError('Google sign-in isn’t set up for this build yet. Use email or phone.');
      }
    }
    throw toAuthError(err);
  }
}

// ── Email ───────────────────────────────────────────────────────────────────

export async function signInWithEmail(
  email: string,
  password: string,
  mode: 'signIn' | 'signUp',
): Promise<void> {
  const auth = authModule();
  try {
    const instance = auth.getAuth();
    if (mode === 'signUp') {
      await auth.createUserWithEmailAndPassword(instance, email.trim(), password);
    } else {
      await auth.signInWithEmailAndPassword(instance, email.trim(), password);
    }
  } catch (err) {
    throw toAuthError(err);
  }
}

export async function sendPasswordReset(email: string): Promise<void> {
  const auth = authModule();
  try {
    await auth.sendPasswordResetEmail(auth.getAuth(), email.trim());
  } catch (err) {
    throw toAuthError(err);
  }
}

// ── Phone OTP ───────────────────────────────────────────────────────────────

export interface PhoneSession {
  confirm: (code: string) => Promise<void>;
}

/** India-only, matching the SMS region policy recommended in docs/04. */
export function toIndianE164(input: string): string | null {
  const digits = input.replace(/\D/g, '');
  // "+91 98765 43210", "919876543210" and "09876543210" are all the same
  // mobile — the server accepts each, so the app must too.
  const local =
    digits.length === 12 && digits.startsWith('91')
      ? digits.slice(2)
      : digits.length === 11 && digits.startsWith('0')
        ? digits.slice(1)
        : digits;
  return /^[6-9]\d{9}$/.test(local) ? `+91${local}` : null;
}

export async function startPhoneSignIn(phoneE164: string): Promise<PhoneSession> {
  const auth = authModule();
  let confirmation: ConfirmationResult;
  try {
    confirmation = await auth.signInWithPhoneNumber(auth.getAuth(), phoneE164);
  } catch (err) {
    throw toAuthError(err);
  }
  return {
    confirm: async (code: string) => {
      try {
        await confirmation.confirm(code.trim());
      } catch (err) {
        throw toAuthError(err);
      }
    },
  };
}

// ── Session ─────────────────────────────────────────────────────────────────

/**
 * The current user's ID token. Firebase caches it and refreshes it shortly
 * before its one-hour expiry, so calling this per request is cheap.
 */
export async function getFirebaseIdToken(): Promise<string | null> {
  if (!firebaseEnabled) return null;
  const auth = authModule();
  const user = auth.getAuth().currentUser;
  return user ? auth.getIdToken(user) : null;
}

export function onFirebaseUserChanged(callback: (user: User | null) => void): () => void {
  if (!firebaseEnabled) {
    callback(null);
    return () => {};
  }
  const auth = authModule();
  return auth.onAuthStateChanged(auth.getAuth(), callback);
}

export async function firebaseSignOut(): Promise<void> {
  if (!firebaseEnabled) return;
  try {
    await authModule().signOut(authModule().getAuth());
    if (googleEnabled) await googleModule().GoogleSignin.signOut();
  } catch {
    // Signing out must always succeed from the user's point of view.
  }
}
