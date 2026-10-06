package timeshealth.app.core.domain

/*
 * Checkout outcomes and the "no double charge" key. Ports of outcomeOf in
 * apps/mobile/src/components/CheckoutSheet.tsx, isSettled in
 * apps/mobile/src/api/hooks.ts and purchaseKey in
 * apps/mobile/src/lib/pendingOrders.ts. Entitlement is granted on the gateway
 * webhook, never on client confirmation (docs/04 T8), so the app can only
 * read the order's status and react to it.
 */

/** What the checkout sheet shows for an order. */
enum class CheckoutOutcome {
    /** No order status yet: the Pay screen. */
    IDLE,

    /** The product is on the account: "You're all set". */
    GRANTED,

    /** The payment failed: nothing was taken, so paying again is safe. */
    FAILED,

    /**
     * PAID_NOT_GRANTED: money arrived but the product was gone by then (seat
     * sold out, registration closed) — the order is flagged for a refund.
     */
    REFUND,

    /**
     * Anything else (CREATED, PENDING, PAID awaiting the webhook, or a status
     * a newer server adds): money may have moved, so Pay stays held back and
     * the sheet offers Refresh.
     */
    SETTLING,
}

/**
 * Classifies an order status. Granted wins over every status (the webhook
 * landed). An unknown status is SETTLING, never FAILED: treating it as failed
 * would re-enable Pay and risk a second charge.
 *
 * @param status the order's `status`, or null when there is no status
 *   response yet (→ [CheckoutOutcome.IDLE]).
 * @param entitlementGranted the order's `entitlementGranted`.
 */
fun outcomeOf(status: String?, entitlementGranted: Boolean): CheckoutOutcome = when {
    status == null -> CheckoutOutcome.IDLE
    entitlementGranted -> CheckoutOutcome.GRANTED
    status == "FAILED" -> CheckoutOutcome.FAILED
    status == "PAID_NOT_GRANTED" -> CheckoutOutcome.REFUND
    else -> CheckoutOutcome.SETTLING
}

/**
 * The order has reached a final state, so checkout stops polling and forgets
 * the pending order. Exactly "[outcomeOf] is not SETTLING".
 */
fun isOrderSettled(status: String, entitlementGranted: Boolean): Boolean =
    outcomeOf(status, entitlementGranted) != CheckoutOutcome.SETTLING

/**
 * What was bought, as one string — the key for the pending-order memory.
 *
 * The checkout sheet can be closed while an order is still settling, and the
 * buy button is still on screen (nothing has been granted yet). Without a key
 * a second tap would create — and charge — a second order. With it, "Pay" for
 * the same product re-checks the earlier order first.
 *
 * "productType|productId|eventId|category|tier", absent parts empty. The
 * referral code is deliberately NOT part of it: typing a code doesn't make it
 * a different purchase.
 */
fun purchaseKey(
    productType: String,
    productId: String,
    eventId: String? = null,
    category: String? = null,
    tier: String? = null,
): String = listOf(productType, productId, eventId ?: "", category ?: "", tier ?: "").joinToString("|")
