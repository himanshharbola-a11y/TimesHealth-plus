package timeshealth.app.ui

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import timeshealth.app.MainDispatcherRule
import timeshealth.app.core.integrations.analytics.Analytics
import timeshealth.app.core.integrations.subscription.PurchaseHost
import timeshealth.app.core.integrations.subscription.PurchaseOutcome
import timeshealth.app.core.integrations.subscription.PurchaseRequest
import timeshealth.app.core.integrations.subscription.SubscriptionProvider
import timeshealth.app.core.model.MarathonEntitlement
import timeshealth.app.core.model.MarathonEvent
import timeshealth.app.core.model.ProductType
import timeshealth.app.core.model.RaceDistanceOption
import timeshealth.app.core.model.RaceLifecycleStatus
import timeshealth.app.core.model.RaceTier
import timeshealth.app.ui.checkout.CheckoutItem
import timeshealth.app.ui.checkout.CheckoutState
import timeshealth.app.ui.checkout.CheckoutViewModel
import timeshealth.app.ui.marathon.andList
import timeshealth.app.ui.marathon.fromPaise
import timeshealth.app.ui.marathon.racesUi

@OptIn(ExperimentalCoroutinesApi::class)
class MarathonTest {

    @get:Rule val main = MainDispatcherRule()

    private fun event(id: String, status: RaceLifecycleStatus? = null, tier: RaceTier = RaceTier.CLASSIC) = MarathonEvent(
        id = id, name = "Race $id", city = "Delhi", venue = "JLN", imageUrl = "", startsAt = "2026-11-01T00:30:00.000Z",
        flagOffTime = "6:00 AM",
        distanceOptions = listOf(
            RaceDistanceOption("5K", "5K", mapOf(RaceTier.CLASSIC to 99_900L, RaceTier.PREMIUM to 199_900L), premiumSoldOut = false, registrationOpen = true),
            RaceDistanceOption("21K", "Half", mapOf(RaceTier.CLASSIC to 197_700L, RaceTier.PREMIUM to 299_900L), premiumSoldOut = true, registrationOpen = true),
        ),
        registrationOpen = true,
        registration = status?.let { MarathonEntitlement(id, "Race $id", "REF-$id", tier, "21K", null, it, "2026-09-01T00:00:00.000Z") },
    )

    // ── The Races tab's sections ─────────────────────────────────────────────

    @Test
    fun `a free user gets the nearest edition sold large and the rest below`() {
        val ui = racesUi(listOf(event("a"), event("b"), event("c")))
        assertThat(ui.myNext).isNull()
        assertThat(ui.hero?.id).isEqualTo("a")
        assertThat(ui.rest.map { it.id }).containsExactly("b", "c").inOrder()
        assertThat(ui.hasEntries).isFalse()
    }

    @Test
    fun `a registrant sees their race first, finished races apart, and no hero`() {
        val ui = racesUi(
            listOf(event("a"), event("mine", RaceLifecycleStatus.UPCOMING), event("done", RaceLifecycleStatus.COMPLETED), event("b"), event("later", RaceLifecycleStatus.UPCOMING, RaceTier.PREMIUM)),
        )
        assertThat(ui.myNext?.id).isEqualTo("mine")
        assertThat(ui.myLater.map { it.id }).containsExactly("later")
        assertThat(ui.past.map { it.id }).containsExactly("done")
        assertThat(ui.hero).isNull()
        assertThat(ui.rest.map { it.id }).containsExactly("a", "b").inOrder()
        // The free upgrade only lands on an upcoming CLASSIC entry.
        assertThat(ui.claimTargetId).isEqualTo("mine")
    }

    @Test
    fun `from price is the cheapest classic entry`() {
        assertThat(fromPaise(event("a"))).isEqualTo(99_900L)
        assertThat(andList(listOf("Parking", "Kit delivery", "Start wave"))).isEqualTo("Parking, Kit delivery & Start wave")
        assertThat(andList(listOf("Parking"))).isEqualTo("Parking")
    }

