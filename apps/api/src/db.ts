import { PrismaClient } from '@prisma/client';
import { isProd } from './env.js';

export const prisma = new PrismaClient({
  log: isProd ? ['warn', 'error'] : ['warn', 'error'],
});

/** Single-row platform config. Created on first read. */
export async function getAppConfig() {
  const existing = await prisma.appConfig.findUnique({ where: { id: 1 } });
  if (existing) return existing;
  return prisma.appConfig.create({ data: { id: 1 } });
}
