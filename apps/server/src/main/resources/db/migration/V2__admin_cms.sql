-- Admin CMS: page sections, live classes (premieres), dashboard users and the audit log,
-- plus the columns the dashboard edits on existing content.
--
-- Generated from apps/api/prisma/schema.prisma with `prisma migrate diff`, then made
-- idempotent (IF NOT EXISTS everywhere). The Node server's `prisma db push` may create these
-- tables first on a shared database; this migration must then adopt them, not fail.

-- AlterTable
ALTER TABLE "Instructor" ADD COLUMN IF NOT EXISTS "sortOrder" INTEGER NOT NULL DEFAULT 0;

-- AlterTable
ALTER TABLE "YogaBatch" ADD COLUMN IF NOT EXISTS "active" BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE "YogaBatch" ADD COLUMN IF NOT EXISTS "videoProvider" TEXT;
ALTER TABLE "YogaBatch" ADD COLUMN IF NOT EXISTS "videoRef" TEXT;

-- AlterTable
ALTER TABLE "YogaCategory" ADD COLUMN IF NOT EXISTS "visible" BOOLEAN NOT NULL DEFAULT true;

-- AlterTable
ALTER TABLE "YogaSession" ADD COLUMN IF NOT EXISTS "sortOrder" INTEGER NOT NULL DEFAULT 0;
ALTER TABLE "YogaSession" ADD COLUMN IF NOT EXISTS "videoProvider" TEXT;
ALTER TABLE "YogaSession" ADD COLUMN IF NOT EXISTS "videoRef" TEXT;
ALTER TABLE "YogaSession" ADD COLUMN IF NOT EXISTS "visible" BOOLEAN NOT NULL DEFAULT true;

