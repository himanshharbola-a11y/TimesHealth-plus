-- Home personalisation switch (dashboard → App switches). On by default.
-- Idempotent, and mirrored in apps/api/prisma/schema.prisma (AppConfig.personalizeHome).
ALTER TABLE "AppConfig" ADD COLUMN IF NOT EXISTS "personalizeHome" BOOLEAN NOT NULL DEFAULT true;
