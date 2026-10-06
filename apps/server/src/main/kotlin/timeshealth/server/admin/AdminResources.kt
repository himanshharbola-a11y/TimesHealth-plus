package timeshealth.server.admin

import timeshealth.server.cms.SectionAudience
import timeshealth.server.cms.SectionKind
import timeshealth.server.cms.SectionPage

/**
 * Everything the dashboard can edit, declared once. The admin API (AdminCrud) and the dashboard
 * screens are generated from these declarations, so:
 *
 * ADDING AN EDITABLE TABLE = one [AdminResource] entry in [AdminResources.ALL]. ADDING A COLUMN to
 * an existing one = one [AdminField]. No controller, SQL or dashboard code changes.
 *
 * Column names are Prisma's (quoted identifiers). Values only ever reach SQL as bound
 * parameters; names only ever come from these declarations, never from a request.
 */
enum class FieldType {
    TEXT, LONGTEXT, URL, IMAGE, INT, FLOAT, BOOL, DATETIME,

    /** "HH:mm", 24-hour, IST (yoga batch times). */
    TIME,

    /** One of [AdminField.options]. */
    SELECT,

    /** Another resource's id, picked by name ([AdminField.ref]). */
    REF,

    /** A list of short strings (Postgres text[]). */
    TAGS,

    /** A JSON object (jsonb). */
    JSON,

    /** Money in paise, shown and typed in rupees by the dashboard. */
    PAISE,
}

data class AdminOption(val value: String, val label: String)

data class AdminField(
    val name: String,
    val label: String,
    val type: FieldType,
    val required: Boolean = false,
    val help: String? = null,
    val options: List<AdminOption> = emptyList(),
    /** For [FieldType.REF]: the resource key it points to. */
    val ref: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val maxLength: Int = 2_000,
    /** Shown as a column in the dashboard's list view. */
    val list: Boolean = false,
    /** Lists can be filtered by this field (?f.<name>=value). */
    val filter: Boolean = false,
    /** Value used by the "New" form. */
    val default: Any? = null,
    /** Shown but never written (system-set columns, or values from a joined [AdminResource.source]). */
    val readOnly: Boolean = false,
)

data class AdminResource(
    /** URL key: /admin/api/<key>. */
    val key: String,
    val label: String,
    val group: String,
    val description: String,
    /** Prisma model (table) name. */
    val table: String,
    val fields: List<AdminField>,
    /** The field shown as the row's name in lists and pickers. */
    val titleField: String,
    /** ORDER BY for lists (trusted SQL from this file). */
    val orderBy: String,
    /** Enables drag-to-reorder: the column written as 10, 20, 30… */
    val sortField: String? = null,
    val hasCreatedAt: Boolean = false,
    val hasUpdatedAt: Boolean = false,
    /** For tables whose id isn't a cuid (AppConfig): rows can't be created or deleted. */
    val singleton: Boolean = false,
    val canCreate: Boolean = true,
    val canDelete: Boolean = true,
    /** Only OWNERs may read or write it. */
    val ownerOnly: Boolean = false,
    /**
     * Trusted SQL the reads come from instead of the bare table, to show joined values (a
     * registration's runner name). Must select every column of [table]; writes still go to
     * [table], and joined columns are declared [AdminField.readOnly].
     */
    val source: String? = null,
) {
    fun field(name: String): AdminField? = fields.firstOrNull { it.name == name }
}

object AdminResources {
    private fun opts(vararg pairs: Pair<String, String>) = pairs.map { AdminOption(it.first, it.second) }
    private fun opts(values: List<String>) = values.map { AdminOption(it, it) }
    private fun opts(vararg values: String) = values.map { AdminOption(it, it) }

    private val VIDEO_PROVIDERS = opts(
        "url" to "Stream URL (HLS .m3u8 or MP4)",
        "slike" to "Slike media id",
    )
    private val CONCERNS = opts(
        "LOWER_BACK" to "Lower back", "NECK_SHOULDERS" to "Neck & shoulders", "KNEES_JOINTS" to "Knees & joints",
        "HIPS_PELVIS" to "Hips & pelvis", "SLEEP_ENERGY" to "Sleep & energy",
    )
    private val PRODUCTS = opts("YOGA" to "Yoga", "MARATHON" to "Marathon", "DIET" to "Diet", "OTHER" to "Other")

