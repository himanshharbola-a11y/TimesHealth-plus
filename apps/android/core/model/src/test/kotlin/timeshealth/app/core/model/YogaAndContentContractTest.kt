package timeshealth.app.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Yoga (PRD §7), content (§6.3), workshops, runs (§8.6), notifications and error bodies. */
class YogaAndContentContractTest {

    @Test
    fun `yoga today - eight batches, reminder slot flagged, next batch resolved (PRD 7_1)`() {
        val today = Fixtures.decode<YogaTodayResponse>("yoga-today.json")

        assertThat(today.batches).hasSize(8)
        assertThat(today.batches.single { it.isUserReminderSlot }.id).isEqualTo("b2")
        assertThat(today.batches.count { it.period == BatchPeriod.MORNING }).isEqualTo(4)
        assertThat(today.batches.count { it.period == BatchPeriod.EVENING }).isEqualTo(4)
        assertThat(today.liveBatchId).isNull()
        assertThat(today.nextBatchId).isEqualTo("b5")
        assertThat(today.nextSessionStartsAt).isEqualTo(today.batches.single { it.id == "b5" }.startsAt)
    }

    @Test
    fun `yoga attendance - server-computed tracker (PRD 7_1)`() {
        val attendance = Fixtures.decode<YogaAttendance>("yoga-attendance.json")

        assertThat(attendance.attendedDates).hasSize(7)
        assertThat(attendance.classesAttended).isEqualTo(7)
        assertThat(attendance.currentStreak).isEqualTo(7)
        assertThat(attendance.bestStreak).isEqualTo(7)
        assertThat(attendance.attendanceRate).isEqualTo(3)
        assertThat(attendance.trackingSince).isEqualTo("2026-03-20")
        assertThat(attendance.upcomingSessions.first())
            .isEqualTo(UpcomingYogaSession("2026-10-06", "b5", "Joint Health Bootcamp", "2026-10-06T11:15:00.000Z"))
    }

    @Test
    fun `yoga catalog - playback urls only for entitled sessions`() {
        val free = Fixtures.decode<YogaCatalogResponse>("yoga-catalog.free.json")
        val member = Fixtures.decode<YogaCatalogResponse>("yoga-catalog.yoga.json")

        assertThat(free.categories.map { it.bannerTheme }).containsExactlyElementsIn(
            BannerTheme.entries - BannerTheme.UNKNOWN,
        )
        // Free sessions play for anyone (§6.3 rail 2); the rest need a membership.
        for (session in free.sessions) {
            assertThat(session.playbackUrl != null).isEqualTo(session.isFree)
        }
        assertThat(member.sessions.map { it.playbackUrl }).doesNotContain(null)
        assertThat(member.sessions.map { it.id }).containsExactlyElementsIn(free.sessions.map { it.id })
    }

    @Test
    fun `entitlement-gated endpoints answer 403 with an ApiError`() {
        for (name in listOf("playback.locked.error403.json", "yoga-attendance.free.error403.json")) {
            val error = Fixtures.decode<ApiError>(name)
            assertThat(error.code).isEqualTo("NOT_ENTITLED")
            assertThat(error.message).isEqualTo("Yoga subscription required")
            assertThat(error.fields).isNull()
        }
    }

    @Test
    fun `playback and my sessions`() {
        assertThat(Fixtures.decode<PlaybackResponse>("playback.json").playbackUrl).contains("/sessions/ys_spine_1/")

        val mine = Fixtures.decode<MySessionsResponse>("yoga-mine.json")
        assertThat(mine.savedSessionIds).isEmpty()
        assertThat(mine.completedSessionIds).isEmpty()
    }

    @Test
    fun `workshops - paid seats priced in paise`() {
        val workshops = Fixtures.decode<WorkshopListResponse>("workshops.json").workshops

        assertThat(workshops.map { it.category }).containsExactly(
            WorkshopCategory.YOGA, WorkshopCategory.MARATHON, WorkshopCategory.DIET, WorkshopCategory.MARATHON,
        ).inOrder()
        assertThat(workshops.map { it.pricePaise }).containsExactly(29_900L, 49_900L, 24_900L, 39_900L).inOrder()
        assertThat(workshops.none { it.isRegistered || it.paidSeat }).isTrue()
        assertThat(workshops.map { it.joinUrl }.distinct()).containsExactly(null)
    }

    @Test
    fun `content - articles, quotes, instructors, faqs, mentor and reels`() {
        val content = Fixtures.decode<ContentResponse>("content.json")

        assertThat(content.articles).hasSize(4)
        assertThat(content.quotes).hasSize(3)
        assertThat(content.instructors).hasSize(4)
        assertThat(content.yogaFaqs).hasSize(3)
        assertThat(content.reels).hasSize(4)
        assertThat(content.mentor.name).isEqualTo("Surakshit Goswami")
    }

    @Test
    fun `runs - history and totals over every run`() {
        val history = Fixtures.decode<RunHistoryResponse>("runs.json")

        assertThat(history.runs).hasSize(4)
        assertThat(history.runs.count { it.hasAccuracyWarning }).isEqualTo(1)
        assertThat(history.runs.all { it.synced && it.routePolyline == null }).isTrue()
        assertThat(history.totals).isEqualTo(
            RunTotals(runs = 4, distanceKm = 16.69, durationSeconds = 6255, longestKm = 8.1, monthDistanceKm = 16.69),
        )
        assertThat(history.runs.sumOf { it.durationSeconds }.toLong()).isEqualTo(history.totals.durationSeconds)
    }

    @Test
    fun `notifications - empty inbox`() {
        assertThat(Fixtures.decode<NotificationListResponse>("notifications.json").items).isEmpty()
    }
}
