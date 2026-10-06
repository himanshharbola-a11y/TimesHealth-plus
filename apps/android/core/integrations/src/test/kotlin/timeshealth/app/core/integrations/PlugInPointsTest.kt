package timeshealth.app.core.integrations

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import timeshealth.app.core.integrations.analytics.Analytics
import timeshealth.app.core.integrations.analytics.AnalyticsEvent
import timeshealth.app.core.integrations.analytics.AnalyticsTracker
import timeshealth.app.core.integrations.push.PushHandler
import timeshealth.app.core.integrations.push.PushMessage
import timeshealth.app.core.integrations.push.PushRouter
import timeshealth.app.core.integrations.subscription.PurchaseOutcome
import timeshealth.app.core.integrations.subscription.purchaseOutcomeOf
import timeshealth.app.core.integrations.video.DirectUrlResolver
import timeshealth.app.core.integrations.video.PlayableStream
import timeshealth.app.core.integrations.video.UnsupportedVideoProviderException
import timeshealth.app.core.integrations.video.VideoRef
import timeshealth.app.core.integrations.video.VideoSourceResolver
import timeshealth.app.core.integrations.video.Videos
import timeshealth.app.core.model.OrderStatus
import timeshealth.app.core.model.OrderStatusResponse

class PlugInPointsTest {

    // ── Subscriptions: the server checkout's order states → what screens show ──

    @Test fun `granted wins over every status`() {
        for (s in OrderStatus.entries) {
            assertThat(purchaseOutcomeOf(OrderStatusResponse("o1", s, entitlementGranted = true)))
                .isEqualTo(PurchaseOutcome.Granted)
        }
    }

    @Test fun `failed is safe to retry, refund is flagged`() {
        assertThat(purchaseOutcomeOf(OrderStatusResponse("o1", OrderStatus.FAILED, false)))
            .isInstanceOf(PurchaseOutcome.Failed::class.java)
        assertThat(purchaseOutcomeOf(OrderStatusResponse("o1", OrderStatus.PAID_NOT_GRANTED, false)))
            .isEqualTo(PurchaseOutcome.RefundFlagged("o1"))
    }

    @Test fun `anything unconfirmed is Confirming, never Failed (no double charge)`() {
        for (s in listOf(OrderStatus.CREATED, OrderStatus.PENDING, OrderStatus.PAID, OrderStatus.UNKNOWN)) {
            assertThat(purchaseOutcomeOf(OrderStatusResponse("o9", s, false)))
                .isEqualTo(PurchaseOutcome.Confirming("o9"))
        }
    }

    // ── Analytics: fan-out, and a broken tracker never breaks the others ──

    @Test fun `every tracker receives every event even if one throws`() {
        val seen = mutableListOf<String>()
        val broken = object : AnalyticsTracker {
            override fun track(event: AnalyticsEvent) = error("SDK crashed")
            override fun identify(userId: String?) = error("SDK crashed")
        }
        val good = object : AnalyticsTracker {
            override fun track(event: AnalyticsEvent) { seen += event.name }
            override fun identify(userId: String?) { seen += "id:$userId" }
        }
        val analytics = Analytics(linkedSetOf(broken, good))

        analytics.track(AnalyticsEvent("run_started"))
        analytics.identify("u1")

        assertThat(seen).containsExactly("run_started", "id:u1").inOrder()
    }

    @Test fun `no trackers is fine`() {
        Analytics(emptySet()).track(AnalyticsEvent("x"))
    }

    // ── Video: refs resolve through the provider that owns them ──

    @Test fun `url refs play as-is, HLS is labelled`() = runTest {
        val videos = Videos(setOf(DirectUrlResolver()))
        val hls = videos.resolve(VideoRef("url", "https://cdn.example/a/master.m3u8", premiereStartEpochMs = 5L))
        assertThat(hls.url).isEqualTo("https://cdn.example/a/master.m3u8")
        assertThat(hls.mimeType).isEqualTo("application/x-mpegURL")
        assertThat(hls.premiereStartEpochMs).isEqualTo(5L)
        assertThat(videos.resolve(VideoRef("url", "https://cdn.example/a.mp4")).mimeType).isNull()
    }

    @Test fun `a new provider plugs in without touching Videos`() = runTest {
        val slike = object : VideoSourceResolver {
            override val provider = "slike"
            override suspend fun resolve(ref: VideoRef) = PlayableStream("https://slike.example/${ref.id}.m3u8")
        }
        val videos = Videos(setOf(DirectUrlResolver(), slike))
        assertThat(videos.supports("slike")).isTrue()
        assertThat(videos.resolve(VideoRef("slike", "abc")).url).isEqualTo("https://slike.example/abc.m3u8")
    }

    @Test fun `unknown provider is a clear error`() {
        val videos = Videos(setOf(DirectUrlResolver()))
        assertThat(videos.supports("slike")).isFalse()
        assertThrows(UnsupportedVideoProviderException::class.java) {
            kotlinx.coroutines.runBlocking { videos.resolve(VideoRef("slike", "abc")) }
        }
    }

    // ── Push: plugged-in handlers first (by priority), else the app's notification ──

    private fun handler(priority: Int, takes: Boolean, log: MutableList<String>, name: String) =
        object : PushHandler {
            override val priority = priority
            override fun handle(message: PushMessage): Boolean { log += name; return takes }
        }

    private val msg = PushMessage("Class at 7", "Starts in 10 min", mapOf("kind" to "SESSION_REMINDER", "route" to "/session/s1"))

    @Test fun `no handlers - our notification shows`() {
        var shown: PushMessage? = null
        assertThat(PushRouter(emptySet()).route(msg) { shown = it }).isFalse()
        assertThat(shown).isEqualTo(msg)
        assertThat(msg.route).isEqualTo("/session/s1")
        assertThat(msg.kind).isEqualTo("SESSION_REMINDER")
    }

    @Test fun `highest priority handler that takes it wins, fallback skipped`() {
        val log = mutableListOf<String>()
        var fallback = false
        val router = PushRouter(
            setOf(handler(10, true, log, "low"), handler(200, false, log, "high"), handler(100, true, log, "mid")),
        )
        assertThat(router.route(msg) { fallback = true }).isTrue()
        assertThat(log).containsExactly("high", "mid").inOrder()
        assertThat(fallback).isFalse()
    }

    @Test fun `a throwing handler is skipped, never swallows the reminder`() {
        var shown = false
        val broken = object : PushHandler {
            override fun handle(message: PushMessage): Boolean = error("SDK crashed")
        }
        assertThat(PushRouter(setOf(broken)).route(msg) { shown = true }).isFalse()
        assertThat(shown).isTrue()
    }
}
