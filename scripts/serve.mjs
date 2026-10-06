#!/usr/bin/env node
/**
 * Hosts the TimesHealth+ API from this PC, reachable from any phone.
 *
 *   npm run serve
 *
 *   1. Makes sure Docker + Postgres are up (scripts/ensure-db.mjs).
 *   2. Starts the API on localhost:4000.
 *   3. Opens an ngrok tunnel on your fixed domain, so the address baked into
 *      the APK keeps working across restarts.
 *
 *   npm run serve -- --qa   also accepts the QA persona tokens, for testing
 *                           the APK's persona sign-in from a phone.
 *
 * Without --qa the tunnelled API never accepts persona tokens, whatever
 * apps/api/.env says: anyone who finds the URL could mint one. Even with --qa
 * they are limited to the QA identities (qa_* / @th.test), never real users.
 *
 * Needs .secrets/NGROK_AUTHTOKEN and .secrets/NGROK_DOMAIN.
 * The app only works while this is running and the PC is awake.
 * Ctrl+C closes the tunnel and stops the API.
 */

import { execSync, spawn } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const API_DIR = path.join(ROOT, 'apps', 'api');
const PORT = 4000;
const QA = process.argv.includes('--qa');

function readSecret(name) {
  const p = path.join(ROOT, '.secrets', name);
  return existsSync(p) ? readFileSync(p, 'utf8').trim() : null;
}

function fail(msg) {
  console.error(`\n✖ ${msg}\n`);
  process.exit(1);
}

const authtoken = readSecret('NGROK_AUTHTOKEN');
const domain = readSecret('NGROK_DOMAIN')?.replace(/^https?:\/\//, '').replace(/\/+$/, '');
if (!authtoken || !domain) {
  fail(
    'ngrok is not set up yet. Save your authtoken to .secrets/NGROK_AUTHTOKEN and your\n' +
      '  free static domain (e.g. something.ngrok-free.app) to .secrets/NGROK_DOMAIN.',
  );
}

// The ngrok SDK lives in the API workspace.
const ngrok = createRequire(path.join(API_DIR, 'package.json'))('@ngrok/ngrok');

async function waitForHealth(seconds) {
  for (let i = 0; i < seconds; i += 1) {
    try {
      const r = await fetch(`http://localhost:${PORT}/health`);
      if (r.ok) return true;
    } catch {
      // not up yet
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  return false;
}

async function main() {
  execSync('node scripts/ensure-db.mjs', { cwd: ROOT, stdio: 'inherit' });

  console.log('Starting API…');
  const api = spawn('npx', ['tsx', 'src/server.ts'], {
    cwd: API_DIR,
    // Process env wins over apps/api/.env (dotenv never overrides), so this
    // decides persona sign-in for the tunnelled server.
    env: { ...process.env, PUBLIC_TUNNEL: 'true', ALLOW_DEV_TOKENS: QA ? 'true' : 'false' },
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });

  let listener = null;
  let shuttingDown = false;
  const shutdown = async (code = 0) => {
    if (shuttingDown) return;
    shuttingDown = true;
    console.log('\nShutting down…');
    try {
      await listener?.close();
    } catch {
      // already closed
    }
    api.kill();
    process.exit(code);
  };
  process.on('SIGINT', () => void shutdown(0));
  process.on('SIGTERM', () => void shutdown(0));
  api.on('exit', (code) => {
    if (!shuttingDown) {
      console.error(`\n✖ API stopped unexpectedly (exit ${code}). Closing the tunnel.`);
      void shutdown(1);
    }
  });

  if (!(await waitForHealth(60))) {
    console.error('✖ API did not become healthy within 60s.');
    return shutdown(1);
  }

  listener = await ngrok.forward({ addr: PORT, authtoken, domain });

  console.log('\n────────────────────────────────────────────────────────');
  console.log(`  TimesHealth+ API is live:  ${listener.url()}/v1`);
  console.log(`  Health check:              ${listener.url()}/health`);
  console.log(
    QA
      ? '  QA mode: test-persona sign-in is ON for anyone with this URL\n' +
          '  (QA identities only). Don’t share the URL outside the team.'
      : '  Persona sign-in is OFF (start with --qa to test personas).',
  );
  console.log('  Keep this window open. Ctrl+C to stop.');
  console.log('────────────────────────────────────────────────────────\n');
}

main().catch((err) => fail(err?.message ?? String(err)));
