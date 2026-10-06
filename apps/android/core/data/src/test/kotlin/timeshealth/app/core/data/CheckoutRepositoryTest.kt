package timeshealth.app.core.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Test
import timeshealth.app.core.data.cache.CacheKeys
import timeshealth.app.core.data.cache.ResponseCache
import timeshealth.app.core.data.checkout.CheckoutRepository
import timeshealth.app.core.data.checkout.PaymentsNotEnabledException
import timeshealth.app.core.data.checkout.PendingOrder
import timeshealth.app.core.data.checkout.PendingOrders
import timeshealth.app.core.model.OrderStatus
import timeshealth.app.core.model.OrderStatusResponse
import timeshealth.app.core.model.PaymentGateway
import timeshealth.app.core.model.SettleResult
import timeshealth.app.core.model.SimulatePaymentResponse
import timeshealth.app.core.network.ApiRequestException

/**
 * Every branch of the checkout flow (port of RN useCheckout). The rule under test throughout: once
 * money may have moved, nothing may come back as an error that would re-enable Pay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutRepositoryTest {

    private val api = FakeTimesHealthApi()
    private val pending = PendingOrders()

    private fun TestScope.repo(cache: ResponseCache = ResponseCache(testTimeSource)) =
        CheckoutRepository(api, pending, cache, testTimeSource)

    private val paid = SimulatePaymentResponse(ok = true, result = SettleResult.GRANTED)

    @Test
    fun `refused at creation - throws, nothing pending, so Pay can re-enable`() = runTest {
        api.onCreateOrder = { throw apiError(409, "SOLD_OUT") }

        val error = expectThrows<ApiRequestException> { repo().checkout(orderRequest()) }

        assertThat(error.code).isEqualTo("SOLD_OUT")
        assertThat(pending.forRequest(orderRequest())).isNull()
        assertThat(api.callsTo("simulatePayment")).isEmpty()
    }

    @Test
    fun `offline at creation - throws too, nothing was paid`() = runTest {
        api.onCreateOrder = { throw networkDown }

        expectThrows<ApiRequestException> { repo().checkout(orderRequest()) }

        assertThat(pending.forRequest(orderRequest())).isNull()
    }

    @Test
    fun `simulate fails - PENDING with the order id, order kept pending, no error`() = runTest {
        api.onCreateOrder = { created("o1") }
        api.onSimulatePayment = { throw networkDown }

        val result = repo().checkout(orderRequest())

        assertThat(result).isEqualTo(status("o1", OrderStatus.PENDING))
        assertThat(pending.forRequest(orderRequest())).isEqualTo(PendingOrder("o1", PaymentGateway.STUB))
        assertThat(api.callsTo("orderStatus")).isEmpty()
    }

    @Test
    fun `paid and granted - returns it, forgets the pending order, refreshes entitlement data`() = runTest {
        val cache = ResponseCache(testTimeSource)
        (CacheKeys.AfterPurchase + CacheKeys.Content).forEach { cache.seed(it) }
        api.onCreateOrder = { created("o1") }
        api.onSimulatePayment = { paid }
        api.onOrderStatus = { status(it, OrderStatus.PAID, granted = true) }

        val result = repo(cache).checkout(orderRequest())

        assertThat(result).isEqualTo(status("o1", OrderStatus.PAID, granted = true))
        assertThat(pending.forRequest(orderRequest())).isNull()
        CacheKeys.AfterPurchase.forEach { assertThat(cache.wasInvalidated(it)).isTrue() }
        // Only what resolves against entitlement, not every cached query.
        assertThat(cache.wasInvalidated(CacheKeys.Content)).isFalse()
    }

    @Test
    fun `a failed poll is a blip, not an outcome`() = runTest {
        api.onCreateOrder = { created("o1") }
        api.onSimulatePayment = { paid }
        val answers = ArrayDeque(
            listOf<() -> OrderStatusResponse>(
                { throw networkDown },
                { status("o1", OrderStatus.PAID) },
                { throw apiError(503, "UNAVAILABLE") },
                { status("o1", OrderStatus.PAID, granted = true) },
            ),
        )
        api.onOrderStatus = { answers.removeFirst()() }

        val result = repo().checkout(orderRequest())

        assertThat(result.entitlementGranted).isTrue()
        assertThat(api.callsTo("orderStatus")).hasSize(4)
        assertThat(currentTime).isEqualTo(3 * CheckoutRepository.POLL_INTERVAL.inWholeMilliseconds)
    }

    @Test
    fun `settle wait is bounded at 20 s - returns the last status, order stays pending`() = runTest {
        api.onCreateOrder = { created("o1") }
        api.onSimulatePayment = { paid }
        api.onOrderStatus = { status(it, OrderStatus.PAID) }

        val result = repo().checkout(orderRequest())

        assertThat(result).isEqualTo(status("o1", OrderStatus.PAID))
        assertThat(currentTime).isEqualTo(20_000)
        // Polls at 0, 0.8 s, ..., 19.2 s.
        assertThat(api.callsTo("orderStatus")).hasSize(25)
        assertThat(pending.forRequest(orderRequest())?.orderId).isEqualTo("o1")
    }

    @Test
    fun `deadline with every poll failing - reports PENDING, never an error`() = runTest {
        api.onCreateOrder = { created("o1") }
        api.onSimulatePayment = { paid }
        api.onOrderStatus = { throw networkDown }

        val result = repo().checkout(orderRequest())

        assertThat(result).isEqualTo(status("o1", OrderStatus.PENDING))
        assertThat(pending.forRequest(orderRequest())?.orderId).isEqualTo("o1")
    }

    @Test
    fun `resume - an unconfirmed earlier order is re-checked, never charged again`() = runTest {
        val repo = repo()
        api.onCreateOrder = { created("o1") }
        api.onSimulatePayment = { throw networkDown }
        repo.checkout(orderRequest())

        // Still offline: the re-check fails too. Report the same order as unconfirmed.
        api.onOrderStatus = { throw networkDown }
        val again = repo.checkout(orderRequest())

        assertThat(again).isEqualTo(status("o1", OrderStatus.PENDING))
        assertThat(api.callsTo("createOrder")).hasSize(1)
        assertThat(api.callsTo("simulatePayment")).hasSize(1)
    }

    @Test
    fun `resume - paid or in flight at the gateway, wait for it, never pay twice`() = runTest {
        val repo = repo()
        api.onCreateOrder = { created("o1") }
        api.onSimulatePayment = { throw networkDown }
        repo.checkout(orderRequest())

        api.onOrderStatus = { status(it, OrderStatus.PAID) }
        val again = repo.checkout(orderRequest())

        assertThat(again).isEqualTo(status("o1", OrderStatus.PAID))
        assertThat(api.callsTo("createOrder")).hasSize(1)
        assertThat(api.callsTo("simulatePayment")).hasSize(1)
        assertThat(pending.forRequest(orderRequest())?.orderId).isEqualTo("o1")
    }

    @Test
    fun `resume - CREATED was never paid, so pay that same order`() = runTest {
        val repo = repo()
        api.onCreateOrder = { created("o1") }
        api.onSimulatePayment = { throw networkDown }
        repo.checkout(orderRequest())

        val statuses = ArrayDeque(listOf(status("o1", OrderStatus.CREATED), status("o1", OrderStatus.PAID, granted = true)))
        api.onOrderStatus = { statuses.removeFirst() }
        api.onSimulatePayment = { paid }
        val again = repo.checkout(orderRequest())

        assertThat(again.entitlementGranted).isTrue()
        assertThat(api.callsTo("createOrder")).hasSize(1)
        assertThat(api.callsTo("simulatePayment")).containsExactly("simulatePayment o1", "simulatePayment o1")
        assertThat(pending.forRequest(orderRequest())).isNull()
    }

    @Test
    fun `resume - FAILED took nothing, so a fresh order is safe`() = runTest {
        val repo = repo()
        api.onCreateOrder = { created("o1") }
        api.onSimulatePayment = { throw networkDown }
        repo.checkout(orderRequest())

        api.onOrderStatus = { if (it == "o1") status("o1", OrderStatus.FAILED) else status(it, OrderStatus.PAID, granted = true) }
        api.onCreateOrder = { created("o2") }
        api.onSimulatePayment = { paid }
        val again = repo.checkout(orderRequest())

        assertThat(again).isEqualTo(status("o2", OrderStatus.PAID, granted = true))
        assertThat(api.callsTo("createOrder")).hasSize(2)
        assertThat(api.callsTo("simulatePayment")).containsExactly("simulatePayment o1", "simulatePayment o2")
        assertThat(pending.forRequest(orderRequest())).isNull()
    }

    @Test
    fun `resume - already granted meanwhile, report it and buy nothing`() = runTest {
        val cache = ResponseCache(testTimeSource)
        cache.seed(CacheKeys.Home)
        val repo = repo(cache)
        api.onCreateOrder = { created("o1") }
        api.onSimulatePayment = { throw networkDown }
        repo.checkout(orderRequest())

        api.onOrderStatus = { status(it, OrderStatus.PAID, granted = true) }
        val again = repo.checkout(orderRequest())

        assertThat(again.entitlementGranted).isTrue()
        assertThat(api.callsTo("createOrder")).hasSize(1)
        assertThat(pending.forRequest(orderRequest())).isNull()
        assertThat(cache.wasInvalidated(CacheKeys.Home)).isTrue()
    }

    @Test
    fun `PAID_NOT_GRANTED - settled, refund flagged, order forgotten, nothing to refresh`() = runTest {
        val cache = ResponseCache(testTimeSource)
        cache.seed(CacheKeys.Session)
        api.onCreateOrder = { created("o1") }
        api.onSimulatePayment = { SimulatePaymentResponse(ok = true, result = SettleResult.NEEDS_REFUND) }
        api.onOrderStatus = { status(it, OrderStatus.PAID_NOT_GRANTED) }

        val result = repo(cache).checkout(orderRequest())

        assertThat(result).isEqualTo(status("o1", OrderStatus.PAID_NOT_GRANTED))
        assertThat(pending.forRequest(orderRequest())).isNull()
        assertThat(cache.wasInvalidated(CacheKeys.Session)).isFalse()
        assertThat(api.callsTo("orderStatus")).hasSize(1)
    }

    @Test
    fun `FAILED from the poll - settled, order forgotten so Pay is safe again`() = runTest {
        api.onCreateOrder = { created("o1") }
        api.onSimulatePayment = { SimulatePaymentResponse(ok = true, result = SettleResult.FAILED) }
        api.onOrderStatus = { status(it, OrderStatus.FAILED) }

        val result = repo().checkout(orderRequest())

        assertThat(result.status).isEqualTo(OrderStatus.FAILED)
        assertThat(pending.forRequest(orderRequest())).isNull()
    }

    @Test
    fun `an unknown status is still settling - never treated as failed`() = runTest {
        api.onCreateOrder = { created("o1") }
        api.onSimulatePayment = { paid }
        api.onOrderStatus = { status(it, OrderStatus.UNKNOWN) }

        val result = repo().checkout(orderRequest())

        assertThat(result.status).isEqualTo(OrderStatus.UNKNOWN)
        assertThat(pending.forRequest(orderRequest())?.orderId).isEqualTo("o1")
    }

    @Test
    fun `a live gateway throws before paying and forgets the order`() = runTest {
        for (gateway in listOf(PaymentGateway.RAZORPAY, PaymentGateway.UNKNOWN)) {
            api.onCreateOrder = { created("o-$gateway", gateway) }

            expectThrows<PaymentsNotEnabledException> { repo().checkout(orderRequest()) }

            assertThat(pending.forRequest(orderRequest())).isNull()
        }
        assertThat(api.callsTo("simulatePayment")).isEmpty()
        assertThat(api.callsTo("orderStatus")).isEmpty()
    }

    @Test
    fun `a referral code doesn't make it a different purchase`() = runTest {
        val repo = repo()
        api.onCreateOrder = { created("o1") }
        api.onSimulatePayment = { throw networkDown }
        repo.checkout(orderRequest())

        api.onOrderStatus = { status(it, OrderStatus.PENDING) }
        val withCode = repo.checkout(orderRequest(referralCode = "FRIEND5"))

        assertThat(withCode.orderId).isEqualTo("o1")
        assertThat(api.callsTo("createOrder")).hasSize(1)
        assertThat(repo.pendingOrder(orderRequest(referralCode = "OTHER"))?.orderId).isEqualTo("o1")
    }

    @Test
    fun `a second tap while paying waits, then re-checks the first order`() = runTest {
        val repo = repo()
        val gate = CompletableDeferred<Unit>()
        api.onCreateOrder = { created("o1") }
        api.onSimulatePayment = {
            gate.await()
            throw networkDown
        }
        api.onOrderStatus = { status(it, OrderStatus.PENDING) }

        val first = async { repo.checkout(orderRequest()) }
        val second = async { repo.checkout(orderRequest()) }
        runCurrent()
        assertThat(api.callsTo("createOrder")).hasSize(1)
        gate.complete(Unit)

        assertThat(first.await()).isEqualTo(status("o1", OrderStatus.PENDING))
        assertThat(second.await()).isEqualTo(status("o1", OrderStatus.PENDING))
        assertThat(api.callsTo("createOrder")).hasSize(1)
        assertThat(api.callsTo("simulatePayment")).hasSize(1)
    }

    @Test
    fun `refresh - settled forgets the order and refreshes entitlement data`() = runTest {
        val cache = ResponseCache(testTimeSource)
        cache.seed(CacheKeys.Home)
        val repo = repo(cache)
        api.onCreateOrder = { created("o1") }
        api.onSimulatePayment = { throw networkDown }
        repo.checkout(orderRequest())

        api.onOrderStatus = { status(it, OrderStatus.PAID, granted = true) }
        val refreshed = repo.refresh("o1")

        assertThat(refreshed.entitlementGranted).isTrue()
        assertThat(pending.forRequest(orderRequest())).isNull()
        assertThat(cache.wasInvalidated(CacheKeys.Home)).isTrue()
    }

    @Test
    fun `refresh - still settling keeps the order, a failed check throws and keeps it too`() = runTest {
        val repo = repo()
        api.onCreateOrder = { created("o1") }
        api.onSimulatePayment = { throw networkDown }
        repo.checkout(orderRequest())

        api.onOrderStatus = { status(it, OrderStatus.PENDING) }
        assertThat(repo.refresh("o1").status).isEqualTo(OrderStatus.PENDING)
        assertThat(pending.forRequest(orderRequest())?.orderId).isEqualTo("o1")

        api.onOrderStatus = { throw networkDown }
        expectThrows<ApiRequestException> { repo.refresh("o1") }
        assertThat(pending.forRequest(orderRequest())?.orderId).isEqualTo("o1")
    }
}
