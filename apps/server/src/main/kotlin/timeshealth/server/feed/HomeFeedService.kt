package timeshealth.server.feed

import java.time.Clock
import java.time.Instant
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import timeshealth.app.core.domain.greetingFor
import timeshealth.app.core.model.ArticleRailComponent
import timeshealth.app.core.model.Article
import timeshealth.app.core.model.EntryTileComponent
import timeshealth.app.core.model.FeedAction
import timeshealth.app.core.model.FeedComponent
import timeshealth.app.core.model.HeroSellMarathon
import timeshealth.app.core.model.HeroSellYoga
import timeshealth.app.core.model.HeroSlot
import timeshealth.app.core.model.HeroStackComponent
import timeshealth.app.core.model.HomeFeedResponse
import timeshealth.app.core.model.Instructor
import timeshealth.app.core.model.LiveClassRailComponent
import timeshealth.app.core.model.PromoStripComponent
import timeshealth.app.core.model.QuoteRailComponent
import timeshealth.app.core.model.RaceLifecycleStatus
import timeshealth.app.core.model.Reel
import timeshealth.app.core.model.ReelRailComponent
import timeshealth.app.core.model.UserQuote
import timeshealth.app.core.model.VideoRailComponent
import timeshealth.app.core.model.VideoSource
import timeshealth.app.core.model.WorkshopRailComponent
import timeshealth.app.core.model.YogaSession
import timeshealth.server.cms.ContentRepository
import timeshealth.server.cms.SectionAudience
import timeshealth.server.cms.SectionKind
import timeshealth.server.cms.SectionPage
import timeshealth.server.cms.SectionRow
import timeshealth.server.cms.SessionRow
import timeshealth.server.db.entity.UserEntity
import timeshealth.server.json.ServerJson
import timeshealth.server.json.toIsoString
import timeshealth.server.media.MediaSigning
import timeshealth.server.session.EntitlementService
import timeshealth.server.session.ResolvedEntitlements
import timeshealth.server.yoga.AttendanceService
import timeshealth.server.yoga.LiveClassService

/**
 * GET /v1/home, built from the admin CMS (port of services/feed.ts `buildHomeFeed`).
 *
 * The page is the HOME [SectionRow]s in their admin-set order. Each visible, in-schedule section
 * for this user's audience becomes one app component; a section with nothing to show is left
 * out (§6.3: no empty states in the feed). What fills each section is decided per [SectionKind].
 *
 * With no HOME sections at all (a database the CMS migration has not seeded), the layout falls
 * back to [DEFAULT_LAYOUT], the order the feed had before it became editable.
 */
