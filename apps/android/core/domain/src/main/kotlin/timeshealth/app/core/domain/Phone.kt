package timeshealth.app.core.domain

/*
 * Phone and email identifiers — the PRD §5 one-account rule depends on them.
 *
 * Two rules, ported from two places, deliberately kept separate:
 *  - toIndianE164 (apps/mobile/src/lib/firebaseAuth.ts): what the APP accepts
 *    from the user. Indian mobiles only, because phone sign-in, class
 *    reminders and race-day calls are India-only.
 *  - normalizePhone (apps/api/src/identity/normalize.ts): how the SERVER
 *    stores and matches any phone, including legacy imports.
 *
 * They agree wherever the app accepts a number: toIndianE164(x) is either
 * null or exactly normalizePhone(x) (PhoneTest checks this on a table). Both
 * take every common way of writing an Indian mobile, and both refuse a "+91"
 * number that isn't one. The server alone also keeps other countries'
 * "+"-prefixed numbers (8–15 digits) for contact fields.
 */

private val NON_DIGIT = Regex("\\D")
private val INDIAN_MOBILE = Regex("[6-9]\\d{9}")

private fun isIndianMobile(digits: String) = INDIAN_MOBILE.matches(digits)

/**
 * The app's phone field → "+91XXXXXXXXXX", or null if it isn't an Indian
 * mobile. Formatting is ignored ("98765 43210", "+91-98765-43210",
 * "919876543210"); the 10 digits must start 6–9 (Indian mobile numbering).
 * A leading 0 ("09876543210") is refused, unlike the server — see file note.
 */
fun toIndianE164(input: String): String? {
    val digits = input.replace(NON_DIGIT, "")
    // "+91 98765 43210", "919876543210" and "09876543210" are all the same
    // mobile — the server accepts each, so the app must too.
    val local = when {
        digits.length == 12 && digits.startsWith("91") -> digits.substring(2)
        digits.length == 11 && digits.startsWith("0") -> digits.substring(1)
        else -> digits
    }
    return if (isIndianMobile(local)) "+91$local" else null
}

/**
 * The server's normalisation, so the app can predict how a number will be
 * stored and matched. Matching is exact string equality, so "9876543210" vs
 * "+919876543210" would otherwise be two different people and a migrating web
 * subscriber would land in an empty new account (PRD §5).
 *
 * Indian mobiles → E.164 (+91XXXXXXXXXX) from any common way of writing them
 * ("98765 43210", "09876543210", "+91-98765-43210", "919876543210"). Other
 * international numbers keep their +country digits (8–15 digits, must be
 * written with a leading "+"). Anything else → null.
 */
fun normalizePhone(raw: String?): String? {
    if (raw.isNullOrEmpty()) return null
    val trimmed = raw.trimJs()
    val digits = trimmed.replace(NON_DIGIT, "")
    if (isIndianMobile(digits)) return "+91$digits"
    if (digits.length == 11 && digits.startsWith("0") && isIndianMobile(digits.substring(1))) {
        return "+91${digits.substring(1)}"
    }
    if (digits.length == 12 && digits.startsWith("91") && isIndianMobile(digits.substring(2))) {
        return "+$digits"
    }
    // Other countries keep their +country digits. A "+91…" number that wasn't
    // a valid Indian mobile above is a typo, not an international number.
    if (trimmed.startsWith("+") && !digits.startsWith("91") && digits.length in 8..15) return "+$digits"
    return null
}

/**
 * The server's email normalisation: trimmed and lowercased, empty → null.
 * "Ravi@Gmail.com" and "ravi@gmail.com" are one person (PRD §5).
 */
fun normalizeEmail(raw: String?): String? {
    val v = raw?.trimJs()?.lowercase()
    return if (v.isNullOrEmpty()) null else v
}

/**
 * "+919876543210" → "9876543210", to prefill a field that takes the 10
 * digits (onboarding and profile). A stored number that isn't an Indian
 * mobile is shown as stored; null → "".
 */
fun localMobileDigits(phone: String?): String = toIndianE164(phone ?: "")?.substring(3) ?: phone ?: ""