-- CreateTable
CREATE TABLE IF NOT EXISTS "FeedSection" (
    "id" TEXT NOT NULL,
    "page" TEXT NOT NULL,
    "kind" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "subtitle" TEXT,
    "actionLabel" TEXT,
    "imageUrl" TEXT,
    "categoryId" TEXT,
    "maxItems" INTEGER NOT NULL DEFAULT 10,
    "sortOrder" INTEGER NOT NULL DEFAULT 0,
    "visible" BOOLEAN NOT NULL DEFAULT true,
    "audience" TEXT NOT NULL DEFAULT 'ALL',
    "startsAt" TIMESTAMP(3),
    "endsAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "FeedSection_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE IF NOT EXISTS "FeedSectionItem" (
    "id" TEXT NOT NULL,
    "sectionId" TEXT NOT NULL,
    "refType" TEXT NOT NULL,
    "refId" TEXT NOT NULL,
    "sortOrder" INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT "FeedSectionItem_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE IF NOT EXISTS "LiveClass" (
    "id" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "description" TEXT NOT NULL DEFAULT '',
    "imageUrl" TEXT,
    "startsAt" TIMESTAMP(3) NOT NULL,
    "durationMinutes" INTEGER NOT NULL DEFAULT 45,
    "isFree" BOOLEAN NOT NULL DEFAULT false,
    "videoProvider" TEXT NOT NULL DEFAULT 'url',
    "videoRef" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'SCHEDULED',
    "instructorId" TEXT,
    "batchId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "LiveClass_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE IF NOT EXISTS "AdminUser" (
    "id" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "role" TEXT NOT NULL,
    "passwordHash" TEXT NOT NULL,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "lastLoginAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "AdminUser_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE IF NOT EXISTS "AdminAuditLog" (
    "id" TEXT NOT NULL,
    "adminId" TEXT,
    "adminEmail" TEXT NOT NULL,
    "action" TEXT NOT NULL,
    "resource" TEXT NOT NULL,
    "resourceId" TEXT,
    "summary" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "AdminAuditLog_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX IF NOT EXISTS "FeedSection_page_sortOrder_idx" ON "FeedSection"("page", "sortOrder");
CREATE INDEX IF NOT EXISTS "FeedSectionItem_sectionId_sortOrder_idx" ON "FeedSectionItem"("sectionId", "sortOrder");
CREATE UNIQUE INDEX IF NOT EXISTS "FeedSectionItem_sectionId_refType_refId_key" ON "FeedSectionItem"("sectionId", "refType", "refId");
CREATE INDEX IF NOT EXISTS "LiveClass_startsAt_idx" ON "LiveClass"("startsAt");
CREATE UNIQUE INDEX IF NOT EXISTS "AdminUser_email_key" ON "AdminUser"("email");
CREATE INDEX IF NOT EXISTS "AdminAuditLog_createdAt_idx" ON "AdminAuditLog"("createdAt");
CREATE INDEX IF NOT EXISTS "AdminAuditLog_resource_resourceId_idx" ON "AdminAuditLog"("resource", "resourceId");

-- AddForeignKey (Postgres has no ADD CONSTRAINT IF NOT EXISTS)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'FeedSectionItem_sectionId_fkey') THEN
        ALTER TABLE "FeedSectionItem" ADD CONSTRAINT "FeedSectionItem_sectionId_fkey"
            FOREIGN KEY ("sectionId") REFERENCES "FeedSection"("id") ON DELETE CASCADE ON UPDATE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'LiveClass_instructorId_fkey') THEN
        ALTER TABLE "LiveClass" ADD CONSTRAINT "LiveClass_instructorId_fkey"
            FOREIGN KEY ("instructorId") REFERENCES "Instructor"("id") ON DELETE SET NULL ON UPDATE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'LiveClass_batchId_fkey') THEN
        ALTER TABLE "LiveClass" ADD CONSTRAINT "LiveClass_batchId_fkey"
            FOREIGN KEY ("batchId") REFERENCES "YogaBatch"("id") ON DELETE SET NULL ON UPDATE CASCADE;
    END IF;
END $$;

-- The default Home layout: exactly the order the feed used before it became editable, plus the
-- new "Upcoming live classes" rail (empty until classes are scheduled, and an empty section is
-- never shown). Fixed ids, so re-running is harmless and PMs' later edits are kept.
INSERT INTO "FeedSection" ("id", "page", "kind", "title", "subtitle", "actionLabel", "imageUrl", "maxItems", "sortOrder", "updatedAt") VALUES
    ('sec_home_hero',         'HOME', 'HERO',              'Today (hero cards)',                NULL, NULL,      NULL, 2,  10,  CURRENT_TIMESTAMP),
    ('sec_home_for_you',      'HOME', 'PERSONALISED_RAIL', 'Recommended for you',               NULL, 'See all', NULL, 4,  20,  CURRENT_TIMESTAMP),
    ('sec_home_promo',        'HOME', 'PROMO',             'Promo strip',                       NULL, NULL,      NULL, 1,  30,  CURRENT_TIMESTAMP),
    ('sec_home_free',         'HOME', 'FREE_SESSIONS',     'Free yoga sessions',                NULL, 'Explore', NULL, 6,  40,  CURRENT_TIMESTAMP),
    ('sec_home_live',         'HOME', 'LIVE_CLASSES',      'Upcoming live classes',             NULL, NULL,      NULL, 6,  50,  CURRENT_TIMESTAMP),
    ('sec_home_run',          'HOME', 'RUN_TRACKER_TILE',  'GPS Outdoor Run',
        'Track live distance, pace & route — even with the screen off', NULL,
        'https://images.unsplash.com/photo-1571008887538-b36bb32f4571?auto=format&fit=crop&w=900&q=70', 1, 60, CURRENT_TIMESTAMP),
    ('sec_home_workshops',    'HOME', 'WORKSHOPS',         'Upcoming Live Workshops',           NULL, NULL,      NULL, 10, 70,  CURRENT_TIMESTAMP),
    ('sec_home_instructors',  'HOME', 'INSTRUCTORS',       'Explore our instructors',           NULL, NULL,      NULL, 10, 80,  CURRENT_TIMESTAMP),
    ('sec_home_articles',     'HOME', 'ARTICLES',          'TOI & ET coverage',                 NULL, NULL,      NULL, 8,  90,  CURRENT_TIMESTAMP),
    ('sec_home_testimonials', 'HOME', 'TESTIMONIALS',      'What our members say',              NULL, NULL,      NULL, 8,  100, CURRENT_TIMESTAMP)
ON CONFLICT ("id") DO NOTHING;
