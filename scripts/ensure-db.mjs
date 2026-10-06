#!/usr/bin/env node
/**
 * Makes sure local Postgres is up before the API starts.
 *
 *   1. Is the Docker daemon reachable? If not, launch Docker Desktop and wait.
 *   2. `docker compose up -d postgres` (idempotent — a no-op if already up).
 *   3. Wait until Postgres reports healthy, not merely "started".
 *
 * Run automatically by `npm run dev` and `npm run api:dev`, so a Docker Desktop
 * that quit overnight (tray icon, Windows update, sleep) heals itself instead
 * of surfacing as a confusing API error.
 */

import { execSync, spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { platform } from 'node:os';

const CONTAINER = 'timeshealth-postgres';
const DOCKER_WAIT_S = 120;
const DB_WAIT_S = 60;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function ok(cmd) {
  try {
    execSync(cmd, { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

function launchDockerDesktop() {
  if (platform() === 'win32') {
    const exe = 'C:\\Program Files\\Docker\\Docker\\Docker Desktop.exe';
    if (!existsSync(exe)) {
      throw new Error(`Docker Desktop not found at ${exe}. Install it from docker.com.`);
    }
    spawn(exe, [], { detached: true, stdio: 'ignore' }).unref();
  } else if (platform() === 'darwin') {
    spawn('open', ['-a', 'Docker'], { detached: true, stdio: 'ignore' }).unref();
  } else {
    throw new Error('Start the Docker daemon (e.g. `sudo systemctl start docker`) and retry.');
  }
}

async function waitFor(label, check, seconds) {
  for (let i = 0; i < seconds; i += 1) {
    if (check()) return true;
    if (i > 0 && i % 10 === 0) console.log(`  …still waiting for ${label} (${i}s)`);
    await sleep(1000);
  }
  return false;
}

async function main() {
  if (!ok('docker info')) {
    console.log('Docker is not running — starting Docker Desktop…');
    launchDockerDesktop();
    const up = await waitFor('Docker', () => ok('docker info'), DOCKER_WAIT_S);
    if (!up) throw new Error(`Docker did not become ready within ${DOCKER_WAIT_S}s.`);
    console.log('Docker is up.');
  }

  execSync('docker compose up -d postgres', { stdio: 'ignore' });

  const healthy = await waitFor(
    'Postgres',
    () => ok(`docker exec ${CONTAINER} pg_isready -U timeshealth -d timeshealth`),
    DB_WAIT_S,
  );
  if (!healthy) throw new Error(`Postgres did not become ready within ${DB_WAIT_S}s.`);
  console.log('Postgres is ready on localhost:5433.');
}

main().catch((err) => {
  console.error(`\n✖ ${err.message}\n`);
  process.exit(1);
});
