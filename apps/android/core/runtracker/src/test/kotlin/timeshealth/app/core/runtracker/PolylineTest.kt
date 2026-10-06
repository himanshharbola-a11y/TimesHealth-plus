package timeshealth.app.core.runtracker

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import timeshealth.app.core.domain.Anchor

class PolylineTest {

    private fun pt(lat: Double, lng: Double) = Anchor(lat, lng, 0L)

    @Test
    fun `Google's documented example encodes exactly`() {
        // developers.google.com/maps/documentation/utilities/polylinealgorithm
        val route = listOf(pt(38.5, -120.2), pt(40.7, -120.95), pt(43.252, -126.453))
        assertThat(encodePolyline(route)).isEqualTo("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
    }

    @Test
    fun `Google's single-value example -179_9832104`() {
        // The same page walks through this one value: `~oia@` for latitude, 0 for longitude.
        assertThat(encodePolyline(listOf(pt(-179.9832104, 0.0)))).isEqualTo("`~oia@?")
    }

    @Test
    fun `no points is an empty string`() {
        assertThat(encodePolyline(emptyList())).isEmpty()
    }

    @Test
    fun `a repeated point encodes as zero deltas`() {
        assertThat(encodePolyline(listOf(pt(38.5, -120.2), pt(38.5, -120.2))))
            .isEqualTo("_p~iF~ps|U??")
    }

    @Test
    fun `rounds to five decimals half up, like JavaScript Math round`() {
        // 0.000005 * 1e5 = 0.5 rounds up to 1 (half-even would give 0).
        assertThat(encodePolyline(listOf(pt(0.000005, 0.0)))).isEqualTo(encodePolyline(listOf(pt(0.00001, 0.0))))
        // -0.000005 * 1e5 = -0.5 rounds toward +∞ to 0, as Math.round does in JS.
        assertThat(encodePolyline(listOf(pt(-0.000005, 0.0)))).isEqualTo("??")
    }
}
