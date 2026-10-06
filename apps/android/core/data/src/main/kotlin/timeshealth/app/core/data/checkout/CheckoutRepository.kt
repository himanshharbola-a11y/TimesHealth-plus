package timeshealth.app.core.data.checkout

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timeshealth.app.core.data.cache.CacheKeys
import timeshealth.app.core.data.cache.ResponseCache
import timeshealth.app.core.domain.CheckoutOutcome
import timeshealth.app.core.domain.isOrderSettled
import timeshealth.app.core.domain.outcomeOf
import timeshealth.app.core.model.CreateOrderRequest
import timeshealth.app.core.model.OrderStatus
import timeshealth.app.core.model.OrderStatusResponse
import timeshealth.app.core.model.PaymentGateway
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.core.network.TimesHealthApi

/**
 * Thrown by [CheckoutRepository.checkout] for a gateway this build can't pay through (anything
 * but STUB until Razorpay is wired). Thrown BEFORE any payment is taken, so Pay can safely
 * re-enable. The message is already a sentence the sheet can show.
 */
class PaymentsNotEnabledException : IllegalStateException("Live payments are not enabled in this build.")

/**
 * Create order -> pay -> poll until the server grants entitlement. Port of `useCheckout` in
 * apps/mobile/src/api/hooks.ts (with pendingOrders.ts and the sheet's Refresh).
 *
 * The client never decides that a purchase succeeded. It asks the server to price and create the
 * order (the client never sends a price, docs/04 T8), hands off to the gateway, then waits for the
 * server to report `entitlementGranted`, which only the payment webhook can set.
 *
 * In STUB mode (no merchant account yet) "pay" calls the development simulator, which runs the
 * same settlement code as the real webhook.
 *
 * The one rule everything here serves: once money may have moved, never surface an error that
 * would re-enable Pay. An outcome we couldn't confirm comes back as PENDING with the order id,
 * and the sheet offers Refresh instead.
 */
@Singleton
class CheckoutRepository internal constructor(
    private val api: TimesHealthApi,
    private val pendingOrders: PendingOrders,
    private val cache: ResponseCache,
    private val timeSource: TimeSource,
) {

    @Inject
    constructor(api: TimesHealthApi, pendingOrders: PendingOrders, cache: ResponseCache) :
        this(api, pendingOrders, cache, TimeSource.Monotonic)

    /**
     * One checkout at a time. RN relied on the sheet disabling Pay while a payment ran; holding
     * the rule here as well means a double tap that slips past the UI waits for the first attempt
     * (and then finds its pending order) instead of racing it to the gateway.
     */
    private val inProgress = Mutex()

    /**
     * Buys [request]'s product and returns the order's status: granted, FAILED, PAID_NOT_GRANTED
     * (refund flagged), or still settling (PENDING/PAID/CREATED; Pay must stay off).
     *
     * @throws ApiRequestException when the server refuses to create the order (sold out, already
     *   registered, bad referral code, offline...). Nothing was paid; the user can fix and retry.
     * @throws PaymentsNotEnabledException for a non-STUB gateway, before paying.
     */
    suspend fun checkout(request: CreateOrderRequest): OrderStatusResponse = inProgress.withLock {
        val status = runCheckout(request)
        if (status.entitlementGranted) cache.invalidate(CacheKeys.AfterPurchase)
        status
    }

    /**
     * Re-checks an order that was still settling when polling stopped (the sheet's Refresh, and
     * the sheet reopening on a [PendingOrders] entry). A settled order is forgotten; a granted one
     * refreshes everything that resolves against entitlement.
     *
     * @throws ApiRequestException when the status couldn't be fetched ("couldn't check, try
     *   again"); the order stays pending.
     */
    suspend fun refresh(orderId: String): OrderStatusResponse {
        val status = api.orderStatus(orderId)
        if (outcomeOf(status.status.name, status.entitlementGranted) != CheckoutOutcome.SETTLING) {
            pendingOrders.clearOrder(orderId)
        }
        if (status.entitlementGranted) cache.invalidate(CacheKeys.AfterPurchase)
        return status
    }

    /** The unconfirmed earlier order for [request], if any: reopen the sheet on it, Pay held back. */
    fun pendingOrder(request: CreateOrderRequest): PendingOrder? = pendingOrders.forRequest(request)

    private suspend fun runCheckout(request: CreateOrderRequest): OrderStatusResponse {
        val key = request.purchaseKey()

        // An earlier order for the same thing that never confirmed (the sheet was closed
        // mid-settle): re-check it rather than charging again.
        val earlier = pendingOrders[key]
        var order = earlier
        if (earlier != null) {
            val prior = attempt { api.orderStatus(earlier.orderId) }
                ?: return unconfirmed(earlier.orderId)
            if (prior.isSettled()) {
                pendingOrders.clear(key, earlier)
                // A failed payment took nothing: a fresh attempt is safe.
                if (prior.status != OrderStatus.FAILED) return prior
                order = null
            } else if (prior.status != OrderStatus.CREATED) {
                // Paid or in flight at the gateway: wait for it, never pay twice.
                return prior
            }
            // CREATED = never paid: complete THIS order (idempotent at the gateway).
        }

        // Refusals from createOrder (sold out, already registered, bad referral code) are errors:
        // nothing has been paid, so the user can fix it and retry.
        val payable: PendingOrder = order
            ?: api.createOrder(request).let { PendingOrder(it.orderId, it.gateway) }
        val orderId = payable.orderId

        // From here on money may have moved.
        pendingOrders[key] = payable

        if (payable.gateway == PaymentGateway.STUB) {
            attempt { api.simulatePayment(orderId) } ?: return unconfirmed(orderId)
        } else {
            // TODO(payments): open Razorpay checkout with the order's gateway id. The webhook
            // grants entitlement; we only poll. Thrown before any payment is taken, so Pay can
            // safely re-enable.
            pendingOrders.clear(key, payable)
            throw PaymentsNotEnabledException()
        }

        // Poll briefly for the webhook to land: with a real gateway this covers the gap between
        // paying and the webhook arriving. A failed poll is a blip, not an outcome. Bounded by
        // elapsed time, not attempts, so the sheet is never stuck for minutes on a slow network.
        var last = unconfirmed(orderId)
        val deadline = timeSource.markNow() + SETTLE_WAIT
        while (deadline.hasNotPassedNow()) {
            val status = attempt { api.orderStatus(orderId) }
            if (status != null) {
                last = status
                if (status.isSettled()) {
                    pendingOrders.clear(key, payable)
                    return status
                }
            }
            delay(POLL_INTERVAL)
        }
        return last
    }

    private fun unconfirmed(orderId: String) =
        OrderStatusResponse(orderId = orderId, status = OrderStatus.PENDING, entitlementGranted = false)

    private fun OrderStatusResponse.isSettled(): Boolean = isOrderSettled(status.name, entitlementGranted)

    /**
     * Runs a call made after money may have moved: any failure is "couldn't confirm" (null),
     * never an exception that would reach the sheet and re-enable Pay. Cancellation still stops.
     */
    private inline fun <T> attempt(call: () -> T): T? =
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    companion object {
        /** How long checkout waits for a payment to settle before offering Refresh. */
        val SETTLE_WAIT: Duration = 20.seconds

        /** The gap between status polls while settling. */
        val POLL_INTERVAL: Duration = 800.milliseconds
    }
}
