import { env } from './env.js';
import { prisma } from './db.js';
import { assertIdentityReady } from './identity/index.js';
import { buildApp } from './app.js';
import { startScheduler, stopScheduler } from './services/scheduler.js';

const app = await buildApp();

async function shutdown(signal: string) {
  app.log.info({ signal }, 'shutting down');
  // Let a tick that is mid-send finish before the database goes away.
  await stopScheduler();
  await app.close();
  await prisma.$disconnect();
  process.exit(0);
}
process.on('SIGTERM', () => void shutdown('SIGTERM'));
process.on('SIGINT', () => void shutdown('SIGINT'));

try {
  // Initialise the identity provider before listening, so a bad or missing key fails the
  // boot instead of failing the first user who tries to log in.
  const authMode = assertIdentityReady();
  await app.listen({ port: env.port, host: env.host });

  if (authMode === 'dev-only') {
    app.log.warn(
      'DEV AUTH MODE — Firebase is not configured. Persona tokens ("uid|email|phone") ' +
        'are accepted unverified. Never expose this server to real users.',
    );
  } else if (env.allowDevTokens) {
    app.log.warn(
      'Firebase auth ON, and ALLOW_DEV_TOKENS=true — QA persona tokens are ALSO ' +
        'accepted. Turn this off before anyone outside the team uses this server.',
    );
  } else {
    app.log.info('Firebase auth ON. Persona tokens are rejected.');
  }

  // Push needs Firebase; without it every send would return "disabled", so
  // don't run the loop at all.
  if (authMode === 'firebase') startScheduler(app.log);
} catch (err) {
  app.log.error(err);
  process.exit(1);
}
