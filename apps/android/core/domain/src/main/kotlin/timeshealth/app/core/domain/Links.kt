package timeshealth.app.core.domain

/*
 * Which links the app will act on. Port of apps/mobile/src/lib/links.ts.
 *
 * Every regex here is matched with Regex.matches / an anchored find: Java's
 * `$` also matches before a trailing "\n", so a partial `find` would let
 * "/paywall\n" through where JavaScript's `test` does not.
 */

/**
 * WhatsApp support (Help & Support). PLACEHOLDER, as in the RN app: the business
 * supplies the real support number before launch.
 */
const val SUPPORT_WHATSAPP_URL: String = "https://wa.me/910000000000"

/** Privacy & Terms. PLACEHOLDER until the policy page is published. */
const val PRIVACY_URL: String = "https://timeshealthplus.invalid/privacy"

/** Schemes always allowed. Debug builds add plain `http` (see [allowedSchemes]). */
private val RELEASE_SCHEMES = setOf("https", "whatsapp", "market", "tel", "mailto")
private val DEBUG_SCHEMES = RELEASE_SCHEMES + "http"

/**
 * Schemes the app will hand to the OS. Server-supplied URLs (articles, class
 * and workshop join links, certificates, photos) only ever need https; the
 * others are the app's own fixed links (WhatsApp share, Play Store, phone,
 * email).
 *
 * Anything else — intent:, file:, content:, javascript:, a custom scheme — is
 * refused, so a bad or compromised content row can't launch an arbitrary app
 * component on the phone. Plain http only in debug builds (local servers):
 * pass `BuildConfig.DEBUG`.
 */
fun allowedSchemes(debug: Boolean): Set<String> = if (debug) DEBUG_SCHEMES else RELEASE_SCHEMES

private val SCHEME_RE = Regex("^([a-z][a-z0-9+.-]*):", RegexOption.IGNORE_CASE)

/**
 * The URL's scheme, lowercased ("HTTPS://x" → "https"), or null if it has
 * none. Parsed by hand, as in the RN app, so the rule doesn't depend on a
 * URL parser's leniency. Leading/trailing whitespace is ignored (JS `trim`).
 */
fun schemeOf(url: String): String? =
    SCHEME_RE.find(url.trimJs())?.groupValues?.get(1)?.lowercase()

/** True if [url] may be opened outside the app (see [allowedSchemes]). Null or empty is never safe. */
fun isSafeExternalUrl(url: String?, debug: Boolean): Boolean {
    if (url.isNullOrEmpty()) return false
    val scheme = schemeOf(url) ?: return false
    return scheme in allowedSchemes(debug)
}

/**
 * In-app routes a push notification or inbox item may open (expo-router
 * paths, which the native app maps onto its own destinations). The route
 * arrives in a payload the app didn't write, so it's matched against the
 * screens that actually exist rather than navigated to blindly. Ids are
 * 1–64 of `[A-Za-z0-9_-]`.
 */
val APP_ROUTES: List<Regex> = listOf(
    Regex("^/\\(tabs\\)(/(yoga|marathon|diet))?$"),
    Regex("^/race/[\\w-]{1,64}(/results)?$"),
    Regex("^/bib/[\\w-]{1,64}$"),
    Regex("^/session/[\\w-]{1,64}$"),
    // A live class (premiere): its reminder notification opens it.
    Regex("^/live/[\\w-]{1,64}$"),
    Regex("^/(paywall|run-tracker|yoga-explorer)$"),
)

/** True if [route] is exactly one of [APP_ROUTES] (whole-string match). */
fun isAppRoute(route: String?): Boolean = route != null && APP_ROUTES.any { it.matches(route) }