    private fun sort(label: String = "Order") =
        AdminField("sortOrder", label, FieldType.INT, default = 0, help = "Lower comes first. Drag rows in the list to reorder.")

    val SECTIONS = AdminResource(
        key = "sections",
        label = "Home sections",
        group = "App pages",
        description = "The sections of the app's Home page, top to bottom. Add, rename, reorder, hide or schedule them; " +
            "changes are live in the app within a minute.",
        table = "FeedSection",
        titleField = "title",
        orderBy = """"page", "sortOrder", "id"""",
        sortField = "sortOrder",
        hasCreatedAt = true,
        hasUpdatedAt = true,
        fields = listOf(
            AdminField("page", "Page", FieldType.SELECT, required = true, options = opts(SectionPage.entries.map { it.name }), default = "HOME", filter = true),
            AdminField(
                "kind", "Type", FieldType.SELECT, required = true, list = true,
                options = SectionKind.entries.map { AdminOption(it.name, it.label) },
                help = "What fills the section. See the description of each type below the form.",
            ),
            AdminField("title", "Heading", FieldType.TEXT, required = true, list = true, maxLength = 80),
            AdminField("subtitle", "Subtitle", FieldType.TEXT, maxLength = 160, help = "Used by tiles."),
            AdminField("actionLabel", "Header link", FieldType.TEXT, maxLength = 24, help = "e.g. \"See all\". Empty: no link."),
            AdminField("imageUrl", "Image", FieldType.IMAGE, help = "Used by tiles."),
            AdminField("categoryId", "Category", FieldType.REF, ref = "categories", help = "Category rails only."),
            AdminField("maxItems", "Max items", FieldType.INT, min = 1.0, max = 30.0, default = 10),
            AdminField(
                "audience", "Shown to", FieldType.SELECT, list = true,
                options = SectionAudience.entries.map { AdminOption(it.name, it.label) }, default = "ALL",
            ),
            AdminField("visible", "Visible", FieldType.BOOL, list = true, default = true),
            AdminField("startsAt", "Show from", FieldType.DATETIME, help = "Optional schedule."),
            AdminField("endsAt", "Show until", FieldType.DATETIME),
            sort(),
        ),
    )

    val CATEGORIES = AdminResource(
        key = "categories",
        label = "Yoga categories",
        group = "Yoga",
        description = "Session categories (e.g. Spine care). Hidden categories and their sessions leave the app.",
        table = "YogaCategory",
        titleField = "name",
        orderBy = """"sortOrder", "id"""",
        sortField = "sortOrder",
        fields = listOf(
            AdminField("name", "Name", FieldType.TEXT, required = true, list = true, maxLength = 60),
            AdminField("tagline", "Tagline", FieldType.TEXT, required = true, maxLength = 120),
            AdminField("bodyTargetSummary", "Body focus summary", FieldType.TEXT, required = true, maxLength = 200),
            AdminField("imageUrl", "Image", FieldType.IMAGE, required = true),
            AdminField("bannerTheme", "Banner theme", FieldType.SELECT, options = opts("NEUTRAL", "AMBER", "CORAL", "EMERALD", "OCEAN", "PLUM", "SAGE"), default = "NEUTRAL"),
            AdminField("totalYogisJoined", "Yogis joined", FieldType.INT, min = 0.0, default = 0),
            AdminField("concernTag", "Matches concern", FieldType.SELECT, options = CONCERNS, help = "Feeds \"Recommended for you\"."),
            AdminField("visible", "Visible", FieldType.BOOL, list = true, default = true),
            sort(),
        ),
    )

