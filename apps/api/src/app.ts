import { createHash } from 'node:crypto';
import Fastify, { type FastifyInstance } from 'fastify';
import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import rateLimit from '@fastify/rate-limit';
import { env, isProd } from './env.js';
import { prisma } from './db.js';
import authPlugin from './auth.js';
import sessionRoutes from './routes/session.js';
import yogaRoutes from './routes/yoga.js';
import marathonRoutes from './routes/marathon.js';
import runRoutes from './routes/runs.js';
import dietRoutes from './routes/diet.js';
import contentRoutes from './routes/content.js';
import orderRoutes from './routes/orders.js';
import deviceRoutes from './routes/devices.js';

/**
 * Builds the API without starting it. server.ts adds listening, the scheduler
 * and signal handling; tests use this directly with app.inject(), so they
 * exercise the real routes, auth and database without opening a port.
 */
export async function buildApp(opts: { logger?: boolean } = {}): Promise<FastifyInstance> {
  const app = Fastify({
    logger:
      opts.logger === false
        ? false
        : {
            level: isProd ? 'info' : 'debug',
            // The request line is logged WITHOUT its query string: queries carry
            // coordinates (/marathon/events?lat=…) and tokens (wa-join ?t=…),
            // which docs/04 T10 says never reach logs.
            serializers: {
              req: (r: { method?: string; url?: string; id?: string }) => ({
                method: r.method,
                url: (r.url ?? '').split('?')[0],
                reqId: r.id,
              }),
            },
            // Never log tokens, OTPs, phone numbers or coordinates — docs/04 T10.
            redact: {
              paths: [
                'req.headers.authorization',
                'req.body.phone',
                'req.body.email',
                'req.body.routePolyline',
              ],
              remove: true,
            },
          },
    // Trust exactly the proxies in front of us (TRUST_PROXY_HOPS, default 1:
    // ngrok or one load balancer). `true` trusted every hop, so any client
    // could pick its own req.ip with a fake X-Forwarded-For and reset its
    // rate-limit bucket on every request.
    trustProxy: (_address: string, hop: number) => hop < env.trustProxyHops,
  });

  await app.register(helmet, { contentSecurityPolicy: false });
  await app.register(cors, {
    origin: env.corsOrigins.includes('*') ? true : env.corsOrigins,
    credentials: true,
  });
  await app.register(rateLimit, {
    max: 300,
    timeWindow: '1 minute',
    // Keyed per SIGNED-IN USER where a bearer token is present (hashed — the
    // token never sits in memory as a key), per IP otherwise. The limiter runs
    // before auth, so req.user is never set here; keying on it fell back to
    // IP for everyone — and whole Jio/Airtel CGNAT ranges share one IP.
    keyGenerator: (req) => {
      const auth = req.headers.authorization;
      if (auth?.startsWith('Bearer ')) {
        return `t:${createHash('sha256').update(auth.slice(7)).digest('hex').slice(0, 32)}`;
      }
      return `ip:${req.ip}`;
    },
  });

  await app.register(authPlugin);

  // Must be set BEFORE the routes are registered. Fastify error handlers only
  // reach plugins registered after them; setting it last left every /v1 route
  // on Fastify's default handler, which echoes raw internal error messages.
  app.setErrorHandler((error: Error & { statusCode?: number }, req, reply) => {
    const status = error.statusCode ?? 500;
    if (status >= 500) req.log.error({ err: error }, 'request failed');
    reply.code(status).send({
      code:
        status === 401
          ? 'UNAUTHORIZED'
          : status === 404
            ? 'NOT_FOUND'
            : status === 429
              ? 'TOO_MANY_REQUESTS' // the global limiter — apps branch on this code
              : status === 503
                ? 'UNAVAILABLE'
                : status >= 500
                  ? 'INTERNAL'
                  : 'BAD_REQUEST',
      // Never leak internals to the client in production.
      message:
        status === 429
          ? 'Too many requests. Please wait a moment and try again.'
          : status >= 500 && isProd
            ? 'Something went wrong'
            : error.message,
    });
  });

  // Unknown routes answer in the same { code, message } shape as everything
  // else, so a client never has to special-case Fastify's default 404 body.
  app.setNotFoundHandler((_req, reply) => {
    reply.code(404).send({ code: 'NOT_FOUND', message: 'Not found' });
  });

  // Health reports the database too, so a load balancer stops routing traffic
  // to an instance that cannot reach it. Returns 503 rather than throwing.
  app.get('/health', async (_req, reply) => {
    try {
      await prisma.$queryRaw`SELECT 1`;
      return { ok: true, service: 'timeshealth-api', time: new Date().toISOString() };
    } catch {
      return reply.code(503).send({ ok: false, service: 'timeshealth-api', db: 'unreachable' });
    }
  });

  await app.register(
    async (api) => {
      await api.register(sessionRoutes);
      await api.register(yogaRoutes);
      await api.register(marathonRoutes);
      await api.register(runRoutes);
      await api.register(dietRoutes);
      await api.register(contentRoutes);
      await api.register(orderRoutes);
      await api.register(deviceRoutes);
    },
    { prefix: '/v1' },
  );

  return app;
}