@Service
class HomeFeedService(
    private val content: ContentRepository,
    private val heroStack: HeroStack,
    private val entitlements: EntitlementService,
    private val attendance: AttendanceService,
    private val workshops: Workshops,
    private val media: MediaSigning,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(HomeFeedService::class.java)

    fun build(user: UserEntity): HomeFeedResponse {
        val now = clock.instant()
        val ctx = Context(user, entitlements.resolve(user.id, now), now)
        val sections = content.sections(SectionPage.HOME.name).ifEmpty { DEFAULT_LAYOUT }
            .filter { it.isShowingAt(now) && audienceMatches(it, ctx.entitled) }

        val components = sections.mapNotNull { section ->
            try {
                buildSection(section, ctx)
            } catch (e: Exception) {
                // One broken section (bad category id, malformed promo action) must not take
                // Home down for everyone; it is skipped and logged for the admin to fix.
                log.error("home section skipped id={} kind={} cause={}", section.id, section.kind, e.toString())
                null
            }
        }
        return HomeFeedResponse(
            greeting = greetingFor(now),
            userName = user.name,
            components = components,
            serverTime = now.toIsoString(),
            ttlSeconds = 60,
        )
    }

    /** Per-request state, computed once and only when a section needs it. */
    private inner class Context(val user: UserEntity, val resolved: ResolvedEntitlements, val now: Instant) {
        val entitled: Boolean get() = resolved.persona.hasYoga

        val hero: List<HeroSlot> by lazy {
            val ent = resolved.entitlements
            // The renew prompt quotes the real best streak, not a count of classes.
            val streak = if (!resolved.persona.hasYoga && EntitlementService.shouldShowRenewPrompt(ent)) {
                attendance.bestStreak(user.id)
            } else {
                0
            }
            heroStack.build(user.id, ent, resolved.persona, heroStack.todaySchedule(now), streak, now)
        }
    }

    private fun buildSection(s: SectionRow, ctx: Context): FeedComponent? =
        when (SectionKind.of(s.kind)) {
            SectionKind.HERO -> HeroStackComponent(id = s.id, slots = ctx.hero)

            SectionKind.PERSONALISED_RAIL -> {
                val rail = resolveConcernRail(ctx.user.concern, ctx.user.healthGoal)
                val categories = content.categories()
                val primary = categories.firstOrNull { it.id == rail.categoryId } ?: categories.firstOrNull()
                primary?.let {
                    videoRail(s, rail.heading, content.sessionsInCategory(it.id, s.maxItems), ctx, it.id, s.actionLabel ?: "See all")
                }
            }

            SectionKind.CATEGORY_RAIL -> s.categoryId
                ?.takeIf { id -> content.categories().any { it.id == id } }
                ?.let { videoRail(s, s.title, content.sessionsInCategory(it, s.maxItems), ctx, it, s.actionLabel) }

            SectionKind.FREE_SESSIONS -> videoRail(s, s.title, content.freeSessions(s.maxItems), ctx, null, s.actionLabel)

            SectionKind.CURATED_VIDEOS -> {
                val ids = content.sectionItems(s.id).filter { it.refType == "SESSION" }.map { it.refId }
                videoRail(s, s.title, content.sessionsByIds(ids).take(s.maxItems), ctx, null, s.actionLabel)
            }

            SectionKind.LIVE_CLASSES -> content.liveClasses(ctx.now, ctx.now.plus(LiveClassService.LIST_AHEAD))
                .filter { !it.cancelled }
                .take(s.maxItems)
                .map { LiveClassService.card(it, ctx.entitled, ctx.now) }
                .takeIf { it.isNotEmpty() }
                ?.let { LiveClassRailComponent(id = s.id, title = s.title, items = it, actionLabel = s.actionLabel) }

            SectionKind.PROMO -> promo(s, ctx)

            SectionKind.RUN_TRACKER_TILE -> EntryTileComponent(
                id = s.id,
                title = s.title,
                subtitle = s.subtitle.orEmpty(),
                imageUrl = s.imageUrl,
                action = FeedAction.OpenRunTracker,
            )

            SectionKind.WORKSHOPS -> workshops.loadFor(ctx.user.id, ctx.entitled, ctx.now)
                .take(s.maxItems)
                .takeIf { it.isNotEmpty() }
                ?.let { WorkshopRailComponent(id = s.id, title = s.title, items = it) }

            SectionKind.INSTRUCTORS -> content.reels(s.maxItems)
                .map { Reel(it.id, it.instructorId, it.instructorName, it.instructorHandle.orEmpty(), it.thumbnailUrl, it.playbackUrl, it.durationSeconds) }
                .takeIf { it.isNotEmpty() }
                ?.let { ReelRailComponent(id = s.id, title = s.title, items = it) }

            SectionKind.ARTICLES -> content.articles(s.maxItems)
                .map { Article(it.id, it.title, it.source, it.category, it.imageUrl, it.readTimeMinutes, it.url) }
                .takeIf { it.isNotEmpty() }
                ?.let { ArticleRailComponent(id = s.id, title = s.title, items = it) }

            SectionKind.TESTIMONIALS -> content.quotes(s.maxItems)
                .map { UserQuote(it.id, it.quote, it.author, it.role) }
                .takeIf { it.isNotEmpty() }
                ?.let { QuoteRailComponent(id = s.id, title = s.title, items = it) }

            // A kind this server version doesn't know (added by a newer dashboard): skip it.
            null -> null
        }

    private fun videoRail(
        s: SectionRow,
        title: String,
        sessions: List<SessionRow>,
        ctx: Context,
        seeAllCategoryId: String?,
        actionLabel: String?,
    ): FeedComponent? {
        if (sessions.isEmpty()) return null
        return VideoRailComponent(
            id = s.id,
            title = title,
            items = sessions.map { toSessionDto(it, ctx.user.id, ctx.entitled, ctx.now) },
            seeAllCategoryId = seeAllCategoryId,
            actionLabel = actionLabel,
        )
    }

    /**
     * §6.2: one campaign, carrying whatever the hero isn't already selling, and never a product
     * the user already holds (a registered runner being sold their own race).
     */
    private fun promo(s: SectionRow, ctx: Context): FeedComponent? {
        val alreadySelling = ctx.hero.mapTo(mutableSetOf()) {
            when (it) {
                is HeroSellYoga -> "YOGA"
                is HeroSellMarathon -> "MARATHON"
                else -> "NONE"
            }
        }
        if (ctx.resolved.persona.hasYoga) alreadySelling += "YOGA"
        if (ctx.resolved.entitlements.marathon.any { it.status != RaceLifecycleStatus.COMPLETED }) alreadySelling += "MARATHON"

        val promos = content.activePromos(ctx.now)
        val strip = promos.firstOrNull { it.sellsProduct !in alreadySelling }
            ?: promos.firstOrNull { it.sellsProduct == "DIET" }
            ?: return null
        return PromoStripComponent(
            id = "promo-${strip.campaignId}",
            campaignId = strip.campaignId,
            title = strip.title,
            subtitle = strip.subtitle,
            ctaLabel = strip.ctaLabel,
            imageUrl = strip.imageUrl,
            backgroundColor = strip.backgroundColor,
            action = ServerJson.decodeFromJsonElement<FeedAction>(strip.action),
        )
    }

    private fun toSessionDto(s: SessionRow, userId: String, entitled: Boolean, now: Instant): YogaSession {
        // §6.3: free items play for anyone; paid items need entitlement (a free user tapping a
        // paid card gets the paywall, never a dead tap).
        val playable = s.isFree || entitled
        val cmsVideo = if (playable && s.videoProvider != null && s.videoRef != null) {
            VideoSource(s.videoProvider, s.videoRef)
        } else {
            null
        }
        val playbackUrl = when {
            !playable -> null
            s.mediaKey != null -> media.signPlaybackUrl(s.mediaKey, userId, now)
            // A plain stream URL set in the dashboard plays directly in older app builds too.
            cmsVideo?.provider == "url" -> cmsVideo.ref
            else -> null
        }
        return YogaSession(
            id = s.id,
            categoryId = s.categoryId,
            title = s.title,
            description = s.description,
            imageUrl = s.imageUrl,
            durationMinutes = s.durationMinutes,
            level = s.level,
            intensity = s.intensity,
            caloriesBurned = s.caloriesBurned,
            isFree = s.isFree,
            isLive = false,
            startsAt = null,
            bodyFocusTitle = s.bodyFocusTitle,
            targetBodyParts = s.targetBodyParts,
            keyPoses = s.keyPoses,
            lifestyleImpact = s.lifestyleImpact,
            instructor = s.instructor.let {
                Instructor(it.id, it.name, it.title, it.specialty, it.bio, it.experience, it.avatarUrl, it.rating, it.handle, it.reelCount)
            },
            joinedCountTillDate = s.joinedCountTillDate,
            todayActiveCount = s.todayActiveCount,
            playbackUrl = playbackUrl,
            video = cmsVideo,
        )
    }

    private fun audienceMatches(s: SectionRow, entitled: Boolean) = when (SectionAudience.of(s.audience)) {
        SectionAudience.ALL -> true
        SectionAudience.MEMBERS -> entitled
        SectionAudience.NON_MEMBERS -> !entitled
    }

    data class ConcernRail(val categoryId: String, val heading: String)

    companion object {
        /**
         * Concern → rail heading and category. Headings are the design's copy (HomeScreen.kt);
         * every onboarding concern maps explicitly (the prototype's substring match silently
         * sent 4 of 6 to the default).
         */
        private val CONCERN_RAIL = mapOf(
            "LOWER_BACK" to ConcernRail("cat_spine", "Sessions for lower back relief"),
            "NECK_SHOULDERS" to ConcernRail("cat_desk", "Sessions for neck & shoulder release"),
            "KNEES_JOINTS" to ConcernRail("cat_flex", "Sessions for knee & joint stability"),
            "HIPS_PELVIS" to ConcernRail("cat_flex", "Sessions for hip & pelvic release"),
            "SLEEP_ENERGY" to ConcernRail("cat_sleep", "Sessions for deep evening sleep"),
        )

        /** With no specific concern, the onboarding goal picks the rail. */
        private val GOAL_RAIL = mapOf(
            "STRESS_ANXIETY" to ConcernRail("cat_sleep", "Sessions for calm & stress release"),
            "WEIGHT_LOSS" to ConcernRail("cat_core", "Sessions for weight loss & agility"),
            "STRENGTH_FLEXIBILITY" to ConcernRail("cat_flex", "Sessions for weight loss & agility"),
            "MARATHON_TRAINING" to ConcernRail("cat_flex", "Sessions for knee & joint stability"),
        )

        private val DEFAULT_RAIL = ConcernRail("cat_morning", "Sessions for weight loss & agility")

        fun resolveConcernRail(concern: String?, goal: String?): ConcernRail =
            concern?.let(CONCERN_RAIL::get) ?: goal?.let(GOAL_RAIL::get) ?: DEFAULT_RAIL

        private fun section(id: String, kind: SectionKind, title: String, sort: Int, max: Int = 10, action: String? = null) =
            SectionRow(id, "HOME", kind.name, title, null, action, null, null, max, sort, true, "ALL", null, null)

        /** The pre-CMS layout; the V2 migration seeds the same rows. */
        val DEFAULT_LAYOUT: List<SectionRow> = listOf(
            section("sec_home_hero", SectionKind.HERO, "Today (hero cards)", 10, 2),
            section("sec_home_for_you", SectionKind.PERSONALISED_RAIL, "Recommended for you", 20, 4, "See all"),
            section("sec_home_promo", SectionKind.PROMO, "Promo strip", 30, 1),
            section("sec_home_free", SectionKind.FREE_SESSIONS, "Free yoga sessions", 40, 6, "Explore"),
            section("sec_home_live", SectionKind.LIVE_CLASSES, "Upcoming live classes", 50, 6),
            section("sec_home_run", SectionKind.RUN_TRACKER_TILE, "GPS Outdoor Run", 60, 1).copy(
                subtitle = "Track live distance, pace & route — even with the screen off",
                imageUrl = "https://images.unsplash.com/photo-1571008887538-b36bb32f4571?auto=format&fit=crop&w=900&q=70",
            ),
            section("sec_home_workshops", SectionKind.WORKSHOPS, "Upcoming Live Workshops", 70),
            section("sec_home_instructors", SectionKind.INSTRUCTORS, "Explore our instructors", 80),
            section("sec_home_articles", SectionKind.ARTICLES, "TOI & ET coverage", 90, 8),
            section("sec_home_testimonials", SectionKind.TESTIMONIALS, "What our members say", 100, 8),
        )
    }
}
