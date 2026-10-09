package timeshealth.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import timeshealth.app.core.domain.UserSignals
import timeshealth.app.core.model.BannerTheme
import timeshealth.app.core.model.Concern
import timeshealth.app.core.model.Instructor
import timeshealth.app.core.model.MySessionsResponse
import timeshealth.app.core.model.YogaCatalogResponse
import timeshealth.app.core.model.YogaCategory
import timeshealth.app.core.model.YogaSession
import timeshealth.app.core.model.YogaTodayResponse
import timeshealth.app.sessionResponse
import timeshealth.app.ui.marathon.RacesUi
import timeshealth.app.ui.marathon.suggestionFor
import timeshealth.app.ui.yoga.YogaUi
import timeshealth.app.ui.yoga.personaliseYoga

class PersonalisationUiTest {

    private fun cat(id: String) = YogaCategory(id, id, "t", "b", "https://img.test/c.jpg", BannerTheme.PLUM, 2, 1000)
    private fun ses(id: String, cat: String, free: Boolean = true) = YogaSession(
        id = id, categoryId = cat, title = id, description = "d", imageUrl = "https://img.test/s.jpg", durationMinutes = 20,
        level = "Beginner", intensity = "Gentle", caloriesBurned = 80, isFree = free, isLive = false, bodyFocusTitle = "f", lifestyleImpact = "i",
        instructor = Instructor("i1", "Asha", "Guru", "Hatha", "", "10y", "https://img.test/i.jpg", 4.8, reelCount = 0),
        joinedCountTillDate = 10, todayActiveCount = 1,
    )

    private val ui = YogaUi(
        member = true, expired = false, today = YogaTodayResponse(),
        catalog = YogaCatalogResponse(
            categories = listOf(cat("cat_morning"), cat("cat_core"), cat("cat_spine")),
            sessions = listOf(ses("m1", "cat_morning"), ses("c1", "cat_core"), ses("s1", "cat_spine"), ses("s2", "cat_spine")),
        ),
        liveClasses = null,
    )

    @Test
    fun `yoga leads with the focus-area track and recommends from it, skipping what is done`() {
        val profile = sessionResponse().profile.copy(concern = Concern.LOWER_BACK)
        val out = personaliseYoga(ui, profile, MySessionsResponse(completedSessionIds = listOf("s1")), hourOfDay = 8)
        assertThat(out.tracks.first().id).isEqualTo("cat_spine")
        assertThat(out.forYou.first().session.id).isEqualTo("s2")
        assertThat(out.forYou.first().reason).isEqualTo("For your lower back relief")
        assertThat(out.forYou.last().session.id).isEqualTo("s1")
        assertThat(out.focusLine).isEqualTo("For your lower back")
    }

    @Test
    fun `with no answers yet the catalogue order stands and nothing claims to be personal`() {
        val out = personaliseYoga(ui, sessionResponse().profile, null, hourOfDay = 8)
        assertThat(out.tracks.map { it.id }).containsExactly("cat_morning", "cat_core", "cat_spine").inOrder()
        assertThat(out.focusLine).isNull()
    }

    @Test
    fun `marathon suggestion only counts races still open, and needs signals`() {
        val races = RacesUi(myNext = null, myLater = emptyList(), past = emptyList(), hero = null, rest = emptyList(), hasEntries = false)
        assertThat(suggestionFor(races, UserSignals())).isNull()
        assertThat(suggestionFor(races, null)).isNull()
    }
}
