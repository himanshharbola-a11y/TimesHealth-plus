import { Alert, Linking } from 'react-native';

/**
 * External links in one place.
 * TODO(launch): replace with the real support number and hosted policy page.
 */
export const SUPPORT_WHATSAPP_URL = 'https://wa.me/910000000000';
export const PRIVACY_URL = 'https://timeshealthplus.invalid/privacy';

/**
 * Schemes the app will hand to the OS. Server-supplied URLs (articles, class
 * and workshop join links, certificates, photos) only ever need https; the
 * others are the app's own fixed links (WhatsApp share, Play Store).
 *
 * Anything else — intent:, file:, content:, javascript:, a custom scheme — is
 * refused, so a bad or compromised content row can't launch an arbitrary app
 * component on the phone. Plain http only in dev builds (local servers).
 */
const ALLOWED_SCHEMES = new Set(['https', 'whatsapp', 'market', 'tel', 'mailto', ...(__DEV__ ? ['http'] : [])]);

/** Parsed by hand: React Native's URL polyfill doesn't implement `protocol`. */
function schemeOf(url: string): string | null {
  const m = /^([a-z][a-z0-9+.-]*):/i.exec(url.trim());
  return m ? m[1]!.toLowerCase() : null;
}

export function isSafeExternalUrl(url: string | null | undefined): url is string {
  if (!url) return false;
  const scheme = schemeOf(url);
  return scheme !== null && ALLOWED_SCHEMES.has(scheme);
}

/**
 * Opens a link outside the app — never a dead tap: a refused or failed link
 * says so instead of silently doing nothing.
 */
export async function openExternal(url: string | null | undefined): Promise<void> {
  if (!isSafeExternalUrl(url)) {
    Alert.alert('Link unavailable', 'This link can’t be opened from the app.');
    return;
  }
  try {
    await Linking.openURL(url);
  } catch {
    Alert.alert('Couldn’t open the link', 'Please try again in a moment.');
  }
}

/**
 * In-app routes a push notification or inbox item may open. The route arrives
 * in a payload the app didn't write, so it's matched against the screens that
 * actually exist rather than pushed blindly (an unknown route is an
 * "unmatched route" screen at best).
 */
const APP_ROUTES = [
  /^\/\(tabs\)(\/(yoga|marathon|diet))?$/,
  /^\/race\/[\w-]{1,64}(\/results)?$/,
  /^\/bib\/[\w-]{1,64}$/,
  /^\/session\/[\w-]{1,64}$/,
  /^\/(paywall|run-tracker|yoga-explorer)$/,
];

export function isAppRoute(route: unknown): route is string {
  return typeof route === 'string' && APP_ROUTES.some((re) => re.test(route));
}
