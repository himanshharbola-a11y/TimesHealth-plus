import { Platform } from 'react-native';
import * as Notifications from 'expo-notifications';
import * as SecureStore from 'expo-secure-store';
import { api } from '@/api/client';
import { firebaseEnabled } from '@/lib/firebaseAuth';
import { isAppRoute } from '@/lib/links';

/**
 * Push notifications — PRD §11.
 *
 * Dual-channel in V1: WhatsApp continues, push is added, both fire. So push is
 * an enhancement, never the critical path, and declining it must not break
 * anything — which is why every function here swallows its own failures.
 *
 * Permission is never requested cold. The app shows its own priming screen
 * first (NotificationPrimer); only a "yes" there triggers the OS prompt. On
 * Android 13+ and iOS a denial at the OS prompt is close to permanent, so the
 * one real ask has to be the one the user already agreed to.
 */

const PRIMED_KEY = 'th_push_primed_v1';
const TOKEN_KEY = 'th_push_token_v1';
const CHANNEL_ID = 'reminders';

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
  }),
});

/** True once we have shown our own priming screen, whatever the answer. */
export async function hasPrimed(): Promise<boolean> {
  try {
    return (await SecureStore.getItemAsync(PRIMED_KEY)) === '1';
  } catch {
    return false;
  }
}

export async function markPrimed(): Promise<void> {
  try {
    await SecureStore.setItemAsync(PRIMED_KEY, '1');
  } catch {
    // Non-fatal: worst case we show the primer once more.
  }
}

export type PushOutcome = 'granted' | 'denied' | 'unavailable';

async function ensureChannel(): Promise<void> {
  if (Platform.OS !== 'android') return;
  // Android 8+ shows nothing without a channel. The API sends to this id.
  await Notifications.setNotificationChannelAsync(CHANNEL_ID, {
    name: 'Class & race reminders',
    importance: Notifications.AndroidImportance.HIGH,
    lightColor: '#E8533A',
  });
}

/** Asks the OS (after our own primer said yes), then registers this device. */
export async function requestAndRegister(): Promise<PushOutcome> {
  try {
    await ensureChannel();
    const { status } = await Notifications.requestPermissionsAsync();
    if (status !== 'granted') return 'denied';
    return (await registerDevice()) ? 'granted' : 'unavailable';
  } catch {
    return 'unavailable';
  }
}

/**
 * Re-registers silently on every launch when permission is already granted.
 * The API upserts on the token, so if a different person has signed in on
 * this phone the device moves to their account — otherwise they would receive
 * the previous user's race-day alerts.
 */
export async function syncPushRegistration(): Promise<void> {
  try {
    const { status } = await Notifications.getPermissionsAsync();
    if (status !== 'granted') return;
    await ensureChannel();
    await registerDevice();
  } catch {
    // Push is an enhancement; never surface this.
  }
}

/** Called on sign-out so a shared phone stops receiving this user's alerts. */
export async function unregisterDevice(): Promise<void> {
  try {
    const token = await SecureStore.getItemAsync(TOKEN_KEY);
    if (!token) return;
    await api.post('/devices/push-token/remove', { token });
    await SecureStore.deleteItemAsync(TOKEN_KEY);
    resetPushRegistration();
  } catch {
    // Best effort: the server also prunes tokens FCM reports as dead.
  }
}

/**
 * The token already registered for the CURRENT signed-in user this session,
 * and one being registered right now. Together they make registration a no-op
 * when nothing has changed — the second line of defence against the loop
 * described in watchTokenRotation().
 */
let registeredToken: string | null = null;
let pendingToken: string | null = null;

/** Called whenever the signed-in identity changes (see store/session.ts). */
export function resetPushRegistration(): void {
  registeredToken = null;
  pendingToken = null;
}

async function registerToken(value: string, provider: 'EXPO' | 'FCM'): Promise<boolean> {
  if (value === registeredToken || value === pendingToken) return true;
  pendingToken = value;
  try {
    await api.post('/devices/push-token', {
      token: value,
      platform: Platform.OS === 'ios' ? 'IOS' : 'ANDROID',
      provider,
    });
    registeredToken = value;
  } finally {
    pendingToken = null;
  }
  try {
    await SecureStore.setItemAsync(TOKEN_KEY, value);
  } catch {
    // Only needed for unregistering on sign-out.
  }
  return true;
}

async function registerDevice(): Promise<boolean> {
  const token = await getToken();
  if (!token) return false;
  return registerToken(token.value, token.provider);
}

async function getToken(): Promise<{ value: string; provider: 'EXPO' | 'FCM' } | null> {
  // The API delivers through FCM only (apps/api services/push.ts) — push
  // stays on Firebase even once sign-in moves to Times SSO. A build without
  // Firebase therefore has no token worth registering: report push as
  // unavailable rather than register an Expo token nothing ever sends to.
  if (!firebaseEnabled) return null;
  try {
    const t = await Notifications.getDevicePushTokenAsync();
    return { value: String(t.data), provider: 'FCM' };
  } catch {
    return null;
  }
}

/**
 * FCM rotates tokens occasionally; keep the server's copy current.
 *
 * The listener is HANDED the new token and must register exactly that. It must
 * never fetch the token itself: getDevicePushTokenAsync() fires this same
 * listener, so "on change → fetch → register" re-triggered itself forever.
 * On a test device that was ~44 requests a second until the API's rate limiter
 * cut the user off — breaking every other screen with it.
 */
export function watchTokenRotation(): () => void {
  // Without Firebase the event carries a device token, not the Expo token we
  // register — so there is nothing correct to do with it. Don't listen.
  if (!firebaseEnabled) return () => {};
  const sub = Notifications.addPushTokenListener((token) => {
    void registerToken(String(token.data), 'FCM').catch(() => {});
  });
  return () => sub.remove();
}

/**
 * Tapping a notification opens the screen it is about. The API puts the
 * route in the message's data payload (services/push.ts).
 */
const handledTaps = new Set<string>();

export function watchNotificationTaps(navigate: (route: string) => void): () => void {
  const open = (response: Notifications.NotificationResponse | null) => {
    if (!response) return;
    // getLastNotificationResponseAsync replays the same tap on every mount;
    // without this the app would keep reopening an old notification's screen.
    const id = response.notification.request.identifier;
    if (handledTaps.has(id)) return;
    handledTaps.add(id);
    const route = response.notification.request.content.data?.route;
    if (isAppRoute(route)) navigate(route);
  };
  // A tap that cold-started the app arrives before any listener is attached.
  void Notifications.getLastNotificationResponseAsync().then(open);
  const sub = Notifications.addNotificationResponseReceivedListener(open);
  return () => sub.remove();
}
