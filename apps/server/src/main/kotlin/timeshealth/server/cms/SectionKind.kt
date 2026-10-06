package timeshealth.server.cms

/**
 * What can fill a page section (FeedSection.kind). The admin dashboard lists these; the feed
 * builder (feed/HomeFeedService.kt) turns each into one app component.
 *
 * ADDING A KIND: add an entry here, then one branch in HomeFeedService.buildSection. The
 * dashboard picks it up from GET /admin/api/meta automatically.
 *
 * @property itemType for CURATED kinds: what admins pick items from (FeedSectionItem.refType).
 * @property singleton at most one section of this kind per page (the hero, the promo strip).
 * @property usesCategory the section shows one category's sessions (FeedSection.categoryId).
 */
enum class SectionKind(
    val label: String,
    val help: String,
    val itemType: String? = null,
    val singleton: Boolean = false,
    val usesCategory: Boolean = false,
) {
    HERO(
        "Hero cards",
        "The top cards: today's live class for members, the user's race, or what to buy next. " +
            "Chosen per user by fixed rules; keep it first.",
        singleton = true,
    ),
    PERSONALISED_RAIL(
        "Recommended for you",
        "Sessions picked from the category that matches each user's onboarding concern or goal, " +
            "with a matching heading (e.g. \"Sessions for lower back relief\").",
        singleton = true,
    ),
    CATEGORY_RAIL(
        "Category rail",
        "Sessions from one category, in the category's order.",
        usesCategory = true,
    ),
    FREE_SESSIONS("Free sessions", "Sessions marked free, playable by everyone."),
    CURATED_VIDEOS("Hand-picked videos", "Sessions you choose and order yourself.", itemType = "SESSION"),
    LIVE_CLASSES("Upcoming live classes", "Scheduled live classes (premieres), soonest first."),
    PROMO(
        "Promo strip",
        "One active promo campaign, skipping products the user already has or the hero is selling.",
        singleton = true,
    ),
    RUN_TRACKER_TILE("Run tracker tile", "A tile that opens the GPS run tracker. Uses the title, subtitle and image."),
    WORKSHOPS("Live workshops", "Upcoming workshops with price, seats left and registration state."),
    INSTRUCTORS("Instructor reels", "Short instructor videos, in reel order."),
    ARTICLES("Articles (TOI & ET)", "News coverage, in article order."),
    TESTIMONIALS("Testimonials", "Member quotes, in quote order."),
    ;

    companion object {
        fun of(value: String?): SectionKind? = entries.firstOrNull { it.name == value }
    }
}

/** The app pages whose layout comes from FeedSection rows. */
enum class SectionPage { HOME }

/** Who a section is shown to (FeedSection.audience). */
enum class SectionAudience(val label: String) {
    ALL("Everyone"),
    MEMBERS("Yoga members only"),
    NON_MEMBERS("Non-members only"),
    ;

    companion object {
        fun of(value: String?): SectionAudience = entries.firstOrNull { it.name == value } ?: ALL
    }
}
