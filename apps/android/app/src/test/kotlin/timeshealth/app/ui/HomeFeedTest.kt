package timeshealth.app.ui

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test
import timeshealth.app.core.model.AppTab as ModelTab
import timeshealth.app.core.model.FeedAction
import timeshealth.app.core.model.HeroMyRace
import timeshealth.app.core.model.HeroSessionState
import timeshealth.app.core.model.HeroSellYoga
import timeshealth.app.core.model.HeroSlot
import timeshealth.app.core.model.HeroYogaSession
import timeshealth.app.core.model.Instructor
import timeshealth.app.core.model.LiveClassCard
import timeshealth.app.core.model.LiveClassState
import timeshealth.app.core.model.YogaSession
import timeshealth.app.ui.feed.formatReelDuration
import timeshealth.app.ui.feed.liveClassPill
import timeshealth.app.ui.feed.parseHexColor
import timeshealth.app.ui.feed.raceLine
import timeshealth.app.ui.feed.yogaHeroState
import timeshealth.app.ui.home.FeedTarget
import timeshealth.app.ui.home.YOGA_PLAN_ID
import timeshealth.app.ui.home.targetFor
import timeshealth.app.ui.navigation.AppTab
import timeshealth.app.ui.navigation.Route
import timeshealth.app.ui.theme.TagTone

/** Home's tap targets and the time-based states its cards show. */
class HomeFeedTest {

    private val start = Instant.parse("2026-10-08T01:00:00Z")
    private val startMs = start.toEpochMilli()

    private fun yoga(state: HeroSessionState, liveClassId: String? = null, secondsToStart: Int? = null) = HeroYogaSession(
        title = "Morning flow", ctaLabel = "Join Wait Room", sessionId = "b1", batchId = "b1", state = state,
        startsAt = start.toString(), secondsToStart = secondsToStart, instructorName = "Asha", durationMinutes = 60,
        liveClassId = liveClassId,
    )

    private fun session(free: Boolean) = YogaSession(
        id = "s1", categoryId = "c1", title = "Spine care", description = "", imageUrl = "", durationMinutes = 20,
        level = "Beginner", intensity = "Gentle", caloriesBurned = 0, isFree = free, isLive = false, bodyFocusTitle = "",
        lifestyleImpact = "", instructor = Instructor("i1", "Asha", "", "", "", "", "", 0.0, null, 0),
        joinedCountTillDate = 0, todayActiveCount = 0,
    )

    private fun liveCard(state: LiveClassState = LiveClassState.SCHEDULED, canJoin: Boolean = true) = LiveClassCard(
        id = "lc1", title = "Sunrise", startsAt = start.toString(), endsAt = start.plusSeconds(3600).toString(),
        durationMinutes = 60, isFree = false, state = state, canJoin = canJoin,
    )

    // ── Where taps go ────────────────────────────────────────────────────────

    @Test
    fun `every server action has a destination`() {
        assertThat(targetFor(FeedAction.OpenTab(ModelTab.DIET))).isEqualTo(FeedTarget.Tab(AppTab.DIET))
        assertThat(targetFor(FeedAction.OpenTab(ModelTab.UNKNOWN))).isEqualTo(FeedTarget.None)
        assertThat(targetFor(FeedAction.OpenRunTracker)).isEqualTo(FeedTarget.Open(Route.RunTracker))
        assertThat(targetFor(FeedAction.OpenPaywall("yoga_annual"))).isEqualTo(FeedTarget.Open(Route.Paywall("yoga_annual")))
        assertThat(targetFor(FeedAction.OpenYogaExplorer(null))).isEqualTo(FeedTarget.Open(Route.YogaExplorer(null)))
        assertThat(targetFor(FeedAction.OpenDigitalBib("e1"))).isEqualTo(FeedTarget.Open(Route.Bib("e1")))
        assertThat(targetFor(FeedAction.OpenExternal("https://toi.in/x"))).isEqualTo(FeedTarget.External("https://toi.in/x"))
        assertThat(targetFor(FeedAction.Unknown("SOMETHING_NEW"))).isEqualTo(FeedTarget.None)
    }

    @Test
    fun `a member's class opens the in-app live class only when one is scheduled and open`() {
        assertThat(targetFor(yoga(HeroSessionState.LIVE, liveClassId = "lc1"))).isEqualTo(FeedTarget.Open(Route.LiveClass("lc1")))
        assertThat(targetFor(yoga(HeroSessionState.STARTING_SOON, liveClassId = "lc1"))).isEqualTo(FeedTarget.Open(Route.LiveClass("lc1")))
        // Later today: the schedule, even with a class scheduled.
        assertThat(targetFor(yoga(HeroSessionState.SCHEDULED_TODAY, liveClassId = "lc1"))).isEqualTo(FeedTarget.Tab(AppTab.YOGA))
        // No premiere: the Yoga tab's join (class link + attendance).
        assertThat(targetFor(yoga(HeroSessionState.LIVE))).isEqualTo(FeedTarget.Tab(AppTab.YOGA))
    }

