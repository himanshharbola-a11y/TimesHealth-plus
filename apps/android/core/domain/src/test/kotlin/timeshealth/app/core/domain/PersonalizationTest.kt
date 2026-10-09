package timeshealth.app.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PersonalizationTest {

    private val adminOrder = listOf("HERO", "FREE_SESSIONS", "PERSONALISED_RAIL", "PROMO", "LIVE_CLASSES", "RUN_TRACKER_TILE", "WORKSHOPS", "INSTRUCTORS", "ARTICLES", "TESTIMONIALS")

    private fun order(s: UserSignals) = personaliseOrder(adminOrder, { it }, s)

    @Test
    fun `the admin's first section stays first and nothing moves more than three places`() {
        val member = UserSignals(isYogaMember = true, hasUpcomingRace = true, currentStreak = 5, hourOfDay = 7)
        val out = order(member)
        assertThat(out.first()).isEqualTo("HERO")
        assertThat(out).containsExactlyElementsIn(adminOrder)
        adminOrder.forEachIndexed { i, kind -> assertThat(kotlin.math.abs(out.indexOf(kind) - i)).isAtMost(MAX_SHIFT + 1) }
    }

    @Test
    fun `members see their classes before sales, newcomers see free samples early`() {
        val member = order(UserSignals(isYogaMember = true, hourOfDay = 7))
        assertThat(member.indexOf("LIVE_CLASSES")).isLessThan(member.indexOf("FREE_SESSIONS"))
        assertThat(member.indexOf("TESTIMONIALS")).isEqualTo(member.lastIndex)

        val newcomer = order(UserSignals(isYogaMember = false))
        assertThat(newcomer.indexOf("FREE_SESSIONS")).isLessThan(newcomer.indexOf("LIVE_CLASSES"))
    }

    @Test
    fun `runners get the run tracker forward, non-runners further down`() {
        val runner = order(UserSignals(runsLast30Days = 4))
        val other = order(UserSignals())
        assertThat(runner.indexOf("RUN_TRACKER_TILE")).isLessThan(other.indexOf("RUN_TRACKER_TILE"))
    }

    @Test
    fun `the personal rail follows focus area, then practice, then goal`() {
        assertThat(personalRail(UserSignals(concern = "LOWER_BACK", goal = "WEIGHT_LOSS")).categoryId).isEqualTo("cat_spine")
        assertThat(personalRail(UserSignals(concern = "LOWER_BACK")).reason).isEqualTo("For your lower back")

        val practised = personalRail(UserSignals(goal = "WEIGHT_LOSS", completedByCategory = mapOf("cat_sleep" to 3)), mapOf("cat_sleep" to "Sleep & Calm"))
        assertThat(practised.categoryId).isEqualTo("cat_sleep")
        assertThat(practised.heading).isEqualTo("More from Sleep & Calm")

        assertThat(personalRail(UserSignals(goal = "WEIGHT_LOSS")).categoryId).isEqualTo("cat_core")
        assertThat(personalRail(UserSignals()).categoryId).isEqualTo("cat_morning")
    }

    @Test
    fun `tracks rank by focus area, goal and practice`() {
        val s = UserSignals(concern = "SLEEP_ENERGY", goal = "WEIGHT_LOSS", completedByCategory = mapOf("cat_flex" to 2))
        assertThat(rankCategories(listOf("cat_morning", "cat_flex", "cat_core", "cat_sleep"), s))
            .containsExactly("cat_sleep", "cat_core", "cat_flex", "cat_morning").inOrder()
    }

    @Test
    fun `recommendations skip what is done, favour saved and fitting sessions, and say why`() {
        val s = UserSignals(concern = "LOWER_BACK", hourOfDay = 13)
        val sessions = listOf(
            SessionFacts("done", "cat_spine", "Beginner", 30, completed = true, saved = false, playable = true),
            SessionFacts("spine", "cat_spine", "All Levels", 15, completed = false, saved = false, playable = true),
            SessionFacts("saved", "cat_core", "Intermediate", 40, completed = false, saved = true, playable = true),
            SessionFacts("other", "cat_core", "Intermediate", 45, completed = false, saved = false, playable = false),
        )
        val recs = recommendSessions(sessions, s)
        assertThat(recs.first().sessionId).isEqualTo("spine")
        assertThat(recs.first().reason).isEqualTo("For your lower back relief")
        assertThat(recs.map { it.sessionId }.indexOf("done")).isEqualTo(recs.lastIndex)
        assertThat(recs.first { it.sessionId == "saved" }.reason).isEqualTo("You saved this")
    }

    @Test
    fun `the suggested distance follows the running, within what is offered`() {
        val offered = listOf("5K", "10K", "21K")
        assertThat(suggestDistance(offered, UserSignals())!!.code).isEqualTo("5K")
        assertThat(suggestDistance(offered, UserSignals())!!.reason).isEqualTo("A great first race")
        assertThat(suggestDistance(offered, UserSignals(longestRunKm = 8.0, runsLast30Days = 3))!!.code).isEqualTo("10K")
        assertThat(suggestDistance(offered, UserSignals(longestRunKm = 16.0, runsLast30Days = 6))!!.code).isEqualTo("21K")
        // Marathon trainees with regular runs step up one.
        val trainee = suggestDistance(offered, UserSignals(goal = "MARATHON_TRAINING", longestRunKm = 8.0, runsLast30Days = 5))!!
        assertThat(trainee.code).isEqualTo("21K")
        // 42K-ready but no 42K on offer: the longest offered.
        assertThat(suggestDistance(offered, UserSignals(longestRunKm = 30.0, runsLast30Days = 8))!!.code).isEqualTo("21K")
        assertThat(suggestDistance(offered, UserSignals(runsLast30Days = 3, longestRunKm = 0.2))!!.reason).isEqualTo("A great first race")
        assertThat(suggestDistance(emptyList(), UserSignals())).isNull()
    }
}
