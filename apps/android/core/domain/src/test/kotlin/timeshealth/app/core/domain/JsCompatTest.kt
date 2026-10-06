package timeshealth.app.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The JavaScript primitives the ported rules depend on. */
class JsCompatTest {

    @Test
    fun `trimJs strips JavaScript whitespace, which differs from Kotlin's trim`() {
        assertThat(" \t\n\u000B\u000C\r        　﻿x  ".trimJs())
            .isEqualTo("x")
        // Kotlin's trim() keeps the BOM; JavaScript strips it.
        assertThat("﻿x".trim()).isEqualTo("﻿x")
        assertThat("﻿x".trimJs()).isEqualTo("x")
        // Kotlin's trim() strips U+001C; JavaScript keeps it.
        assertThat("\u001Cx".trimJs()).isEqualTo("\u001Cx")
        assertThat("a b".trimJs()).isEqualTo("a b")
    }

    @Test
    fun `jsNumber follows Number(string)`() {
        assertThat(jsNumber("")).isEqualTo(0.0)
        assertThat(jsNumber("  ")).isEqualTo(0.0)
        assertThat(jsNumber("05")).isEqualTo(5.0)
        assertThat(jsNumber(" 5 ")).isEqualTo(5.0)
        assertThat(jsNumber("+5")).isEqualTo(5.0)
        assertThat(jsNumber("-10")).isEqualTo(-10.0)
        assertThat(jsNumber("5.5")).isEqualTo(5.5)
        assertThat(jsNumber(".5")).isEqualTo(0.5)
        assertThat(jsNumber("5.")).isEqualTo(5.0)
        assertThat(jsNumber("1e1")).isEqualTo(10.0)
        assertThat(jsNumber("0x10")).isEqualTo(16.0)
        assertThat(jsNumber("0b11")).isEqualTo(3.0)
        assertThat(jsNumber("0o17")).isEqualTo(15.0)
        assertThat(jsNumber("-Infinity")).isEqualTo(Double.NEGATIVE_INFINITY)
        assertThat(jsNumber("5a").isNaN()).isTrue()
        assertThat(jsNumber("0x").isNaN()).isTrue()
        assertThat(jsNumber("-0x10").isNaN()).isTrue()
        assertThat(jsNumber("5d").isNaN()).isTrue() // Java's parseDouble would accept this
        assertThat(jsNumber("0o19").isNaN()).isTrue()
    }

    @Test
    fun `jsWholeNumber refuses fractions, NaN and infinities`() {
        assertThat(jsWholeNumber("06")).isEqualTo(6)
        assertThat(jsWholeNumber("6.0")).isEqualTo(6)
        assertThat(jsWholeNumber("6.5")).isNull()
        assertThat(jsWholeNumber("x")).isNull()
        assertThat(jsWholeNumber("Infinity")).isNull()
    }
}
