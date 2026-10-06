package timeshealth.app.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** CheckoutSheet outcomeOf, hooks.ts isSettled, pendingOrders.ts purchaseKey. */
class CheckoutTest {

    @Test
    fun `no status yet is the Pay screen`() {
        assertThat(outcomeOf(null, entitlementGranted = false)).isEqualTo(CheckoutOutcome.IDLE)
        assertThat(outcomeOf(null, entitlementGranted = true)).isEqualTo(CheckoutOutcome.IDLE)
    }

    @Test
    fun `granted wins over every status`() {
        for (status in listOf("CREATED", "PENDING", "PAID", "FAILED", "PAID_NOT_GRANTED", "SOMETHING_NEW")) {
            assertThat(outcomeOf(status, entitlementGranted = true)).isEqualTo(CheckoutOutcome.GRANTED)
        }
    }

    @Test
    fun `failed and refund`() {
        assertThat(outcomeOf("FAILED", false)).isEqualTo(CheckoutOutcome.FAILED)
        assertThat(outcomeOf("PAID_NOT_GRANTED", false)).isEqualTo(CheckoutOutcome.REFUND)
    }

    @Test
    fun `anything else is still settling - including PAID before the webhook and unknown statuses`() {
        for (status in listOf("CREATED", "PENDING", "PAID", "SOMETHING_NEW", "failed", "")) {
            assertThat(outcomeOf(status, false)).isEqualTo(CheckoutOutcome.SETTLING)
        }
    }

    @Test
    fun `isOrderSettled is exactly 'not settling'`() {
        assertThat(isOrderSettled("PENDING", true)).isTrue()
        assertThat(isOrderSettled("FAILED", false)).isTrue()
        assertThat(isOrderSettled("PAID_NOT_GRANTED", false)).isTrue()
        assertThat(isOrderSettled("PAID", false)).isFalse()
        assertThat(isOrderSettled("CREATED", false)).isFalse()
        assertThat(isOrderSettled("PENDING", false)).isFalse()
    }

    @Test
    fun `purchaseKey joins what was bought, absent parts empty`() {
        assertThat(purchaseKey("YOGA_SUBSCRIPTION", "yoga_annual")).isEqualTo("YOGA_SUBSCRIPTION|yoga_annual|||")
        assertThat(purchaseKey("MARATHON_REGISTRATION", "p1", "evt_9", "10K", "PREMIUM"))
            .isEqualTo("MARATHON_REGISTRATION|p1|evt_9|10K|PREMIUM")
        assertThat(purchaseKey("WORKSHOP", "w1", eventId = null, category = "x", tier = null)).isEqualTo("WORKSHOP|w1||x|")
    }

    @Test
    fun `purchaseKey tells different purchases apart`() {
        val classic = purchaseKey("MARATHON_REGISTRATION", "p1", "evt_9", "10K", "CLASSIC")
        val premium = purchaseKey("MARATHON_REGISTRATION", "p1", "evt_9", "10K", "PREMIUM")
        val otherCategory = purchaseKey("MARATHON_REGISTRATION", "p1", "evt_9", "21K", "CLASSIC")
        assertThat(setOf(classic, premium, otherCategory)).hasSize(3)
    }
}
