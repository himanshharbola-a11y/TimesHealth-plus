package timeshealth.app.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** apps/mobile/src/lib/links.ts: external URL scheme allowlist and in-app route allowlist. */
class LinksTest {

    private fun safe(url: String?) = isSafeExternalUrl(url, debug = false)
    private fun safeInDebug(url: String?) = isSafeExternalUrl(url, debug = true)

    @Test
    fun `https and the app's own fixed schemes are allowed`() {
        assertThat(safe("https://timeshealthplus.example/article/1")).isTrue()
        assertThat(safe("whatsapp://send?text=hi")).isTrue()
        assertThat(safe("market://details?id=timeshealth.app")).isTrue()
        assertThat(safe("tel:+911234567890")).isTrue()
        assertThat(safe("mailto:help@example.com")).isTrue()
    }

    @Test
    fun `scheme case doesn't matter - uppercase HTTPS is https`() {
        assertThat(safe("HTTPS://EXAMPLE.COM")).isTrue()
        assertThat(safe("HtTpS://example.com")).isTrue()
        assertThat(schemeOf("HTTPS://EXAMPLE.COM")).isEqualTo("https")
    }

    @Test
    fun `surrounding whitespace is ignored when reading the scheme, JS-style`() {
        assertThat(safe("  https://example.com  ")).isTrue()
        assertThat(safe(" https://example.com")).isTrue() // JS trim strips NBSP
        assertThat(safe("﻿https://example.com")).isTrue() // and the BOM
    }

    @Test
    fun `plain http only in debug builds`() {
        assertThat(safe("http://10.0.2.2:3000/v1")).isFalse()
        assertThat(safeInDebug("http://10.0.2.2:3000/v1")).isTrue()
        assertThat(safeInDebug("HTTP://localhost")).isTrue()
        assertThat(allowedSchemes(false)).doesNotContain("http")
        assertThat(allowedSchemes(true)).contains("http")
    }

    @Test
    fun `intent, javascript, file, content and custom schemes are refused even in debug`() {
        for (url in listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.evil;end",
            "INTENT://scan/#Intent;end",
            "javascript:alert(1)",
            "JavaScript:alert(document.cookie)",
            " javascript:alert(1)",
            "file:///sdcard/Download/x.html",
            "content://com.android.contacts/contacts",
            "data:text/html,<script>alert(1)</script>",
            "timeshealth://paywall",
            "ftp://example.com",
        )) {
            assertThat(safe(url)).isFalse()
            assertThat(safeInDebug(url)).isFalse()
        }
    }

    @Test
    fun `no scheme, an empty value or null is refused`() {
        assertThat(safe(null)).isFalse()
        assertThat(safe("")).isFalse()
        assertThat(safe("   ")).isFalse()
        assertThat(safe("example.com/path")).isFalse()
        assertThat(safe("//example.com")).isFalse()
        assertThat(safe("1https://example.com")).isFalse() // a scheme starts with a letter
        assertThat(safe("localhost:3000")).isFalse() // parses as scheme "localhost"
        assertThat(schemeOf("example.com")).isNull()
    }

    @Test
    fun `app routes - the screens that exist`() {
        for (route in listOf(
            "/(tabs)", "/(tabs)/yoga", "/(tabs)/marathon", "/(tabs)/diet",
            "/race/abc-123", "/race/abc_123/results",
            "/bib/BIB-0042", "/session/s_1",
            "/paywall", "/run-tracker", "/yoga-explorer",
            "/race/" + "a".repeat(64),
        )) {
            assertThat(isAppRoute(route)).isTrue()
        }
    }

    @Test
    fun `app routes - anything else is refused`() {
        for (route in listOf(
            null, "", "/", "paywall", "/paywall/", "/paywall?x=1",
            "/paywall\n", // Java's $ would match before a trailing newline; a whole match doesn't
            "/(tabs)/profile", "/(tabs)/", "/race/", "/race/a b", "/race/a/b",
            "/race/../admin", "/race/é", "/race/" + "a".repeat(65),
            "/race/x/results/extra", "/bib/", "/session/",
            "https://evil.example/paywall", "/PAYWALL",
        )) {
            assertThat(isAppRoute(route)).isFalse()
        }
    }
}
