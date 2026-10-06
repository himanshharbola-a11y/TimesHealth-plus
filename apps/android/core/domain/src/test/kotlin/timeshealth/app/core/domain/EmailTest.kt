package timeshealth.app.core.domain

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Onboarding's (= the server's zod) email check and the profile drawer's
 * looser one. input → (isValidEmail, isValidProfileEmail).
 */
class EmailTest {

    private val table: List<Triple<String, Boolean, Boolean>> = listOf(
        Triple("name@example.com", true, true),
        Triple("NAME@EXAMPLE.COM", true, true),
        Triple("Ravi.Kumar+yoga@Gmail.co.in", true, true),
        Triple("o'brien@example.ie", true, true),
        Triple("user@123.com", true, true),
        Triple("a@b.co", true, true),
        Triple("first_last-x@sub.domain.example.org", true, true),
        // zod is stricter: the profile drawer lets these through, the server refuses them.
        Triple(".name@example.com", false, true), // leading dot
        Triple("na..me@example.com", false, true), // consecutive dots
        Triple("name.@example.com", false, true), // local part ends in a dot
        Triple("name'@example.com", false, true), // local part ends in an apostrophe
        Triple("name@-example.com", false, true), // label starts with a hyphen
        Triple("name@exa_mple.com", false, true), // underscore in the domain
        Triple("name@example.co1", false, true), // TLD must be letters
        Triple("ñame@example.com", false, true), // ASCII only
        Triple("name@example..com", false, true),
        // Refused by both.
        Triple("", false, false),
        Triple("name", false, false),
        Triple("name@example", false, false),
        Triple("name@example.c", false, false), // TLD under 2
        Triple("@example.com", false, false),
        Triple("name@@example.com", false, false),
        Triple("na me@example.com", false, false),
        Triple("name@ex ample.com", false, false),
        Triple(" name@example.com", false, false), // callers trim first
        Triple("name@example.com ", false, false),
        Triple("name@example.com\n", false, false), // Java's $ would allow this; a whole match doesn't
        Triple("na me@example.com", false, false), // JS \s includes NBSP
        Triple("name@example.com ", false, false),
    )

    @Test
    fun `isValidEmail is the server's zod rule`() {
        for ((input, strict, _) in table) {
            assertWithMessage("isValidEmail(\"$input\")").that(isValidEmail(input)).isEqualTo(strict)
        }
    }

    @Test
    fun `isValidProfileEmail is the profile drawer's shape check`() {
        for ((input, _, loose) in table) {
            assertWithMessage("isValidProfileEmail(\"$input\")").that(isValidProfileEmail(input)).isEqualTo(loose)
        }
    }

    @Test
    fun `anything the strict rule accepts, the profile check accepts too`() {
        for ((input, strict, _) in table) {
            if (strict) assertWithMessage(input).that(isValidProfileEmail(input)).isTrue()
        }
    }

    @Test
    fun `trimming first, as the screens do, makes padded input valid`() {
        assertWithMessage("trimmed").that(isValidEmail("  name@example.com ".trimJs())).isTrue()
    }
}
