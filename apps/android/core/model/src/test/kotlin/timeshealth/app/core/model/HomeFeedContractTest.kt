package timeshealth.app.core.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import timeshealth.app.core.model.FeedComponentType.ARTICLE_RAIL
import timeshealth.app.core.model.FeedComponentType.ENTRY_TILE
import timeshealth.app.core.model.FeedComponentType.HERO_STACK
import timeshealth.app.core.model.FeedComponentType.PROMO_STRIP
import timeshealth.app.core.model.FeedComponentType.QUOTE_RAIL
import timeshealth.app.core.model.FeedComponentType.REEL_RAIL
import timeshealth.app.core.model.FeedComponentType.VIDEO_RAIL
import timeshealth.app.core.model.FeedComponentType.WORKSHOP_RAIL
import timeshealth.app.core.model.HeroSlotKind.MY_RACE
import timeshealth.app.core.model.HeroSlotKind.RACE_RESULT
import timeshealth.app.core.model.HeroSlotKind.SELL_MARATHON
import timeshealth.app.core.model.HeroSlotKind.SELL_YOGA
import timeshealth.app.core.model.HeroSlotKind.YOGA_RENEW
import timeshealth.app.core.model.HeroSlotKind.YOGA_SESSION

/** GET /home for the six QA personas. */
class HomeFeedContractTest {

    private fun home(persona: String) = Fixtures.decode<HomeFeedResponse>("home.$persona.json")

    private fun HomeFeedResponse.heroSlots() =
        knownComponents.filterIsInstance<HeroStackComponent>().single().knownSlots

    private inline fun <reified T : KnownFeedComponent> HomeFeedResponse.only(): T =
        knownComponents.filterIsInstance<T>().single()

    @Test
    fun `hero slots follow the PRD 6_1 priority for each persona`() {
        val expected = mapOf(
            "free" to listOf(SELL_YOGA, SELL_MARATHON),
            "yoga" to listOf(YOGA_SESSION, SELL_MARATHON),
            "marathon" to listOf(MY_RACE, SELL_YOGA),
            // Hard rule: a yoga subscriber's session always wins slot 1; the race drops to slot 2.
            "both" to listOf(YOGA_SESSION, MY_RACE),
            "expired" to listOf(YOGA_RENEW, SELL_MARATHON),
            "finisher" to listOf(RACE_RESULT, SELL_YOGA),
        )
        for ((persona, kinds) in expected) {
            assertWithMessage(persona).that(home(persona).heroSlots().map { it.kind }).containsExactlyElementsIn(kinds).inOrder()
        }
    }

    @Test
    fun `every persona gets the same component sequence, hero first and promo after the first rail`() {
        val sequence = listOf(
            HERO_STACK, VIDEO_RAIL, PROMO_STRIP, VIDEO_RAIL, ENTRY_TILE,
            WORKSHOP_RAIL, REEL_RAIL, ARTICLE_RAIL, QUOTE_RAIL,
        )
        for (persona in listOf("free", "yoga", "marathon", "both", "expired", "finisher")) {
            val feed = home(persona)
            assertWithMessage(persona).that(feed.components.map { it.type }).containsExactlyElementsIn(sequence).inOrder()
            assertWithMessage(persona).that(feed.knownComponents).hasSize(feed.components.size)
            assertWithMessage(persona).that(feed.ttlSeconds).isEqualTo(60)
            assertWithMessage(persona).that(feed.userName).isNotNull()
        }
    }

    @Test
    fun `both persona - todays yoga session leads, the registered race follows`() {
        val (session, race) = home("both").heroSlots()

        session as HeroYogaSession
        assertThat(session.state).isEqualTo(HeroSessionState.SCHEDULED_TODAY)
        assertThat(session.batchId).isEqualTo("b5")
        assertThat(session.secondsToStart).isEqualTo(10064)
        assertThat(session.joinUrl).isNull()
        assertThat(session.instructorName).isEqualTo("Sneha Rathi")
        assertThat(session.durationMinutes).isEqualTo(60)

        race as HeroMyRace
        assertThat(race.eventId).isEqualTo("delhi_half")
        assertThat(race.daysRemaining).isEqualTo(24)
        assertThat(race.bibNumber).isEqualTo("DEL-4410B")
        assertThat(race.category).isEqualTo("10K")
        assertThat(race.isRaceDay).isFalse()
        assertThat(race.flagOffTime).isEqualTo("5:30 AM · Wave 2")
    }

