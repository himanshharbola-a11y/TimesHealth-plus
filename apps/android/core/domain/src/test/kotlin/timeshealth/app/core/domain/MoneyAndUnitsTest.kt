package timeshealth.app.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** formatPaise (lib/time.ts) and the run tracker's unitsFor (RunTrackerPanel.tsx). */
class MoneyAndUnitsTest {

    // ── formatPaise ─────────────────────────────────────────────────────────

    @Test
    fun `formatPaise shows whole rupees`() {
        assertThat(formatPaise(197_700)).isEqualTo("₹1,977")
        assertThat(formatPaise(0)).isEqualTo("₹0")
        assertThat(formatPaise(99_900)).isEqualTo("₹999")
        assertThat(formatPaise(100_000)).isEqualTo("₹1,000")
    }

    @Test
    fun `formatPaise groups in lakhs and crores`() {
        assertThat(formatPaise(10_000_000)).isEqualTo("₹1,00,000")
        assertThat(formatPaise(1_234_567_800)).isEqualTo("₹1,23,45,678")
        assertThat(formatPaise(99_999_999_900)).isEqualTo("₹99,99,99,999")
        assertThat(formatPaise(100_000_000_000)).isEqualTo("₹1,00,00,00,000")
    }

    @Test
    fun `formatPaise rounds half-up like Math round, not half-even`() {
        assertThat(formatPaise(49)).isEqualTo("₹0")
        assertThat(formatPaise(50)).isEqualTo("₹1")
        assertThat(formatPaise(149)).isEqualTo("₹1")
        assertThat(formatPaise(150)).isEqualTo("₹2")
        assertThat(formatPaise(250)).isEqualTo("₹3") // half-even would give ₹2
        assertThat(formatPaise(-150)).isEqualTo("₹-1") // Math.round(-1.5) is -1
    }

    // ── unitsFor ────────────────────────────────────────────────────────────

    @Test
    fun `metric units`() {
        val u = unitsFor(imperial = false)
        assertThat(u.short).isEqualTo("km")
        assertThat(u.long).isEqualTo("KILOMETRES")
        assertThat(u.dist(5.0)).isEqualTo(5.0)
        assertThat(u.pace(358.0)).isEqualTo("5'58\"")
    }

    @Test
    fun `imperial units convert km and sec per km`() {
        val u = unitsFor(imperial = true)
        assertThat(u.short).isEqualTo("mi")
        assertThat(u.long).isEqualTo("MILES")
        assertThat(u.dist(KM_PER_MILE)).isEqualTo(1.0)
        assertThat(u.dist(10.0)).isWithin(1e-9).of(6.213711922)
        // 5:00 /km = 482.8 s /mi → 8'03".
        assertThat(u.pace(300.0)).isEqualTo("8'03\"")
    }

    @Test
    fun `pace rounds the total first in either unit`() {
        assertThat(unitsFor(false).pace(839.6)).isEqualTo("14'00\"")
        // 521.7 s/km × 1.609344 = 839.59… s/mi → 14'00", not 13'60".
        assertThat(unitsFor(true).pace(521.7)).isEqualTo("14'00\"")
    }

    @Test
    fun `no pace yet shows dashes`() {
        for (imperial in listOf(false, true)) {
            val u = unitsFor(imperial)
            assertThat(u.pace(0.0)).isEqualTo("--'--\"")
            assertThat(u.pace(-3.0)).isEqualTo("--'--\"")
            assertThat(u.pace(Double.NaN)).isEqualTo("--'--\"")
            assertThat(u.pace(Double.POSITIVE_INFINITY)).isEqualTo("--'--\"")
        }
    }
}