    val SESSIONS = AdminResource(
        key = "sessions",
        label = "Videos (sessions)",
        group = "Yoga",
        description = "Recorded yoga sessions. Free ones play for everyone; the rest need the membership. " +
            "Set the video as a Slike id or a stream URL.",
        table = "YogaSession",
        titleField = "title",
        orderBy = """"categoryId", "sortOrder", "id"""",
        sortField = "sortOrder",
        fields = listOf(
            AdminField("title", "Title", FieldType.TEXT, required = true, list = true, maxLength = 100),
            AdminField("categoryId", "Category", FieldType.REF, ref = "categories", required = true, list = true, filter = true),
            AdminField("instructorId", "Instructor", FieldType.REF, ref = "instructors", required = true, list = true),
            AdminField("isFree", "Free for everyone", FieldType.BOOL, list = true, default = false),
            AdminField("visible", "Visible", FieldType.BOOL, list = true, default = true),
            AdminField("videoProvider", "Video source", FieldType.SELECT, options = VIDEO_PROVIDERS, default = "url"),
            AdminField("videoRef", "Video (Slike id or URL)", FieldType.TEXT, maxLength = 500),
            AdminField("imageUrl", "Thumbnail", FieldType.IMAGE, required = true),
            AdminField("description", "Description", FieldType.LONGTEXT, required = true),
            AdminField("durationMinutes", "Minutes", FieldType.INT, required = true, min = 1.0, max = 600.0, list = true),
            AdminField("level", "Level", FieldType.TEXT, required = true, maxLength = 40, help = "e.g. Beginner, All Levels"),
            AdminField("intensity", "Intensity", FieldType.TEXT, required = true, maxLength = 40, help = "e.g. Gentle, Moderate, Strong"),
            AdminField("caloriesBurned", "Calories", FieldType.INT, min = 0.0, default = 0),
            AdminField("bodyFocusTitle", "Body focus", FieldType.TEXT, required = true, maxLength = 100),
            AdminField("targetBodyParts", "Target body parts", FieldType.TAGS),
            AdminField("keyPoses", "Key poses", FieldType.TAGS),
            AdminField("lifestyleImpact", "Lifestyle impact", FieldType.LONGTEXT, required = true),
            AdminField("joinedCountTillDate", "Joined (count)", FieldType.INT, min = 0.0, default = 0),
            AdminField("todayActiveCount", "Active today (count)", FieldType.INT, min = 0.0, default = 0),
            AdminField("mediaKey", "Media key (legacy)", FieldType.TEXT, help = "Old signed-CDN key. Leave empty when a video source is set."),
            sort("Order in category"),
        ),
    )

    val INSTRUCTORS = AdminResource(
        key = "instructors",
        label = "Instructors",
        group = "Yoga",
        description = "Yoga gurus shown on classes, sessions and \"Explore our instructors\".",
        table = "Instructor",
        titleField = "name",
        orderBy = """"sortOrder", "name"""",
        sortField = "sortOrder",
        fields = listOf(
            AdminField("name", "Name", FieldType.TEXT, required = true, list = true, maxLength = 80),
            AdminField("title", "Title", FieldType.TEXT, required = true, list = true, maxLength = 80),
            AdminField("specialty", "Specialty", FieldType.TEXT, required = true, maxLength = 120),
            AdminField("bio", "Bio", FieldType.LONGTEXT, required = true),
            AdminField("experience", "Experience", FieldType.TEXT, required = true, maxLength = 60, help = "e.g. \"12 years\""),
            AdminField("avatarUrl", "Photo", FieldType.IMAGE, required = true),
            AdminField("rating", "Rating", FieldType.FLOAT, min = 0.0, max = 5.0, default = 0),
            AdminField("handle", "Social handle", FieldType.TEXT, maxLength = 60),
            AdminField("reelCount", "Reel count", FieldType.INT, min = 0.0, default = 0),
            sort(),
        ),
    )

