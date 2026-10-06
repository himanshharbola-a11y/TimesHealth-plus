package timeshealth.app.ui.checkout

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timeshealth.app.core.integrations.analytics.Analytics
import timeshealth.app.core.integrations.analytics.AnalyticsEvents
import timeshealth.app.core.integrations.subscription.PurchaseHost
import timeshealth.app.core.integrations.subscription.PurchaseOutcome
import timeshealth.app.core.integrations.subscription.PurchaseRequest
import timeshealth.app.core.integrations.subscription.SubscriptionProvider
import timeshealth.app.core.model.ProductType

/** What is being bought, as the sheet shows it. The price is a DISPLAY estimate; the server prices it. */
@Immutable
data class CheckoutItem(
    val request: PurchaseRequest,
    val title: String,
    val subtitle: String,
    val displayPaise: Long,
)

/** The sheet's state. Every way a purchase can end has its own words. */
@Immutable
sealed interface CheckoutState {
    data object Idle : CheckoutState
    data object Paying : CheckoutState
    data object Granted : CheckoutState

    /** Money may have moved: Pay stays off, Refresh re-checks (no double charge). */
    data class Confirming(val reference: String, val refreshing: Boolean = false, val refreshFailed: Boolean = false) : CheckoutState

    /** Not charged; Pay again is safe. */
    data class Failed(val message: String) : CheckoutState

    /** Refused before paying: a sentence the user can act on. [onReferralField] shows it on the code field. */
    data class Refused(val message: String, val onReferralField: Boolean) : CheckoutState

    data object RefundFlagged : CheckoutState
}

/**
 * The confirm-and-pay sheet's logic (port of components/CheckoutSheet.tsx),
 * through the [SubscriptionProvider] plug-in point: the server checkout today,
 * the TIL Subscription SDK later, with no change here.
 */
@HiltViewModel
class CheckoutViewModel @Inject constructor(
    private val subscriptions: SubscriptionProvider,
    private val analytics: Analytics,
) : ViewModel() {

    private val _state = MutableStateFlow<CheckoutState>(CheckoutState.Idle)
    val state: StateFlow<CheckoutState> = _state.asStateFlow()

    /** A new item opens clean (a referral code typed for one race isn't another's). */
    fun reset() {
        if (_state.value != CheckoutState.Paying) _state.value = CheckoutState.Idle
    }

    fun pay(host: PurchaseHost, item: CheckoutItem, referralCode: String?) {
        if (_state.value == CheckoutState.Paying || _state.value is CheckoutState.Confirming) return
        val request = if (item.request.productType == ProductType.MARATHON_REGISTRATION && !referralCode.isNullOrBlank()) {
            item.request.copy(referralCode = referralCode.trim())
        } else {
            item.request
        }
        _state.value = CheckoutState.Paying
        analytics.track(AnalyticsEvents.purchaseStarted(request.productType.name, request.productId))
        viewModelScope.launch {
            val outcome = subscriptions.purchase(host, request)
            analytics.track(AnalyticsEvents.purchaseResult(request.productType.name, request.productId, outcome::class.simpleName.orEmpty()))
            _state.value = stateOf(outcome)
        }
    }

    /** "Refresh" on a purchase still being confirmed. */
    fun refresh() {
        val confirming = _state.value as? CheckoutState.Confirming ?: return
        if (confirming.refreshing) return
        _state.value = confirming.copy(refreshing = true, refreshFailed = false)
        viewModelScope.launch {
            val next = stateOf(subscriptions.refresh(confirming.reference))
            _state.value = if (next is CheckoutState.Confirming) next.copy(refreshFailed = false) else next
        }
    }

    /** Close is held back while money is moving: the result must land. */
    val canClose: Boolean get() = _state.value != CheckoutState.Paying

    companion object {
        /** Server refusal codes as sentences a person can act on. */
        val REFUSAL_COPY: Map<String, String> = mapOf(
            "ALREADY_REGISTERED" to "You’re already registered for this — check your races or workshops.",
            "ALREADY_PREMIUM" to "You already hold a Premium entry for this race.",
            "SOLD_OUT" to "Premium has sold out for this distance. Classic entries may still be open.",
            "REGISTRATION_CLOSED" to "Registrations for this race have closed.",
            "UNAVAILABLE" to "This isn’t available to buy right now.",
            "WORKSHOP_FULL" to "This workshop is full.",
            "WORKSHOP_ENDED" to "This workshop has already ended.",
            "FREE_FOR_YOU" to "This workshop is free with your membership — register without paying.",
            "NOT_REGISTERED" to "You need a registration for this race before you can upgrade it.",
            "UNKNOWN_PLAN" to "This plan isn’t available any more.",
        )

        /** Shown on the referral field itself: the user's to fix before paying. */
        val CODE_COPY: Map<String, String> = mapOf(
            "INVALID_REFERRAL_CODE" to "That referral code isn’t valid. Check it, or leave the field empty.",
            "OWN_REFERRAL_CODE" to "That’s your own code — use a friend’s, or leave the field empty.",
        )

        fun stateOf(outcome: PurchaseOutcome): CheckoutState = when (outcome) {
            PurchaseOutcome.Granted -> CheckoutState.Granted
            is PurchaseOutcome.Confirming -> CheckoutState.Confirming(outcome.reference)
            is PurchaseOutcome.Failed -> CheckoutState.Failed(outcome.message)
            PurchaseOutcome.Cancelled -> CheckoutState.Idle
            is PurchaseOutcome.RefundFlagged -> CheckoutState.RefundFlagged
            is PurchaseOutcome.Refused -> CODE_COPY[outcome.code]?.let { CheckoutState.Refused(it, onReferralField = true) }
                ?: CheckoutState.Refused(REFUSAL_COPY[outcome.code] ?: "This couldn’t be bought right now. You have not been charged.", onReferralField = false)
        }
    }
}
