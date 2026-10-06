package timeshealth.app.core.data.checkout

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import timeshealth.app.core.domain.purchaseKey
import timeshealth.app.core.model.CreateOrderRequest
import timeshealth.app.core.model.PaymentGateway

/** An order whose payment may have gone through but isn't confirmed yet. */
data class PendingOrder(val orderId: String, val gateway: PaymentGateway)

/**
 * Orders whose payment may have gone through but isn't confirmed yet, keyed by what was bought.
 * Port of apps/mobile/src/lib/pendingOrders.ts.
 *
 * The checkout sheet can be closed while an order is still settling, and the buy button is still
 * on screen (nothing has been granted yet). Without this a second tap would create (and charge) a
 * second order. With it, Pay for the same product re-checks the earlier order first
 * ([CheckoutRepository.checkout]).
 *
 * Memory only: the server's idempotent order creation (an unpaid order is reused for 30 minutes)
 * covers an app restart. Cleared on sign-out so one user's checkout never resumes for another.
 */
@Singleton
class PendingOrders @Inject constructor() {

    private val pending = ConcurrentHashMap<String, PendingOrder>()

    operator fun get(key: String): PendingOrder? = pending[key]

    /** The unconfirmed order for this purchase, if any (the sheet reopens on it, Pay held back). */
    fun forRequest(request: CreateOrderRequest): PendingOrder? = pending[request.purchaseKey()]

    internal operator fun set(key: String, order: PendingOrder) {
        pending[key] = order
    }

    /** Forgets [key]'s order, but only if it is still [order]; a newer one is left alone. */
    internal fun clear(key: String, order: PendingOrder) {
        pending.remove(key, order)
    }

    /** Forgets whichever purchase [orderId] belongs to (it settled). */
    internal fun clearOrder(orderId: String) {
        pending.entries.removeIf { it.value.orderId == orderId }
    }

    /** Sign-out / identity change. */
    internal fun forgetAll() = pending.clear()
}

/** The pending-order key for this request; the referral code is deliberately not part of it. */
internal fun CreateOrderRequest.purchaseKey(): String =
    purchaseKey(productType.name, productId, eventId, category, tier?.name)
