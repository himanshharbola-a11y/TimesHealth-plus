package timeshealth.app.core.integrations.subscription

import timeshealth.app.core.model.ProductType
import timeshealth.app.core.model.RaceTier

/**
 * PLUG-IN POINT — subscriptions and payments.
 *
 * Everything that sells something (the yoga paywall, race registration, the
 * Premium upgrade, paid workshop seats) calls [purchase] and reacts to the
 * [PurchaseOutcome]. Screens never know which payment system is behind it.
 *
 * TODAY: [ServerCheckoutSubscriptionProvider] — orders are created and
 * "paid" (simulated) on our own server, which then grants access. It goes
 * through the same order states a real payment does, so every screen state
 * (success, failed, confirming, refund) is exercised before real money flows.
 *
 * HOW TO PLUG IN THE TIL SUBSCRIPTION SDK
 *  1. Add the SDK dependency to app/build.gradle.kts.
 *  2. Write `class TilSubscriptionProvider @Inject constructor(...) : SubscriptionProvider`
 *     in the app module (it needs an Activity — use [PurchaseHost.activity]):
 *       - [purchase]: map [PurchaseRequest] to the SDK's plan/product codes
 *         (product line "TH": yoga plan "TYOG", …), launch the SDK's checkout,
 *         and translate its result into a [PurchaseOutcome].
 *       - After a success, the server must learn about it from the
 *         subscription platform itself (server-to-server webhook or
 *         entitlement check), never from the phone. Return
 *         [PurchaseOutcome.Confirming] until the app's session shows access.
 *       - [restore]: the SDK's "restore purchases", then refresh the session.
 *  3. In app/.../wiring/IntegrationsModule.kt change ONE line:
 *       `@Binds fun subscriptions(impl: TilSubscriptionProvider): SubscriptionProvider`
 * Nothing else changes.
 */
interface SubscriptionProvider {
    /** Short name for logs and analytics ("server-checkout", "til-subscription-sdk"). */
    val name: String

    /**
     * Buys [request]. Must not throw for ordinary outcomes (declined, cancelled,
     * sold out) — those are [PurchaseOutcome]s. Throws only for programming errors.
     */
    suspend fun purchase(host: PurchaseHost, request: PurchaseRequest): PurchaseOutcome

    /** Re-checks an outcome that was [PurchaseOutcome.Confirming] (the "Refresh" button). */
    suspend fun refresh(reference: String): PurchaseOutcome

    /** "Restore purchases": re-sync what this account owns. */
    suspend fun restore()
}

/**
 * What the payment UI needs from the screen. Real SDKs show their own
 * checkout UI and need the current Activity; the server mock needs nothing.
 */
interface PurchaseHost {
    /** The foreground Activity, for SDKs that present their own UI. */
    val activity: android.app.Activity?
}

/** What is being bought. Prices are NEVER sent from the app: the server/SDK prices it. */
data class PurchaseRequest(
    val productType: ProductType,
    val productId: String,
    /** Marathon registration / upgrade only. */
    val eventId: String? = null,
    val category: String? = null,
    val tier: RaceTier? = null,
    /** Refer & Win: a friend's code typed at checkout. */
    val referralCode: String? = null,
)

/** Every way a purchase can end, as the screens show it. */
sealed interface PurchaseOutcome {
    /** Paid and access granted. */
    data object Granted : PurchaseOutcome

    /**
     * Payment may have been taken but access isn't confirmed yet. The screen
     * shows "Confirming…" with Refresh and must NOT offer to pay again.
     */
    data class Confirming(val reference: String) : PurchaseOutcome

    /** Nothing was charged; the user can try again. */
    data class Failed(val message: String) : PurchaseOutcome

    /** The user closed the payment sheet. Nothing was charged. */
    data object Cancelled : PurchaseOutcome

    /**
     * Refused BEFORE paying (sold out, already registered, invalid referral
     * code…). [code] is the server's error code so the screen can explain it.
     */
    data class Refused(val code: String, val message: String) : PurchaseOutcome

    /** Paid, but the item was gone by then: flagged for a full refund. */
    data class RefundFlagged(val reference: String) : PurchaseOutcome
}
