package timeshealth.app.core.integrations.subscription

import javax.inject.Inject
import timeshealth.app.core.data.checkout.CheckoutRepository
import timeshealth.app.core.data.checkout.PaymentsNotEnabledException
import timeshealth.app.core.domain.CheckoutOutcome
import timeshealth.app.core.domain.outcomeOf
import timeshealth.app.core.model.CreateOrderRequest
import timeshealth.app.core.model.OrderStatusResponse
import timeshealth.app.core.network.ApiRequestException

/**
 * MOCK / INTERIM implementation of [SubscriptionProvider]: our own server's
 * checkout (POST /orders → simulated payment → order status).
 *
 * It is not a toy: [CheckoutRepository] already guarantees one purchase can
 * never become two charges (an unconfirmed order is re-checked, never re-paid)
 * and the server grants access only after settlement. Replace it with the TIL
 * Subscription SDK adapter as described on [SubscriptionProvider].
 */
class ServerCheckoutSubscriptionProvider @Inject constructor(
    private val checkout: CheckoutRepository,
) : SubscriptionProvider {

    override val name: String = "server-checkout"

    override suspend fun purchase(host: PurchaseHost, request: PurchaseRequest): PurchaseOutcome =
        try {
            purchaseOutcomeOf(checkout.checkout(request.toOrderRequest()))
        } catch (e: ApiRequestException) {
            // Refused before any payment (the order was never created).
            if (e.isNetworkFailure) PurchaseOutcome.Failed(e.error.message)
            else PurchaseOutcome.Refused(e.code, e.error.message)
        } catch (e: PaymentsNotEnabledException) {
            PurchaseOutcome.Failed(e.message ?: "Payments are not available in this build.")
        }

    override suspend fun refresh(reference: String): PurchaseOutcome =
        try {
            purchaseOutcomeOf(checkout.refresh(reference))
        } catch (e: ApiRequestException) {
            // Still unknown: keep the user on "Confirming…", never offer Pay again.
            PurchaseOutcome.Confirming(reference)
        }

    /** Our server checkout keeps no separate purchase history to restore. */
    override suspend fun restore() = Unit

    private fun PurchaseRequest.toOrderRequest() = CreateOrderRequest(
        productType = productType,
        productId = productId,
        eventId = eventId,
        category = category,
        tier = tier,
        referralCode = referralCode,
    )
}

/** Maps our server's order status to what the purchase screens show. */
internal fun purchaseOutcomeOf(status: OrderStatusResponse): PurchaseOutcome =
    when (outcomeOf(status.status.name, status.entitlementGranted)) {
        CheckoutOutcome.GRANTED -> PurchaseOutcome.Granted
        CheckoutOutcome.FAILED -> PurchaseOutcome.Failed("Payment didn't go through — you haven't been charged.")
        CheckoutOutcome.REFUND -> PurchaseOutcome.RefundFlagged(status.orderId)
        // Money may have moved: never offer Pay again, offer Refresh.
        CheckoutOutcome.SETTLING, CheckoutOutcome.IDLE -> PurchaseOutcome.Confirming(status.orderId)
    }
