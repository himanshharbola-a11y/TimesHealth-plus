import 'dotenv/config';

function required(name: string, fallback?: string): string {
  // `||`, not `??`: an EMPTY value (a blank line copied from .env.example)
  // must not become an empty HMAC key.
  const v = process.env[name] || fallback;
  if (v === undefined) throw new Error(`Missing required env var: ${name}`);
  return v;
}

export const env = {
  nodeEnv: process.env.NODE_ENV ?? 'development',
  port: Number(process.env.PORT ?? 4000),
  host: process.env.HOST ?? '0.0.0.0',

  databaseUrl: required(
    'DATABASE_URL',
    'postgresql://timeshealth:timeshealth_dev@localhost:5433/timeshealth',
  ),

  /**
   * Firebase service account, as either a file path (simplest for hosting on a
   * PC) or base64 JSON (simplest for cloud hosts, where there is no disk).
   * When neither is set the API runs in DEV AUTH MODE — refused in production.
   */
  firebaseServiceAccountFile: process.env.FIREBASE_SERVICE_ACCOUNT_FILE ?? null,
  firebaseServiceAccountB64: process.env.FIREBASE_SERVICE_ACCOUNT_B64 ?? null,
  firebaseProjectId: process.env.FIREBASE_PROJECT_ID ?? null,

  /**
   * Accept QA persona tokens ("uid|email|phone") alongside real Firebase
   * tokens, so the six test personas keep working after Firebase is switched
   * on. Anyone can mint these, so they are an account-takeover hole in any
   * environment real users touch — hence refused outright in production below.
   */
  allowDevTokens: process.env.ALLOW_DEV_TOKENS === 'true',
  /**
   * Set by scripts/serve.mjs: this API is reachable from the internet through
   * a tunnel, so "no provider configured → accept personas" no longer holds.
   */
  publicTunnel: process.env.PUBLIC_TUNNEL === 'true',
  /** Identity seam switch: 'firebase' (default) or 'times-sso' (src/identity/). */
  authProvider: process.env.AUTH_PROVIDER ?? 'firebase',
  /** Proxies in front of the API (ngrok / one load balancer = 1). */
  trustProxyHops: Number(process.env.TRUST_PROXY_HOPS ?? 1),

  /** Signs digital-bib QR tokens. Rotate independently of auth secrets. */
  bibSigningSecret: required('BIB_SIGNING_SECRET', 'dev-only-bib-secret-change-me'),
  bibTokenTtlSeconds: Number(process.env.BIB_TOKEN_TTL_SECONDS ?? 60),

  /** Signs media playback URLs for entitlement-gated video. */
  mediaSigningSecret: required('MEDIA_SIGNING_SECRET', 'dev-only-media-secret-change-me'),

  /** Signs the per-user WhatsApp class links (GET /yoga/wa-join). */
  waJoinSigningSecret: required('WA_JOIN_SIGNING_SECRET', 'dev-only-wa-join-secret-change-me'),
  /** Base of shared links (Refer & Win). The web funnel domain — docs/01 F6. */
  shareBaseUrl: process.env.SHARE_BASE_URL ?? 'https://timeshealthplus.invalid',
  /**
   * Expo bib scanners: "counter1:key1,counter2:key2". Required in production
   * for POST /marathon/verify-bib (it returns runner names).
   */
  scannerKeys: (process.env.SCANNER_KEYS ?? '')
    .split(',')
    .map((pair) => pair.trim())
    .filter(Boolean)
    .map((pair) => {
      const [scannerId = '', ...rest] = pair.split(':');
      return { id: scannerId, key: rest.join(':') };
    })
    .filter((s) => s.id && s.key.length >= 24),
  /** Where a non-subscriber who opens a WhatsApp class link is sent instead. */
  yogaRenewUrl: process.env.YOGA_RENEW_URL ?? 'https://timeshealthplus.invalid/yoga',
  mediaUrlTtlSeconds: Number(process.env.MEDIA_URL_TTL_SECONDS ?? 300),
  mediaBaseUrl: process.env.MEDIA_BASE_URL ?? 'https://media.timeshealthplus.invalid',

  /** Where diet leads are forwarded (PRD §9). Logged only until wired. */
  dietLeadWebhookUrl: process.env.DIET_LEAD_WEBHOOK_URL ?? null,

  razorpayKeyId: process.env.RAZORPAY_KEY_ID ?? null,
  razorpayKeySecret: process.env.RAZORPAY_KEY_SECRET ?? null,

  corsOrigins: (process.env.CORS_ORIGINS ?? '*').split(','),
} as const;

/**
 * FAIL CLOSED: anything that is not explicitly development or test is treated
 * as production. A deploy with NODE_ENV unset, "staging" or "prod" must not
 * accept persona tokens, simulated payments or leak raw errors.
 */
export const isProd = !['development', 'test'].includes(env.nodeEnv);

export const firebaseConfigured = Boolean(
  env.firebaseServiceAccountFile || env.firebaseServiceAccountB64,
);

if (env.authProvider !== 'firebase' && env.authProvider !== 'times-sso') {
  throw new Error('Unknown AUTH_PROVIDER "' + env.authProvider + '" — expected firebase or times-sso.');
}

if (env.authProvider === 'times-sso') {
  // Fail at boot in EVERY environment until the provider exists, so nobody
  // discovers a dead login screen in front of users.
  throw new Error(
    'AUTH_PROVIDER=times-sso is not implemented yet — see src/identity/timesSso.ts ' +
      'for exactly what the Times SSO team must supply, and docs/06 for the swap plan.',
  );
}

if (isProd && !firebaseConfigured) {
  throw new Error(
    'A Firebase service account is required in production ' +
      '(FIREBASE_SERVICE_ACCOUNT_FILE or FIREBASE_SERVICE_ACCOUNT_B64). ' +
      'Dev auth mode must never run with NODE_ENV=production.',
  );
}

// The signing secrets have dev fallbacks so local setup is one command. In
// production a fallback would let anyone mint bib passes, playback URLs and
// attendance links — so every one of them must be set explicitly.
if (isProd) {
  const secrets: Record<string, string> = {
    BIB_SIGNING_SECRET: env.bibSigningSecret,
    MEDIA_SIGNING_SECRET: env.mediaSigningSecret,
    WA_JOIN_SIGNING_SECRET: env.waJoinSigningSecret,
  };
  const weak = Object.keys(secrets).filter((name) => {
    const value = secrets[name] ?? '';
    return value.startsWith('dev-only-') || value.length < 32;
  });
  if (weak.length > 0) {
    throw new Error(`Production requires real signing secrets (32+ chars): ${weak.join(', ')}`);
  }
}

if (isProd && env.allowDevTokens) {
  throw new Error(
    'ALLOW_DEV_TOKENS=true is refused in production: persona tokens can be ' +
      'forged by anyone and would let them sign in as any user.',
  );
}