    val BATCHES = AdminResource(
        key = "batches",
        label = "Daily batches",
        group = "Live classes",
        description = "The daily live-class timetable (IST). Members pick one for reminders and can join any.",
        table = "YogaBatch",
        titleField = "title",
        orderBy = """"time", "id"""",
        sortField = "sortOrder",
        fields = listOf(
            AdminField("title", "Name", FieldType.TEXT, required = true, list = true, maxLength = 60),
            AdminField("time", "Starts at (IST)", FieldType.TIME, required = true, list = true),
            AdminField("period", "Period", FieldType.SELECT, required = true, options = opts("MORNING" to "Morning", "EVENING" to "Evening"), list = true),
            AdminField("instructorId", "Instructor", FieldType.REF, ref = "instructors", required = true, list = true),
            AdminField("active", "On the timetable", FieldType.BOOL, list = true, default = true),
            AdminField("videoProvider", "Usual stream source", FieldType.SELECT, options = VIDEO_PROVIDERS),
            AdminField("videoRef", "Usual stream (Slike id or URL)", FieldType.TEXT, maxLength = 500, help = "Used when no live class is scheduled for the day."),
            sort(),
        ),
    )

    val LIVE_CLASSES = AdminResource(
        key = "live-classes",
        label = "Live classes (premieres)",
        group = "Live classes",
        description = "Pre-recorded classes streamed at a set time, like a YouTube premiere. Everyone watching is at the same point. " +
            "Link one to a batch to show it in members' Home hero. Use \"Schedule a week\" to create one per day.",
        table = "LiveClass",
        titleField = "title",
        orderBy = """"startsAt" DESC, "id"""",
        hasCreatedAt = true,
        hasUpdatedAt = true,
        fields = listOf(
            AdminField("title", "Title", FieldType.TEXT, required = true, list = true, maxLength = 100),
            AdminField("startsAt", "Starts at", FieldType.DATETIME, required = true, list = true),
            AdminField("durationMinutes", "Minutes", FieldType.INT, required = true, min = 5.0, max = 300.0, default = 60),
            AdminField("isFree", "Free for everyone", FieldType.BOOL, list = true, default = false),
            AdminField("status", "Status", FieldType.SELECT, options = opts("SCHEDULED" to "Scheduled", "CANCELLED" to "Cancelled"), default = "SCHEDULED", list = true),
            AdminField("videoProvider", "Video source", FieldType.SELECT, required = true, options = VIDEO_PROVIDERS, default = "url"),
            AdminField("videoRef", "Video (Slike id or URL)", FieldType.TEXT, required = true, maxLength = 500),
            AdminField("batchId", "Batch", FieldType.REF, ref = "batches", filter = true),
            AdminField("instructorId", "Instructor", FieldType.REF, ref = "instructors", list = true),
            AdminField("imageUrl", "Thumbnail", FieldType.IMAGE),
            AdminField("description", "Description", FieldType.LONGTEXT, default = ""),
        ),
    )

    val WORKSHOPS = AdminResource(
        key = "workshops",
        label = "Live workshops",
        group = "Live classes",
        description = "Bookable workshops (\"Upcoming Live Workshops\"). Empty price = free for members.",
        table = "LiveWorkshop",
        titleField = "title",
        orderBy = """"startsAt" DESC, "id"""",
        fields = listOf(
            AdminField("title", "Title", FieldType.TEXT, required = true, list = true, maxLength = 100),
            AdminField("startsAt", "Starts at", FieldType.DATETIME, required = true, list = true),
            AdminField("category", "Category", FieldType.SELECT, required = true, options = opts("YOGA" to "Yoga", "MARATHON" to "Marathon", "DIET" to "Diet"), list = true),
            AdminField("pricePaise", "Price (₹)", FieldType.PAISE, min = 0.0, help = "Empty = free for members.", list = true),
            AdminField("totalCapacity", "Seats", FieldType.INT, required = true, min = 1.0, list = true),
            AdminField("durationMinutes", "Minutes", FieldType.INT, required = true, min = 5.0, max = 600.0),
            AdminField("description", "Description", FieldType.LONGTEXT, required = true),
            AdminField("focusArea", "Focus area", FieldType.TEXT, required = true, maxLength = 80),
            AdminField("imageUrl", "Image", FieldType.IMAGE, required = true),
            AdminField("level", "Level", FieldType.TEXT, required = true, maxLength = 40, help = "e.g. Beginner, All Levels"),
            AdminField("platform", "Platform", FieldType.TEXT, required = true, maxLength = 40, default = "Zoom Interactive Live"),
            AdminField("joinUrl", "Join link", FieldType.URL, help = "Shown to registered users an hour before."),
            AdminField("instructorName", "Instructor name", FieldType.TEXT, required = true, maxLength = 80),
            AdminField("instructorTitle", "Instructor title", FieldType.TEXT, required = true, maxLength = 80),
            AdminField("instructorAvatarUrl", "Instructor photo", FieldType.IMAGE, required = true),
        ),
    )