    // ── Checkout through the subscription plug-in point ──────────────────────

    private class FakeSubscriptions : SubscriptionProvider {
        override val name = "fake"
        var outcome: PurchaseOutcome = PurchaseOutcome.Granted
        var gate: CompletableDeferred<Unit>? = null
        val requests = mutableListOf<PurchaseRequest>()
        var refreshOutcome: PurchaseOutcome = PurchaseOutcome.Granted
        override suspend fun purchase(host: PurchaseHost, request: PurchaseRequest): PurchaseOutcome {
            requests += request
            gate?.await()
            return outcome
        }
        override suspend fun refresh(reference: String) = refreshOutcome
        override suspend fun restore() = Unit
    }

    private val host = object : PurchaseHost { override val activity: Activity? = null }
    private val item = CheckoutItem(
        PurchaseRequest(ProductType.MARATHON_REGISTRATION, "e1:21K:CLASSIC", eventId = "e1", category = "21K", tier = RaceTier.CLASSIC),
        "Race e1 · Half", "Classic entry · Delhi", 197_700L,
    )

    @Test
    fun `a granted purchase, with the friend's referral code sent along`() = runTest {
        val subs = FakeSubscriptions()
        val vm = CheckoutViewModel(subs, Analytics(emptySet()))
        vm.pay(host, item, referralCode = " TH4F2KQ ")
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(CheckoutState.Granted)
        assertThat(subs.requests.single().referralCode).isEqualTo("TH4F2KQ")
    }

    @Test
    fun `while paying, close and a second Pay are held back`() = runTest {
        val subs = FakeSubscriptions().apply { gate = CompletableDeferred() }
        val vm = CheckoutViewModel(subs, Analytics(emptySet()))
        vm.pay(host, item, null)
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(CheckoutState.Paying)
        assertThat(vm.canClose).isFalse()
        vm.pay(host, item, null)
        subs.gate!!.complete(Unit)
        advanceUntilIdle()
        assertThat(subs.requests).hasSize(1)
    }

    @Test
    fun `an unconfirmed payment never offers Pay again, and Refresh settles it`() = runTest {
        val subs = FakeSubscriptions().apply { outcome = PurchaseOutcome.Confirming("o1") }
        val vm = CheckoutViewModel(subs, Analytics(emptySet()))
        vm.pay(host, item, null)
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(CheckoutState.Confirming("o1"))
        vm.pay(host, item, null) // ignored: money may have moved
        advanceUntilIdle()
        assertThat(subs.requests).hasSize(1)
        vm.refresh()
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(CheckoutState.Granted)
    }

    @Test
    fun `refusals become sentences, referral-code ones on the field`() {
        assertThat(CheckoutViewModel.stateOf(PurchaseOutcome.Refused("INVALID_REFERRAL_CODE", "x")))
            .isEqualTo(CheckoutState.Refused(CheckoutViewModel.CODE_COPY.getValue("INVALID_REFERRAL_CODE"), onReferralField = true))
        assertThat(CheckoutViewModel.stateOf(PurchaseOutcome.Refused("SOLD_OUT", "x")))
            .isEqualTo(CheckoutState.Refused(CheckoutViewModel.REFUSAL_COPY.getValue("SOLD_OUT"), onReferralField = false))
        assertThat((CheckoutViewModel.stateOf(PurchaseOutcome.Refused("SOMETHING_NEW", "x")) as CheckoutState.Refused).message)
            .contains("not been charged")
        assertThat(CheckoutViewModel.stateOf(PurchaseOutcome.RefundFlagged("o1"))).isEqualTo(CheckoutState.RefundFlagged)
        assertThat(CheckoutViewModel.stateOf(PurchaseOutcome.Cancelled)).isEqualTo(CheckoutState.Idle)
    }
}
