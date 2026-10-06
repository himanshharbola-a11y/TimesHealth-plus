import Constants from 'expo-constants';
import * as SecureStore from 'expo-secure-store';
import type { ApiError } from '@th/types';
import { getIdToken, identitySignOut } from '@/lib/identity';

/**
 * API client.
 *
 * The base URL defaults to 10.0.2.2 — the Android emulator's alias for the
 * host machine's localhost. On a physical device set EXPO_PUBLIC_API_URL to
 * your machine's LAN address.
 */
// The native app config first: app.config.js writes the build's API address
// there on every build, so a stale bundler cache can't point a release at the
// wrong server. The inlined env var is the fallback for unusual dev setups.
export const API_BASE_URL: string =
  (Constants.expoConfig?.extra as { apiBaseUrl?: string } | undefined)?.apiBaseUrl ??
  process.env.EXPO_PUBLIC_API_URL ??
  'http://10.0.2.2:4000/v1';

/**
 * Two kinds of credential:
 *   - A QA persona token, chosen on the login screen in test builds and stored
 *     here. Only accepted by a server with ALLOW_DEV_TOKENS=true.
 *   - A Firebase ID token for a real signed-in user. Never stored by us —
 *     Firebase keeps the session and refreshes the token before it expires.
 * A persona, when set, wins, so testers can switch states without signing out
 * of their real account.
 */
const PERSONA_KEY = 'th_auth_token';

let cachedPersona: string | null | undefined;

export async function getPersonaToken(): Promise<string | null> {
  if (cachedPersona !== undefined) return cachedPersona;
  try {
    cachedPersona = await SecureStore.getItemAsync(PERSONA_KEY);
  } catch {
    // Secure store can fail on a device with no screen lock. Treat as signed out
    // rather than crashing the app on launch.
    cachedPersona = null;
  }
  return cachedPersona;
}

export async function getToken(): Promise<string | null> {
  const persona = await getPersonaToken();
  if (persona) return persona;
  try {
    return await getIdToken();
  } catch {
    return null;
  }
}

export async function setToken(token: string | null): Promise<void> {
  cachedPersona = token;
  try {
    if (token === null) await SecureStore.deleteItemAsync(PERSONA_KEY);
    else await SecureStore.setItemAsync(PERSONA_KEY, token);
  } catch {
    // Non-fatal: the in-memory copy keeps the session alive for this launch.
  }
}

/**
 * Called when the server rejects our token. The session store registers here
 * rather than being imported, which would make client and store import each
 * other.
 */
let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(fn: () => void): void {
  onUnauthorized = fn;
}

export class ApiRequestError extends Error {
  constructor(
    readonly status: number,
    readonly body: ApiError,
  ) {
    super(body.message);
    this.name = 'ApiRequestError';
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  query?: Record<string, string | number | undefined>;
  /** Endpoints callable before sign-in. */
  anonymous?: boolean;
  timeoutMs?: number;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, query, anonymous = false, timeoutMs = 15000 } = options;

  const url = new URL(`${API_BASE_URL}${path}`);
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined) url.searchParams.set(k, String(v));
    }
  }

  const headers: Record<string, string> = {
    accept: 'application/json',
    // While the API is hosted through ngrok's free tier, this guarantees the
    // request reaches the API instead of ngrok's browser warning page. Ignored
    // by every other host, so it can stay after migration.
    'ngrok-skip-browser-warning': '1',
  };
  if (body !== undefined) headers['content-type'] = 'application/json';
  let sentToken = false;
  if (!anonymous) {
    const token = await getToken();
    if (token) {
      headers.authorization = `Bearer ${token}`;
      sentToken = true;
    }
  }

  // Every request is bounded. A hung socket on a flaky Indian mobile network
  // must surface as an error state, not an endless spinner.
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  let response: Response;
  try {
    response = await fetch(url.toString(), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });
  } catch (cause) {
    clearTimeout(timer);
    const aborted = (cause as Error)?.name === 'AbortError';
    throw new ApiRequestError(0, {
      code: aborted ? 'TIMEOUT' : 'NETWORK',
      message: aborted
        ? 'That took too long. Check your connection and try again.'
        : 'No connection. Check your network and try again.',
    });
  } finally {
    clearTimeout(timer);
  }

  if (response.status === 204) return undefined as T;

  const text = await response.text();
  let parsed: unknown = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    // An HTML error page from a proxy or tunnel (ngrok's offline page, a
    // gateway 502) — not something to show the user verbatim.
    if (response.ok) {
      throw new ApiRequestError(response.status, {
        code: 'BAD_RESPONSE',
        message: 'Something went wrong on our side. Please try again.',
      });
    }
  }

  // Only a 401 means the session is over. A 503 means the server is having a
  // bad moment and must never sign the user out — the API returns 503, not
  // 401, when its database is unreachable for exactly this reason.
  // Only a REJECTED token ends the session. A 401 for a request that carried
  // no token at all — say, one that fired before Firebase finished restoring
  // the saved login on launch — must not sign a real user out.
  if (response.status === 401 && sentToken) {
    await setToken(null);
    await identitySignOut();
    onUnauthorized?.();
  }

  if (!response.ok) {
    const err = (parsed ?? {}) as Partial<ApiError>;
    throw new ApiRequestError(response.status, {
      code: err.code ?? 'UNKNOWN',
      message: err.message ?? 'Something went wrong.',
      fields: err.fields,
    });
  }

  return parsed as T;
}

export const api = {
  get: <T>(path: string, query?: RequestOptions['query']) => request<T>(path, { query }),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST', body }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
};