    val ARTICLES = AdminResource(
        key = "articles",
        label = "Articles (TOI & ET)",
        group = "Content",
        description = "Press coverage shown on Home. Opens the article link.",
        table = "Article",
        titleField = "title",
        orderBy = """"sortOrder", "publishedAt" DESC""",
        sortField = "sortOrder",
        fields = listOf(
            AdminField("title", "Headline", FieldType.TEXT, required = true, list = true, maxLength = 160),
            AdminField("source", "Source", FieldType.SELECT, required = true, list = true, options = opts("THE TIMES OF INDIA", "ET DIGITAL", "ET HEALTHWORLD", "NAVBHARAT TIMES")),
            AdminField("url", "Link", FieldType.URL, required = true),
            AdminField("imageUrl", "Image", FieldType.IMAGE, required = true),
            AdminField("category", "Category", FieldType.TEXT, required = true, maxLength = 40),
            AdminField("readTimeMinutes", "Read time (min)", FieldType.INT, required = true, min = 1.0, max = 60.0),
            AdminField("concernTag", "Matches concern", FieldType.SELECT, options = CONCERNS),
            AdminField("publishedAt", "Published", FieldType.DATETIME),
            sort(),
        ),
    )

    val REELS = AdminResource(
        key = "reels",
        label = "Instructor reels",
        group = "Content",
        description = "Short portrait videos on \"Explore our instructors\".",
        table = "Reel",
        titleField = "thumbnailUrl",
        orderBy = """"sortOrder", "id"""",
        sortField = "sortOrder",
        fields = listOf(
            AdminField("instructorId", "Instructor", FieldType.REF, ref = "instructors", required = true, list = true),
            AdminField("thumbnailUrl", "Thumbnail", FieldType.IMAGE, required = true, list = true),
            AdminField("playbackUrl", "Video URL", FieldType.URL, required = true),
            AdminField("durationSeconds", "Seconds", FieldType.INT, required = true, min = 1.0, max = 600.0, list = true),
            sort(),
        ),
    )

    val TESTIMONIALS = AdminResource(
        key = "testimonials",
        label = "Testimonials",
        group = "Content",
        description = "\"What our members say\". Only publish quotes members consented to.",
        table = "UserQuote",
        titleField = "author",
        orderBy = """"sortOrder", "id"""",
        sortField = "sortOrder",
        fields = listOf(
            AdminField("quote", "Quote", FieldType.LONGTEXT, required = true, list = true, maxLength = 400),
            AdminField("author", "Name", FieldType.TEXT, required = true, list = true, maxLength = 60),
            AdminField("role", "Who they are", FieldType.TEXT, required = true, maxLength = 80, help = "e.g. \"Member since 2023\""),
            sort(),
        ),
    )

    val PROMOS = AdminResource(
        key = "promos",
        label = "Promo campaigns",
        group = "Content",
        description = "The Home promo strip. The highest-priority active campaign that isn't selling something the user " +
            "already has (or the hero is already selling) is shown.",
        table = "PromoCampaign",
        titleField = "title",
        orderBy = """"active" DESC, "priority" DESC, "id"""",
        fields = listOf(
            AdminField("campaignId", "Campaign id", FieldType.TEXT, required = true, maxLength = 60, help = "Unique, for analytics."),
            AdminField("title", "Title", FieldType.TEXT, required = true, list = true, maxLength = 80),
            AdminField("subtitle", "Subtitle", FieldType.TEXT, maxLength = 160),
            AdminField("ctaLabel", "Button", FieldType.TEXT, required = true, maxLength = 30),
            AdminField("sellsProduct", "Sells", FieldType.SELECT, required = true, options = PRODUCTS, list = true),
            AdminField(
                "action", "Button action", FieldType.JSON, required = true,
                help = "e.g. {\"type\":\"OPEN_TAB\",\"tab\":\"DIET\"}, {\"type\":\"OPEN_PAYWALL\",\"productId\":\"yoga_annual\"}, " +
                    "{\"type\":\"OPEN_EXTERNAL\",\"url\":\"https://…\"}",
                default = mapOf("type" to "OPEN_TAB", "tab" to "YOGA"),
            ),
            AdminField("imageUrl", "Image", FieldType.IMAGE),
            AdminField("backgroundColor", "Background colour", FieldType.TEXT, maxLength = 9, help = "Hex, e.g. #1B4931"),
            AdminField("active", "Active", FieldType.BOOL, list = true, default = true),
            AdminField("priority", "Priority", FieldType.INT, list = true, default = 0, help = "Higher wins."),
            AdminField("startsAt", "From", FieldType.DATETIME),
            AdminField("endsAt", "Until", FieldType.DATETIME),
        ),
    )

