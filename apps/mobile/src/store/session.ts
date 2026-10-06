import { create } from 'zustand';
import { getPersonaToken, setToken, setUnauthorizedHandler } from '@/api/client';
import { queryClient } from '@/api/queryClient';
import { identitySignOut, onIdentityChanged } from '@/lib/identity';
import { resetInboxSeen } from '@/lib/inboxSeen';
import { resetPushRegistration, unregisterDevice } from '@/lib/notifications';
import { clearOfflinePasses } from '@/lib/offlinePass';
import { forgetPendingOrders } from '@/lib/pendingOrders';
import { suspendTrackingForSignOut } from '@/lib/runTracker';

/**
 * Auth state.
 *
 * Signed in means either a real Firebase user exists (Firebase persists that
 * session across launches on its own) or a QA persona has been chosen in a
 * test build. Nothing else in the app touches tokens — src/api/client.ts picks
 * the right credential per request.
 */

interface SessionState {
  status: 'loading' | 'signed-in' | 'signed-out';
  /** How the user signed in — drives which sign-out steps run. */
  method: 'identity' | 'persona' | null;
  restore: () => Promise<void>;
  signInPersona: (token: string) => Promise<void>;
  /**
   * `accountDeleted`: the server has already erased the account (including
   * its device registrations), so skip calls that would need it to exist.
   */
  signOut: (opts?: { accountDeleted?: boolean }) => Promise<void>;
}

let unsubscribeIdentity: (() => void) | null = null;
let lastUid: string | null | undefined;

/** Drop every cached response. Called whenever the signed-in identity changes. */
function forgetCachedData(): void {
  queryClient.cancelQueries();
  queryClient.clear();
  // The next user must re-register this device under their own account.
  resetPushRegistration();
  // …and must never see the previous user's race pass.
  void clearOfflinePasses().catch(() => undefined);
  // …nor have GPS keep tracking a run for someone who has left. Their runs
  // stay parked on the phone under their name and sync when they return.
  void suspendTrackingForSignOut().catch(() => undefined);
  // …nor find their notifications already marked read by the last person.
  void resetInboxSeen().catch(() => undefined);
  // …nor resume their half-finished checkout.
  forgetPendingOrders();
}

export const useSessionStore = create<SessionState>((set, get) => ({
  status: 'loading',
  method: null,

  restore: async () => {
    if (await getPersonaToken()) {
      set({ status: 'signed-in', method: 'persona' });
    }
    // Follow Firebase for the lifetime of the app: sign-in on the login screen,
    // token revocation and sign-out elsewhere all flow through here.
    unsubscribeIdentity?.();
    unsubscribeIdentity = onIdentityChanged((user) => {
      const uid = user?.uid ?? null;
      if (lastUid !== undefined && uid !== lastUid) forgetCachedData();
      lastUid = uid;
      if (get().method === 'persona') return;
      set(user ? { status: 'signed-in', method: 'identity' } : { status: 'signed-out', method: null });
    });
  },

  signInPersona: async (token: string) => {
    forgetCachedData();
    await setToken(token);
    set({ status: 'signed-in', method: 'persona' });
  },

  signOut: async (opts) => {
    // While still authenticated: stop this phone receiving this user's alerts.
    if (!opts?.accountDeleted) await unregisterDevice();
    await setToken(null);
    await identitySignOut();
    forgetCachedData();
    set({ status: 'signed-out', method: null });
  },
}));

// A rejected token anywhere in the app drops the user back to login.
setUnauthorizedHandler(() => {
  forgetCachedData();
  useSessionStore.setState({ status: 'signed-out', method: null });
});

/**
 * QA personas, mirroring apps/api/prisma/personas.ts. Shown on the login screen
 * only in dev builds and test APKs (EXPO_PUBLIC_DEV_SIGNIN=1), and accepted only
 * by a server running with ALLOW_DEV_TOKENS=true.
 */
export const DEV_PERSONAS = [
  { label: 'Free user', token: 'qa_free|free@th.test|+919000000001' },
  { label: 'Yoga subscriber', token: 'qa_yoga|yoga@th.test|+919000000002' },
  { label: 'Marathon registrant', token: 'qa_marathon|marathon@th.test|+919000000003' },
  { label: 'Yoga + Marathon', token: 'qa_both|both@th.test|+919000000004' },
  { label: 'Expired subscriber', token: 'qa_expired|expired@th.test|+919000000005' },
  { label: 'Race finisher', token: 'qa_finisher|finisher@th.test|+919000000006' },
] as const;

export const devSignInEnabled = __DEV__ || process.env.EXPO_PUBLIC_DEV_SIGNIN === '1';
