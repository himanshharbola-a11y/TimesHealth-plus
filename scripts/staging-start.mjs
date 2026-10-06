#!/usr/bin/env node
/**
 * Boots the API on a hosted staging server (Render free tier + Neon Postgres).
 *
 *   node scripts/staging-start.mjs
 *
 *   1. Applies the Prisma schema over a DIRECT database connection: Neon's
 *      pooled endpoint (PgBouncer) can't run schema changes.
 *   2. Loads the demo data + QA personas only when the database is empty —
 *      a redeploy or a wake-from-sleep must never wipe what testers are doing.
 *      Set RESET_ON_BOOT=true for one deploy to start fresh (dates in the demo
 *      data are relative to "now", so a refresh every few weeks keeps races
 *      upcoming).
 *   3. Starts the API on the pooled connection, under tsx (the shared
 *      @th/types package ships as TypeScript source).
 *
 * Env: DATABASE_URL (Neon pooled URL). DIRECT_DATABASE_URL is optional — it
 * is derived from DATABASE_URL by dropping "-pooler" from the host.
 */

import { execSync, spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const API_DIR = path.join(ROOT, 'apps', 'api');

const pooledRaw = process.env.DATABASE_URL;
if (!pooledRaw) {
  console.error('DATABASE_URL is not set.');
  process.exit(1);
}

function withParams(url, params) {
  const u = new URL(url);
  for (const [k, v] of Object.entries(params)) if (!u.searchParams.has(k)) u.searchParams.set(k, v);
  return u.toString();
}

const pooled = withParams(pooledRaw, { pgbouncer: 'true', connect_timeout: '15' });
const direct =
  process.env.DIRECT_DATABASE_URL ??
  (() => {
    const u = new URL(pooledRaw);
    u.hostname = u.hostname.replace('-pooler.', '.');
    return withParams(u.toString(), { connect_timeout: '15' });
  })();

/** Never echo commands or env: the URLs carry the database password. */
function run(cmd, databaseUrl) {
  execSync(cmd, { cwd: API_DIR, stdio: 'inherit', env: { ...process.env, DATABASE_URL: databaseUrl } });
}

function eventCount() {
  const out = execSync(
    `npx tsx -e "import { PrismaClient } from '@prisma/client'; const p = new PrismaClient(); p.marathonEvent.count().then((n) => { console.log('COUNT=' + n); return p.$disconnect(); });"`,
    { cwd: API_DIR, env: { ...process.env, DATABASE_URL: direct } },
  ).toString();
  const m = /COUNT=(\d+)/.exec(out);
  return m ? Number(m[1]) : 0;
}

console.log('[staging] applying schema…');
run('npx prisma db push --skip-generate --accept-data-loss', direct);

const reset = process.env.RESET_ON_BOOT === 'true';
if (reset || eventCount() === 0) {
  console.log(reset ? '[staging] RESET_ON_BOOT — reloading demo data…' : '[staging] empty database — loading demo data…');
  run('npx tsx prisma/seed.ts', direct);
  run('npx tsx prisma/personas.ts', direct);
} else {
  console.log('[staging] demo data present — keeping it.');
}

console.log('[staging] starting API…');
const api = spawn('npx', ['tsx', 'src/server.ts'], {
  cwd: API_DIR,
  stdio: 'inherit',
  env: { ...process.env, DATABASE_URL: pooled },
  shell: process.platform === 'win32',
});
for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => api.kill(sig));
api.on('exit', (code) => process.exit(code ?? 0));