    val MARATHONS = AdminResource(
        key = "marathons",
        label = "Marathon editions",
        group = "Marathon",
        description = "Race editions sold in the app. Add distances and FAQs under each edition.",
        table = "MarathonEvent",
        titleField = "name",
        orderBy = """"startsAt" DESC, "id"""",
        fields = listOf(
            AdminField("name", "Name", FieldType.TEXT, required = true, list = true, maxLength = 100),
            AdminField("city", "City", FieldType.TEXT, required = true, list = true, maxLength = 60),
            AdminField("startsAt", "Race starts", FieldType.DATETIME, required = true, list = true),
            AdminField("registrationOpen", "Registration open", FieldType.BOOL, list = true, default = true),
            AdminField("venue", "Venue", FieldType.TEXT, required = true, maxLength = 160),
            AdminField("flagOffTime", "Flag-off", FieldType.TEXT, required = true, maxLength = 60, help = "e.g. \"5:30 AM · Wave 2\""),
            AdminField("imageUrl", "Image", FieldType.IMAGE, required = true),
            AdminField("rescheduledFrom", "Rescheduled from", FieldType.DATETIME, help = "Set when the date moved; the app explains it."),
            AdminField("latitude", "Latitude", FieldType.FLOAT, min = -90.0, max = 90.0),
            AdminField("longitude", "Longitude", FieldType.FLOAT, min = -180.0, max = 180.0),
            AdminField("expoVenue", "Expo venue", FieldType.TEXT, maxLength = 160),
            AdminField("expoAddress", "Expo address", FieldType.LONGTEXT),
            AdminField("expoStartsAt", "Expo opens", FieldType.DATETIME),
            AdminField("expoEndsAt", "Expo closes", FieldType.DATETIME),
            AdminField("expoPickupWindow", "Kit pickup window", FieldType.TEXT, maxLength = 120),
            AdminField("expoInstructions", "Expo instructions", FieldType.LONGTEXT),
            AdminField("expoDocuments", "Documents to bring", FieldType.TAGS),
        ),
    )

    val RACE_DISTANCES = AdminResource(
        key = "race-distances",
        label = "Race distances & prices",
        group = "Marathon",
        description = "Distances sold for each edition, with Classic and Premium prices. Prices are always taken from here, never from the app.",
        table = "RaceDistanceOption",
        titleField = "label",
        orderBy = """"eventId", "sortOrder", "id"""",
        sortField = "sortOrder",
        fields = listOf(
            AdminField("eventId", "Edition", FieldType.REF, ref = "marathons", required = true, list = true, filter = true),
            AdminField("code", "Code", FieldType.SELECT, required = true, options = opts("3K", "5K", "10K", "21K", "42K"), list = true),
            AdminField("label", "Label", FieldType.TEXT, required = true, maxLength = 60, list = true),
            AdminField("priceClassicPaise", "Classic price (₹)", FieldType.PAISE, required = true, min = 0.0, list = true),
            AdminField("pricePremiumPaise", "Premium price (₹)", FieldType.PAISE, required = true, min = 0.0, list = true),
            AdminField("wasPriceClassicPaise", "Classic was (₹)", FieldType.PAISE, min = 0.0),
            AdminField("wasPricePremiumPaise", "Premium was (₹)", FieldType.PAISE, min = 0.0),
            AdminField("registrationOpen", "Open", FieldType.BOOL, list = true, default = true),
            AdminField("premiumSoldOut", "Premium sold out", FieldType.BOOL, default = false),
            sort(),
        ),
    )