    @Test
    fun `marathon persona - race countdown leads, yoga is sold`() {
        val (race, sell) = home("marathon").heroSlots()

        assertThat((race as HeroMyRace).bibNumber).isEqualTo("DEL-8892A")
        assertThat(race.category).isEqualTo("21K")
        assertThat((sell as HeroSellYoga).planId).isEqualTo("yoga_annual")
    }

    @Test
    fun `expired persona - warm renew prompt keeps the streak (PRD 5)`() {
        val (renew, sell) = home("expired").heroSlots()

        renew as HeroYogaRenew
        assertThat(renew.preservedStreak).isEqualTo(7)
        assertThat(renew.expiredAt).isEqualTo("2026-08-05T06:26:54.575Z")
        assertThat(renew.imageUrl).isNull()
        assertThat(renew.ctaLabel).isEqualTo("Renew Membership")

        sell as HeroSellMarathon
        assertThat(sell.fromPricePaise).isEqualTo(197_700L)
        assertThat(sell.city).isEqualTo("Delhi NCR")
    }

    @Test
    fun `finisher persona - pending result, never an empty results screen (PRD 8_4)`() {
        val feed = home("finisher")
        val result = feed.heroSlots().first() as HeroRaceResult

        assertThat(result.eventId).isEqualTo("pune_run")
        assertThat(result.resultPublished).isFalse()
        // The promo slot is server-controlled: the finisher is pointed at the next race.
        assertThat(feed.only<PromoStripComponent>().action).isEqualTo(FeedAction.OpenRaceDetail("delhi_half"))
    }

    @Test
    fun `free persona - both products are sold`() {
        val (yoga, marathon) = home("free").heroSlots()

        assertThat((yoga as HeroSellYoga).pricePaise).isEqualTo(0L)
        assertThat((marathon as HeroSellMarathon).eventId).isEqualTo("delhi_half")
    }

    @Test
    fun `entry tile and promo strip carry typed actions`() {
        val feed = home("both")

        assertThat(feed.only<EntryTileComponent>().action).isEqualTo(FeedAction.OpenRunTracker)
        val promo = feed.only<PromoStripComponent>()
        assertThat(promo.action).isEqualTo(FeedAction.OpenDietLeadForm)
        assertThat(promo.campaignId).isEqualTo("diet-consult")
        assertThat(promo.backgroundColor).isEqualTo("#1B4931")
    }

    @Test
    fun `video rails - concern rail says See all, free rail says Explore`() {
        val (concern, free) = home("both").knownComponents.filterIsInstance<VideoRailComponent>()

        assertThat(concern.seeAllCategoryId).isEqualTo("cat_sleep")
        assertThat(concern.actionLabel).isEqualTo("See all")
        assertThat(concern.cardFormat).isEqualTo(CardFormat.VIDEO_LANDSCAPE)
        assertThat(free.id).isEqualTo("rail-free")
        assertThat(free.actionLabel).isEqualTo("Explore")
        assertThat(free.items.all { it.isFree }).isTrue()
        assertThat(concern.items.first().instructor.rating).isEqualTo(4.92)
    }

    @Test
    fun `playback urls are present only for sessions the caller may play`() {
        // Marathon-only persona: free sessions play, member sessions are locked (url null).
        val concernRail = home("marathon").knownComponents.filterIsInstance<VideoRailComponent>().first()
        for (session in concernRail.items) {
            assertWithMessage(session.id).that(session.playbackUrl != null).isEqualTo(session.isFree)
        }
        // A yoga subscriber may play everything.
        val yogaRail = home("yoga").knownComponents.filterIsInstance<VideoRailComponent>().first()
        assertThat(yogaRail.items.map { it.playbackUrl }).doesNotContain(null)
    }

    @Test
    fun `rails decode their typed items`() {
        val feed = home("yoga")

        assertThat(feed.only<WorkshopRailComponent>().items.map { it.category }).doesNotContain(WorkshopCategory.UNKNOWN)
        assertThat(feed.only<ReelRailComponent>().items).hasSize(4)
        assertThat(feed.only<ArticleRailComponent>().items.first().readTimeMinutes).isGreaterThan(0)
        assertThat(feed.only<QuoteRailComponent>().items).hasSize(3)
    }
}
