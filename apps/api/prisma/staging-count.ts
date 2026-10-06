/**
 * Prints "COUNT=<n>" — the number of marathon editions — so
 * scripts/staging-start.mjs can tell an empty database (seed it) from one
 * testers are already using (leave it alone). A file rather than `tsx -e`,
 * because a shell would mangle the `$` in `$disconnect`.
 */
import { PrismaClient } from '@prisma/client';

const prisma = new PrismaClient();
const n = await prisma.marathonEvent.count();
console.log(`COUNT=${n}`);
await prisma.$disconnect();