    val RACE_FAQS = AdminResource(
        key = "race-faqs",
        label = "Race FAQs",
        group = "Marathon",
        description = "Questions shown on an edition's detail screen.",
        table = "RaceFaq",
        titleField = "question",
        orderBy = """"eventId", "sortOrder", "id"""",
        sortField = "sortOrder",
        fields = listOf(
            AdminField("eventId", "Edition", FieldType.REF, ref = "marathons", required = true, list = true, filter = true),
            AdminField("question", "Question", FieldType.TEXT, required = true, list = true, maxLength = 200),
            AdminField("answer", "Answer", FieldType.LONGTEXT, required = true),
            sort(),
        ),
    )

    val REGISTRATIONS = AdminResource(
        key = "registrations",
        label = "Registrations & bibs",
        group = "Marathon",
        description = "Everyone registered for each edition. Set or correct bib numbers here (unique per edition); use the edition page " +
            "to allocate bibs in bulk, issue complimentary passes and publish results.",
        table = "MarathonRegistration",
        source = """SELECT r.*, u."name" AS "runnerName", COALESCE(u."email", u."contactEmail", u."phone") AS "runnerContact"
                    FROM "MarathonRegistration" r LEFT JOIN "User" u ON u."id" = r."userId"""",
        titleField = "registrationRef",
        orderBy = """"registeredAt" DESC, "id"""",
        canCreate = false,
        canDelete = false,
        fields = listOf(
            AdminField("eventId", "Edition", FieldType.REF, ref = "marathons", list = true, filter = true, readOnly = true),
            AdminField("runnerName", "Runner", FieldType.TEXT, list = true, readOnly = true),
            AdminField("runnerContact", "Contact", FieldType.TEXT, list = true, readOnly = true),
            AdminField("registrationRef", "Registration ref", FieldType.TEXT, list = true, readOnly = true),
            AdminField("category", "Distance", FieldType.TEXT, list = true, readOnly = true),
            AdminField("tier", "Tier", FieldType.SELECT, options = opts("CLASSIC" to "Classic", "PREMIUM" to "Premium VIP"), list = true, readOnly = true),
            AdminField("bibNumber", "Bib number", FieldType.TEXT, list = true, maxLength = 24, help = "Unique within the edition. The digital pass appears once it is set."),
            AdminField("registeredAt", "Registered", FieldType.DATETIME, readOnly = true),
        ),
    )

    val APP_CONFIG = AdminResource(
        key = "app-config",
        label = "App switches",
        group = "Platform",
        description = "Force-update and maintenance. Careful: these affect every user at once.",
        table = "AppConfig",
        titleField = "minSupportedAppVersion",
        orderBy = """"id"""",
        hasUpdatedAt = true,
        singleton = true,
        canCreate = false,
        canDelete = false,
        fields = listOf(
            AdminField("minSupportedAppVersion", "Minimum app version", FieldType.TEXT, required = true, list = true, maxLength = 20,
                help = "Older versions must update before using the app, e.g. 1.2.0."),
            AdminField("maintenanceActive", "Maintenance mode", FieldType.BOOL, list = true, default = false, help = "Shows \"Back shortly\" to everyone."),
            AdminField("maintenanceMessage", "Maintenance message", FieldType.TEXT, maxLength = 200),
        ),
    )

    val ALL: List<AdminResource> = listOf(
        SECTIONS, CATEGORIES, SESSIONS, INSTRUCTORS, BATCHES, LIVE_CLASSES, WORKSHOPS,
        ARTICLES, REELS, TESTIMONIALS, PROMOS, MARATHONS, RACE_DISTANCES, RACE_FAQS, REGISTRATIONS, APP_CONFIG,
    )

    private val byKey = ALL.associateBy { it.key }

    fun of(key: String): AdminResource? = byKey[key]
}
