package timeshealth.app.core.domain

/*
 * Email checks. The RN app has TWO, and they are not the same:
 *  - onboarding.tsx EMAIL_RE — a copy of the server's own check (zod's
 *    `.email()` in apps/api/src/routes/session.ts), so anything accepted on
 *    the phone is accepted by the server.
 *  - ProfileDrawer.tsx — a looser shape check. An address that passes it but
 *    not zod's is refused by the server (400 INVALID_BODY), and the drawer
 *    then shows the same "Enter a valid email address" message.
 * Both are ported. Prefer [isValidEmail] on every screen: it reaches the same
 * verdict without the round trip.
 *
 * Both test the string as given — trim first ([trimJs]), as both screens and
 * the server do — and both are whole-string matches.
 */

/** The server's limit (zod `.max(160)`), and the profile field's maxLength. */
const val EMAIL_MAX_LENGTH: Int = 160

/**
 * zod's email pattern, verbatim: no leading dot, no "..", the local part ends
 * in `[A-Z0-9_+-]`, every domain label starts alphanumeric, and the TLD is two
 * or more letters. ASCII only, case-insensitive.
 */
val EMAIL_RE: Regex = Regex(
    "^(?!\\.)(?!.*\\.\\.)([A-Z0-9_'+\\-.]*)[A-Z0-9_+-]@([A-Z0-9][A-Z0-9-]*\\.)+[A-Z]{2,}$",
    RegexOption.IGNORE_CASE,
)

/**
 * True if the server will accept [email] (PRD §5 onboarding, §10 profile).
 * The server refuses the WHOLE onboarding step on one bad field, which would
 * silently lose a good phone number with it — so check here first.
 * Does not check [EMAIL_MAX_LENGTH]; the input field caps it.
 */
fun isValidEmail(email: String): Boolean = EMAIL_RE.matches(email)

/**
 * The profile drawer's pattern: something@something.xx with no whitespace or
 * extra "@". `\s` here is JavaScript's whitespace set (it includes U+00A0
 * etc.), not Java's ASCII-only `\s`.
 */
val PROFILE_EMAIL_RE: Regex = Regex(
    "^[^$JS_WHITESPACE_CLASS@]+@[^$JS_WHITESPACE_CLASS@]+\\.[^$JS_WHITESPACE_CLASS@]{2,}$",
)

/** The profile drawer's looser pre-check — see the file note; prefer [isValidEmail]. */
fun isValidProfileEmail(email: String): Boolean = PROFILE_EMAIL_RE.matches(email)
