-- CreateSchema
CREATE SCHEMA IF NOT EXISTS "public";

-- CreateTable
CREATE TABLE "User" (
    "id" TEXT NOT NULL,
    "firebaseUid" TEXT NOT NULL,
    "email" TEXT,
    "phone" TEXT,
    "contactEmail" TEXT,
    "contactPhone" TEXT,
    "name" TEXT,
    "dob" TIMESTAMP(3),
    "gender" TEXT,
    "healthGoal" TEXT,
    "concern" TEXT,
    "units" TEXT NOT NULL DEFAULT 'METRIC',
    "locale" TEXT NOT NULL DEFAULT 'en-IN',
    "onboardingCompleted" BOOLEAN NOT NULL DEFAULT false,
    "profileCompletion" INTEGER NOT NULL DEFAULT 0,
    "referredByCode" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "User_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "YogaSubscription" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "planId" TEXT NOT NULL,
    "planLabel" TEXT NOT NULL,
    "status" TEXT NOT NULL,
    "startedAt" TIMESTAMP(3) NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "autoRenews" BOOLEAN NOT NULL DEFAULT true,
    "reminderSlotId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "YogaSubscription_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Instructor" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "specialty" TEXT NOT NULL,
    "bio" TEXT NOT NULL,
    "experience" TEXT NOT NULL,
    "avatarUrl" TEXT NOT NULL,
    "rating" DOUBLE PRECISION NOT NULL DEFAULT 0,
    "handle" TEXT,
    "reelCount" INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT "Instructor_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "YogaBatch" (
    "id" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "time" TEXT NOT NULL,
    "period" TEXT NOT NULL,
    "sortOrder" INTEGER NOT NULL DEFAULT 0,
    "instructorId" TEXT NOT NULL,

    CONSTRAINT "YogaBatch_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "YogaCategory" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "tagline" TEXT NOT NULL,
    "bodyTargetSummary" TEXT NOT NULL,
    "imageUrl" TEXT NOT NULL,
    "bannerTheme" TEXT NOT NULL DEFAULT 'NEUTRAL',
    "totalYogisJoined" INTEGER NOT NULL DEFAULT 0,
    "sortOrder" INTEGER NOT NULL DEFAULT 0,
    "concernTag" TEXT,

    CONSTRAINT "YogaCategory_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "YogaSession" (
    "id" TEXT NOT NULL,
    "categoryId" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "description" TEXT NOT NULL,
    "imageUrl" TEXT NOT NULL,
    "durationMinutes" INTEGER NOT NULL,
    "level" TEXT NOT NULL,
    "intensity" TEXT NOT NULL,
    "caloriesBurned" INTEGER NOT NULL DEFAULT 0,
    "isFree" BOOLEAN NOT NULL DEFAULT false,
    "bodyFocusTitle" TEXT NOT NULL,
    "targetBodyParts" TEXT[],
    "keyPoses" TEXT[],
    "lifestyleImpact" TEXT NOT NULL,
    "instructorId" TEXT NOT NULL,
    "joinedCountTillDate" INTEGER NOT NULL DEFAULT 0,
    "todayActiveCount" INTEGER NOT NULL DEFAULT 0,
    "mediaKey" TEXT,

    CONSTRAINT "YogaSession_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Attendance" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "date" DATE NOT NULL,
    "batchId" TEXT,
    "source" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Attendance_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "SavedSession" (
    "userId" TEXT NOT NULL,
    "sessionId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "SavedSession_pkey" PRIMARY KEY ("userId","sessionId")
);

-- CreateTable
CREATE TABLE "CompletedSession" (
    "userId" TEXT NOT NULL,
    "sessionId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "CompletedSession_pkey" PRIMARY KEY ("userId","sessionId")
);

-- CreateTable
CREATE TABLE "MarathonEvent" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "city" TEXT NOT NULL,
    "venue" TEXT NOT NULL,
    "imageUrl" TEXT NOT NULL,
    "startsAt" TIMESTAMP(3) NOT NULL,
    "flagOffTime" TEXT NOT NULL,
    "registrationOpen" BOOLEAN NOT NULL DEFAULT true,
    "rescheduledFrom" TIMESTAMP(3),
    "latitude" DOUBLE PRECISION,
    "longitude" DOUBLE PRECISION,
    "expoVenue" TEXT,
    "expoAddress" TEXT,
    "expoStartsAt" TIMESTAMP(3),
    "expoEndsAt" TIMESTAMP(3),
    "expoPickupWindow" TEXT,
    "expoInstructions" TEXT,
    "expoDocuments" TEXT[],

    CONSTRAINT "MarathonEvent_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "RaceDistanceOption" (
    "id" TEXT NOT NULL,
    "eventId" TEXT NOT NULL,
    "code" TEXT NOT NULL,
    "label" TEXT NOT NULL,
    "priceClassicPaise" INTEGER NOT NULL,
    "pricePremiumPaise" INTEGER NOT NULL,
    "wasPriceClassicPaise" INTEGER,
    "wasPricePremiumPaise" INTEGER,
    "premiumSoldOut" BOOLEAN NOT NULL DEFAULT false,
    "registrationOpen" BOOLEAN NOT NULL DEFAULT true,
    "sortOrder" INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT "RaceDistanceOption_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "MarathonRegistration" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "eventId" TEXT NOT NULL,
    "registrationRef" TEXT NOT NULL,
    "tier" TEXT NOT NULL,
    "category" TEXT NOT NULL,
    "bibNumber" TEXT,
    "status" TEXT NOT NULL DEFAULT 'UPCOMING',
    "registeredAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "tshirtSize" TEXT,
    "emergencyContactName" TEXT,
    "emergencyContactPhone" TEXT,

    CONSTRAINT "MarathonRegistration_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "RaceResult" (
    "id" TEXT NOT NULL,
    "registrationId" TEXT NOT NULL,
    "published" BOOLEAN NOT NULL DEFAULT false,
    "finishTime" TEXT,
    "chipTime" TEXT,
    "avgPace" TEXT,
    "overallRank" INTEGER,
    "ageGroupRank" INTEGER,
    "splits" JSONB,
    "certificateUrl" TEXT,
    "medalStatus" TEXT,
    "photoUrls" TEXT[],
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "RaceResult_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "KitDelivery" (
    "id" TEXT NOT NULL,
    "registrationId" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'NOT_DISPATCHED',
    "courierName" TEXT,
    "trackingRef" TEXT,
    "expectedBy" TIMESTAMP(3),

    CONSTRAINT "KitDelivery_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "RaceFaq" (
    "id" TEXT NOT NULL,
    "eventId" TEXT NOT NULL,
    "question" TEXT NOT NULL,
    "answer" TEXT NOT NULL,
    "sortOrder" INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT "RaceFaq_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Referral" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "code" TEXT NOT NULL,
    "confirmedReferrals" INTEGER NOT NULL DEFAULT 0,
    "luckyDrawEntries" INTEGER NOT NULL DEFAULT 0,
    "guaranteedUpgradeUnlocked" BOOLEAN NOT NULL DEFAULT false,
    "upgradeClaimedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Referral_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "ReferralCredit" (
    "id" TEXT NOT NULL,
    "referralId" TEXT NOT NULL,
    "referredUserId" TEXT NOT NULL,
    "orderId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "ReferralCredit_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "IdentityConflict" (
    "id" TEXT NOT NULL,
    "newUserId" TEXT NOT NULL,
    "matchedUserIds" TEXT[],
    "reason" TEXT NOT NULL,
    "resolvedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "IdentityConflict_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "LiveWorkshop" (
    "id" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "description" TEXT NOT NULL,
    "category" TEXT NOT NULL,
    "focusArea" TEXT NOT NULL,
    "imageUrl" TEXT NOT NULL,
    "startsAt" TIMESTAMP(3) NOT NULL,
    "durationMinutes" INTEGER NOT NULL,
    "level" TEXT NOT NULL,
    "platform" TEXT NOT NULL,
    "instructorName" TEXT NOT NULL,
    "instructorTitle" TEXT NOT NULL,
    "instructorAvatarUrl" TEXT NOT NULL,
    "pricePaise" INTEGER,
    "totalCapacity" INTEGER NOT NULL,
    "joinUrl" TEXT,

    CONSTRAINT "LiveWorkshop_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "WorkshopRegistration" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "workshopId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "WorkshopRegistration_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "RunRecord" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "startedAt" TIMESTAMP(3) NOT NULL,
    "endedAt" TIMESTAMP(3) NOT NULL,
    "distanceKm" DOUBLE PRECISION NOT NULL,
    "durationSeconds" INTEGER NOT NULL,
    "avgPaceSecPerKm" INTEGER NOT NULL,
    "caloriesBurned" INTEGER NOT NULL,
    "routePolyline" TEXT,
    "hasAccuracyWarning" BOOLEAN NOT NULL DEFAULT false,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "RunRecord_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "DietLead" (
    "id" TEXT NOT NULL,
    "userId" TEXT,
    "name" TEXT NOT NULL,
    "phone" TEXT NOT NULL,
    "condition" TEXT,
    "cuisinePreference" TEXT,
    "bestTimeToCall" TEXT,
    "forwardedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DietLead_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Article" (
    "id" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "source" TEXT NOT NULL,
    "category" TEXT NOT NULL,
    "imageUrl" TEXT NOT NULL,
    "readTimeMinutes" INTEGER NOT NULL,
    "url" TEXT NOT NULL,
    "concernTag" TEXT,
    "sortOrder" INTEGER NOT NULL DEFAULT 0,
    "publishedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Article_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Reel" (
    "id" TEXT NOT NULL,
    "instructorId" TEXT NOT NULL,
    "thumbnailUrl" TEXT NOT NULL,
    "playbackUrl" TEXT NOT NULL,
    "durationSeconds" INTEGER NOT NULL,
    "sortOrder" INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT "Reel_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "UserQuote" (
    "id" TEXT NOT NULL,
    "quote" TEXT NOT NULL,
    "author" TEXT NOT NULL,
    "role" TEXT NOT NULL,
    "sortOrder" INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT "UserQuote_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "PromoCampaign" (
    "id" TEXT NOT NULL,
    "campaignId" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "subtitle" TEXT,
    "ctaLabel" TEXT NOT NULL,
    "imageUrl" TEXT,
    "backgroundColor" TEXT,
    "action" JSONB NOT NULL,
    "sellsProduct" TEXT NOT NULL,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "startsAt" TIMESTAMP(3),
    "endsAt" TIMESTAMP(3),
    "priority" INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT "PromoCampaign_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Order" (
    "id" TEXT NOT NULL,
    "userId" TEXT,
    "productType" TEXT NOT NULL,
    "productId" TEXT NOT NULL,
    "eventId" TEXT,
    "category" TEXT,
    "tier" TEXT,
    "amountPaise" INTEGER NOT NULL,
    "currency" TEXT NOT NULL DEFAULT 'INR',
    "gateway" TEXT NOT NULL DEFAULT 'STUB',
    "gatewayOrderId" TEXT,
    "gatewayPaymentId" TEXT,
    "status" TEXT NOT NULL DEFAULT 'CREATED',
    "entitlementGranted" BOOLEAN NOT NULL DEFAULT false,
    "settlementNote" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Order_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "DeviceToken" (
    "token" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "platform" TEXT NOT NULL,
    "provider" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastSeenAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DeviceToken_pkey" PRIMARY KEY ("token")
);

-- CreateTable
CREATE TABLE "NotificationLog" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "kind" TEXT NOT NULL,
    "dedupeKey" TEXT NOT NULL,
    "title" TEXT,
    "body" TEXT,
    "route" TEXT,
    "delivered" INTEGER NOT NULL DEFAULT 0,
    "sentAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "NotificationLog_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "AppConfig" (
    "id" INTEGER NOT NULL DEFAULT 1,
    "minSupportedAppVersion" TEXT NOT NULL DEFAULT '1.0.0',
    "maintenanceActive" BOOLEAN NOT NULL DEFAULT false,
    "maintenanceMessage" TEXT,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "AppConfig_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "BibScan" (
    "id" TEXT NOT NULL,
    "registrationId" TEXT NOT NULL,
    "scannerId" TEXT NOT NULL,
    "tokenKind" TEXT NOT NULL,
    "scannedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "BibScan_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "User_firebaseUid_key" ON "User"("firebaseUid");

-- CreateIndex
CREATE INDEX "User_email_idx" ON "User"("email");

-- CreateIndex
CREATE INDEX "User_phone_idx" ON "User"("phone");

-- CreateIndex
CREATE UNIQUE INDEX "YogaSubscription_userId_key" ON "YogaSubscription"("userId");

-- CreateIndex
CREATE INDEX "YogaSubscription_status_expiresAt_idx" ON "YogaSubscription"("status", "expiresAt");

-- CreateIndex
CREATE INDEX "YogaSubscription_reminderSlotId_idx" ON "YogaSubscription"("reminderSlotId");

-- CreateIndex
CREATE INDEX "YogaBatch_period_sortOrder_idx" ON "YogaBatch"("period", "sortOrder");

-- CreateIndex
CREATE INDEX "YogaSession_categoryId_idx" ON "YogaSession"("categoryId");

-- CreateIndex
CREATE INDEX "YogaSession_isFree_idx" ON "YogaSession"("isFree");

-- CreateIndex
CREATE INDEX "Attendance_userId_date_idx" ON "Attendance"("userId", "date");

-- CreateIndex
CREATE UNIQUE INDEX "Attendance_userId_date_key" ON "Attendance"("userId", "date");

-- CreateIndex
CREATE INDEX "MarathonEvent_startsAt_idx" ON "MarathonEvent"("startsAt");

-- CreateIndex
CREATE UNIQUE INDEX "RaceDistanceOption_eventId_code_key" ON "RaceDistanceOption"("eventId", "code");

-- CreateIndex
CREATE UNIQUE INDEX "MarathonRegistration_registrationRef_key" ON "MarathonRegistration"("registrationRef");

-- CreateIndex
CREATE INDEX "MarathonRegistration_eventId_status_idx" ON "MarathonRegistration"("eventId", "status");

-- CreateIndex
CREATE UNIQUE INDEX "MarathonRegistration_userId_eventId_key" ON "MarathonRegistration"("userId", "eventId");

-- CreateIndex
CREATE UNIQUE INDEX "RaceResult_registrationId_key" ON "RaceResult"("registrationId");

-- CreateIndex
CREATE INDEX "RaceResult_published_updatedAt_idx" ON "RaceResult"("published", "updatedAt");

-- CreateIndex
CREATE UNIQUE INDEX "KitDelivery_registrationId_key" ON "KitDelivery"("registrationId");

-- CreateIndex
CREATE UNIQUE INDEX "Referral_userId_key" ON "Referral"("userId");

-- CreateIndex
CREATE UNIQUE INDEX "Referral_code_key" ON "Referral"("code");

-- CreateIndex
CREATE UNIQUE INDEX "ReferralCredit_orderId_key" ON "ReferralCredit"("orderId");

-- CreateIndex
CREATE UNIQUE INDEX "ReferralCredit_referralId_referredUserId_key" ON "ReferralCredit"("referralId", "referredUserId");

-- CreateIndex
CREATE INDEX "IdentityConflict_resolvedAt_createdAt_idx" ON "IdentityConflict"("resolvedAt", "createdAt");

-- CreateIndex
CREATE INDEX "LiveWorkshop_startsAt_idx" ON "LiveWorkshop"("startsAt");

-- CreateIndex
CREATE UNIQUE INDEX "WorkshopRegistration_userId_workshopId_key" ON "WorkshopRegistration"("userId", "workshopId");

-- CreateIndex
CREATE INDEX "RunRecord_userId_startedAt_idx" ON "RunRecord"("userId", "startedAt");

-- CreateIndex
CREATE INDEX "DietLead_createdAt_idx" ON "DietLead"("createdAt");

-- CreateIndex
CREATE INDEX "DietLead_forwardedAt_createdAt_idx" ON "DietLead"("forwardedAt", "createdAt");

-- CreateIndex
CREATE INDEX "Article_publishedAt_idx" ON "Article"("publishedAt");

-- CreateIndex
CREATE UNIQUE INDEX "PromoCampaign_campaignId_key" ON "PromoCampaign"("campaignId");

-- CreateIndex
CREATE INDEX "PromoCampaign_active_priority_idx" ON "PromoCampaign"("active", "priority");

-- CreateIndex
CREATE INDEX "Order_userId_status_idx" ON "Order"("userId", "status");

-- CreateIndex
CREATE INDEX "Order_gatewayOrderId_idx" ON "Order"("gatewayOrderId");

-- CreateIndex
CREATE INDEX "DeviceToken_userId_idx" ON "DeviceToken"("userId");

-- CreateIndex
CREATE INDEX "NotificationLog_sentAt_idx" ON "NotificationLog"("sentAt");

-- CreateIndex
CREATE INDEX "NotificationLog_kind_dedupeKey_idx" ON "NotificationLog"("kind", "dedupeKey");

-- CreateIndex
CREATE UNIQUE INDEX "NotificationLog_userId_kind_dedupeKey_key" ON "NotificationLog"("userId", "kind", "dedupeKey");

-- CreateIndex
CREATE INDEX "BibScan_registrationId_scannedAt_idx" ON "BibScan"("registrationId", "scannedAt");

-- AddForeignKey
ALTER TABLE "YogaSubscription" ADD CONSTRAINT "YogaSubscription_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "YogaBatch" ADD CONSTRAINT "YogaBatch_instructorId_fkey" FOREIGN KEY ("instructorId") REFERENCES "Instructor"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "YogaSession" ADD CONSTRAINT "YogaSession_categoryId_fkey" FOREIGN KEY ("categoryId") REFERENCES "YogaCategory"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "YogaSession" ADD CONSTRAINT "YogaSession_instructorId_fkey" FOREIGN KEY ("instructorId") REFERENCES "Instructor"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Attendance" ADD CONSTRAINT "Attendance_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Attendance" ADD CONSTRAINT "Attendance_batchId_fkey" FOREIGN KEY ("batchId") REFERENCES "YogaBatch"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "SavedSession" ADD CONSTRAINT "SavedSession_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "SavedSession" ADD CONSTRAINT "SavedSession_sessionId_fkey" FOREIGN KEY ("sessionId") REFERENCES "YogaSession"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "CompletedSession" ADD CONSTRAINT "CompletedSession_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "CompletedSession" ADD CONSTRAINT "CompletedSession_sessionId_fkey" FOREIGN KEY ("sessionId") REFERENCES "YogaSession"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "RaceDistanceOption" ADD CONSTRAINT "RaceDistanceOption_eventId_fkey" FOREIGN KEY ("eventId") REFERENCES "MarathonEvent"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MarathonRegistration" ADD CONSTRAINT "MarathonRegistration_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MarathonRegistration" ADD CONSTRAINT "MarathonRegistration_eventId_fkey" FOREIGN KEY ("eventId") REFERENCES "MarathonEvent"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "RaceResult" ADD CONSTRAINT "RaceResult_registrationId_fkey" FOREIGN KEY ("registrationId") REFERENCES "MarathonRegistration"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "KitDelivery" ADD CONSTRAINT "KitDelivery_registrationId_fkey" FOREIGN KEY ("registrationId") REFERENCES "MarathonRegistration"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "RaceFaq" ADD CONSTRAINT "RaceFaq_eventId_fkey" FOREIGN KEY ("eventId") REFERENCES "MarathonEvent"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Referral" ADD CONSTRAINT "Referral_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ReferralCredit" ADD CONSTRAINT "ReferralCredit_referralId_fkey" FOREIGN KEY ("referralId") REFERENCES "Referral"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ReferralCredit" ADD CONSTRAINT "ReferralCredit_referredUserId_fkey" FOREIGN KEY ("referredUserId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "IdentityConflict" ADD CONSTRAINT "IdentityConflict_newUserId_fkey" FOREIGN KEY ("newUserId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WorkshopRegistration" ADD CONSTRAINT "WorkshopRegistration_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WorkshopRegistration" ADD CONSTRAINT "WorkshopRegistration_workshopId_fkey" FOREIGN KEY ("workshopId") REFERENCES "LiveWorkshop"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "RunRecord" ADD CONSTRAINT "RunRecord_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "DietLead" ADD CONSTRAINT "DietLead_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Reel" ADD CONSTRAINT "Reel_instructorId_fkey" FOREIGN KEY ("instructorId") REFERENCES "Instructor"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Order" ADD CONSTRAINT "Order_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "DeviceToken" ADD CONSTRAINT "DeviceToken_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "NotificationLog" ADD CONSTRAINT "NotificationLog_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "BibScan" ADD CONSTRAINT "BibScan_registrationId_fkey" FOREIGN KEY ("registrationId") REFERENCES "MarathonRegistration"("id") ON DELETE CASCADE ON UPDATE CASCADE;

