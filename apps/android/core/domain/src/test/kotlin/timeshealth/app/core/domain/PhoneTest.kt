package timeshealth.app.core.domain

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The app's phone rule (toIndianE164) and the server's (normalizePhone).
 * The table records both answers for each input; the agreement test is that
 * whenever the app accepts a number, the server stores exactly the same value.
 */
class PhoneTest {

    /** input → (app's toIndianE164, server's normalizePhone). */
    private val table: List<Triple<String, String?, String?>> = listOf(
        // Accepted by both, the same way.
        Triple("9876543210", "+919876543210", "+919876543210"),
        Triple("98765 43210", "+919876543210", "+919876543210"),
        Triple("  9876543210  ", "+919876543210", "+919876543210"),
        Triple("+91 98765 43210", "+919876543210", "+919876543210"),
        Triple("+91-98765-43210", "+919876543210", "+919876543210"),
        Triple("+919876543210", "+919876543210", "+919876543210"),
        Triple("919876543210", "+919876543210", "+919876543210"),
        Triple("91 98765 43210", "+919876543210", "+919876543210"),
        Triple("(+91) 98765-43210", "+919876543210", "+919876543210"),
        Triple("6000000000", "+916000000000", "+916000000000"), // lowest mobile prefix
        Triple("9198765432", "+919198765432", "+919198765432"), // 10 digits that start 91
        // Refused by both.
        Triple("", null, null),
        Triple("98765", null, null),
        Triple("5876543210", null, null), // mobiles start 6–9
        Triple("1234567890", null, null),
        Triple("0091 98765 43210", null, null),
        Triple("98765432101", null, null),
        Triple("abcdefghij", null, null),
        // The 0-prefixed way of writing a mobile: accepted by both.
        Triple("09876543210", "+919876543210", "+919876543210"),
        Triple("0 98765 43210", "+919876543210", "+919876543210"),
        // "+91" that isn't a valid Indian mobile is a typo: refused by both.
        Triple("+91 58765 43210", null, null),
        Triple("+91 0 98765 43210", null, null),
        // Other countries: server-only (contact fields); the app's Indian-mobile
        // entry refuses them.
        Triple("+1 415 555 0123", null, "+14155550123"),
        Triple("+44 20 7946 0958", null, "+442079460958"),
    )

    @Test
    fun `toIndianE164 - the app's rule`() {
        for ((input, app, _) in table) {
            assertWithMessage("toIndianE164(\"$input\")").that(toIndianE164(input)).isEqualTo(app)
        }
    }

    @Test
    fun `normalizePhone - the server's rule`() {
        for ((input, _, server) in table) {
            assertWithMessage("normalizePhone(\"$input\")").that(normalizePhone(input)).isEqualTo(server)
        }
        assertThat(normalizePhone(null)).isNull()
    }

    @Test
    fun `client and server agree - whatever the app accepts, the server stores identically`() {
        for ((input, _, _) in table) {
            val app = toIndianE164(input) ?: continue
            assertWithMessage("\"$input\"").that(normalizePhone(input)).isEqualTo(app)
            // And the stored value is a fixed point of both rules.
            assertThat(toIndianE164(app)).isEqualTo(app)
            assertThat(normalizePhone(app)).isEqualTo(app)
        }
    }

    @Test
    fun `server plus-international needs 8 to 15 digits`() {
        assertThat(normalizePhone("+1234567")).isNull()
        assertThat(normalizePhone("+12345678")).isEqualTo("+12345678")
        assertThat(normalizePhone("+123456789012345")).isEqualTo("+123456789012345")
        assertThat(normalizePhone("+1234567890123456")).isNull()
        assertThat(normalizePhone("12345678")).isNull() // no "+": not international
    }

    @Test
    fun `normalizeEmail trims and lowercases`() {
        assertThat(normalizeEmail("  Ravi@Gmail.COM ")).isEqualTo("ravi@gmail.com")
        assertThat(normalizeEmail("")).isNull()
        assertThat(normalizeEmail("   ")).isNull()
        assertThat(normalizeEmail(null)).isNull()
    }

    @Test
    fun `localMobileDigits prefills the 10-digit field`() {
        assertThat(localMobileDigits("+919876543210")).isEqualTo("9876543210")
        assertThat(localMobileDigits("+14155550123")).isEqualTo("+14155550123")
        assertThat(localMobileDigits(null)).isEqualTo("")
        assertThat(localMobileDigits("")).isEqualTo("")
    }
}
