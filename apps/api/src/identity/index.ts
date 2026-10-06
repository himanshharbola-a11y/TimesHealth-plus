import { env } from '../env.js';
import { firebaseProvider } from './firebase.js';
import { timesSsoProvider } from './timesSso.js';
import type { IdentityProvider } from './provider.js';

export type { IdentityProvider, VerifiedIdentity } from './provider.js';

/** Chosen once at boot by AUTH_PROVIDER (default: firebase). */
export const identityProvider: IdentityProvider =
  env.authProvider === 'times-sso' ? timesSsoProvider : firebaseProvider;

/**
 * Called once at boot so a broken key fails loudly at startup, not on the
 * first login. 'dev-only' means no provider is configured and only QA persona
 * tokens work — env.ts refuses that combination in production.
 */
export function assertIdentityReady(): string {
  return identityProvider.configured() ? identityProvider.name : 'dev-only';
}