    @Test
    fun `race day opens the pass, otherwise the race`() {
        fun race(raceDay: Boolean) = HeroMyRace(
            title = "Delhi Half", ctaLabel = "Open", eventId = "e1", startsAt = start.toString(), daysRemaining = 3,
            category = "21K", flagOffTime = "5:30 AM", isRaceDay = raceDay,
        )
        assertThat(targetFor(race(true))).isEqualTo(FeedTarget.Open(Route.Bib("e1")))
        assertThat(targetFor(race(false))).isEqualTo(FeedTarget.Open(Route.RaceDetail("e1")))
        assertThat(targetFor(HeroSellYoga(title = "x", ctaLabel = "Buy", planId = "yoga_annual", pricePaise = 0)))
            .isEqualTo(FeedTarget.Open(Route.Paywall("yoga_annual")))
        assertThat(targetFor(HeroSlot.Unknown("NEW_KIND") as HeroSlot)).isEqualTo(FeedTarget.None)
    }

    @Test
    fun `a locked session opens the paywall, never a dead tap`() {
        assertThat(targetFor(session(free = false), entitledToYoga = false)).isEqualTo(FeedTarget.Open(Route.Paywall(YOGA_PLAN_ID)))
        assertThat(targetFor(session(free = false), entitledToYoga = true)).isEqualTo(FeedTarget.Open(Route.SessionDetail("s1")))
        assertThat(targetFor(session(free = true), entitledToYoga = false)).isEqualTo(FeedTarget.Open(Route.SessionDetail("s1")))
        assertThat(targetFor(liveCard(canJoin = false))).isEqualTo(FeedTarget.Open(Route.Paywall(YOGA_PLAN_ID)))
        assertThat(targetFor(liveCard(canJoin = true))).isEqualTo(FeedTarget.Open(Route.LiveClass("lc1")))
    }

    // ── What the cards say, from the server clock ────────────────────────────

    @Test
    fun `the wait room counts down and turns live at zero without a refetch`() {
        val slot = yoga(HeroSessionState.STARTING_SOON, secondsToStart = 600)
        assertThat(yogaHeroState(slot, startMs - 9 * 60_000 - 1_000)).isEqualTo(timeshealth.app.ui.feed.YogaHeroState(false, true, 10))
        assertThat(yogaHeroState(slot, startMs - 30_000).minutes).isEqualTo(1)
        assertThat(yogaHeroState(slot, startMs).live).isTrue()
    }

    @Test
    fun `live class pills follow the clock`() {
        assertThat(liveClassPill(liveCard(), startMs - 30 * 60_000)).isEqualTo("Starts in 30 min" to TagTone.CORAL)
        assertThat(liveClassPill(liveCard(), startMs + 1)).isEqualTo("Live now" to TagTone.LIVE)
        assertThat(liveClassPill(liveCard(), startMs + 3_600_000)).isEqualTo("Ended" to TagTone.NEUTRAL)
        assertThat(liveClassPill(liveCard(LiveClassState.CANCELLED), startMs - 1)).isEqualTo("Cancelled" to TagTone.NEUTRAL)
        // Further out: the day and time, not a countdown.
        assertThat(liveClassPill(liveCard(), startMs - 26 * 3_600_000).second).isEqualTo(TagTone.CORAL)
        assertThat(liveClassPill(liveCard(), startMs - 26 * 3_600_000).first).doesNotContain("Starts in")
    }

    @Test
    fun `small formatting rules`() {
        assertThat(formatReelDuration(45)).isEqualTo("0:45")
        assertThat(formatReelDuration(125)).isEqualTo("2:05")
        assertThat(formatReelDuration(0)).isEmpty()
        assertThat(parseHexColor("#1B4931")).isNotNull()
        assertThat(parseHexColor("1b4931ff")).isNotNull()
        assertThat(parseHexColor("red")).isNull()
        assertThat(parseHexColor(null)).isNull()
        val race = HeroMyRace(
            title = "x", ctaLabel = "x", eventId = "e", startsAt = start.toString(), daysRemaining = 1, category = "21K",
            flagOffTime = "", isRaceDay = false, bibNumber = "DEL-1",
        )
        assertThat(raceLine(race)).isEqualTo("1 day to go · Bib #DEL-1")
    }
}
