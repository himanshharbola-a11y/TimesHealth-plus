package timeshealth.app.ui

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import timeshealth.app.FakeAccountGateway
import timeshealth.app.MainDispatcherRule
import timeshealth.app.core.integrations.video.PlayableStream
import timeshealth.app.core.integrations.video.VideoRef
import timeshealth.app.core.model.ApiError
import timeshealth.app.core.model.Instructor
import timeshealth.app.core.model.MySessionsResponse
import timeshealth.app.core.model.PersonaInfo
import timeshealth.app.core.model.PlaybackResponse
import timeshealth.app.core.model.UserPersona
import timeshealth.app.core.model.VideoSource
import timeshealth.app.core.model.YogaCatalogResponse
import timeshealth.app.core.model.YogaSession
import timeshealth.app.core.model.YogaTodayResponse
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.networkError
import timeshealth.app.sessionResponse
import timeshealth.app.ui.paywall.YogaPlans
import timeshealth.app.ui.paywall.expiryAfter
import timeshealth.app.ui.paywall.rupees
import timeshealth.app.ui.state.UiState
import timeshealth.app.ui.yoga.RepositoryYogaSessionsGateway
import timeshealth.app.ui.yoga.SessionDetailViewModel
import timeshealth.app.ui.yoga.SessionStream
import timeshealth.app.ui.yoga.StreamState
import timeshealth.app.ui.yoga.VideoPlayerViewModel
import timeshealth.app.ui.yoga.YogaExplorerViewModel
import timeshealth.app.ui.yoga.YogaSessionsGateway
import timeshealth.app.ui.yoga.clock
import timeshealth.app.ui.yoga.speedLabel

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryTest {

    @get:Rule val main = MainDispatcherRule()

    private fun session(id: String, free: Boolean, category: String = "cat_spine") = YogaSession(
        id = id, categoryId = category, title = "Session $id", description = "d", imageUrl = "https://img.test/$id.jpg",
        durationMinutes = 30, level = "Beginner", intensity = "Gentle", caloriesBurned = 120, isFree = free, isLive = false,
        bodyFocusTitle = "Spine", lifestyleImpact = "Better posture",
        instructor = Instructor(id = "i1", name = "Asha", title = "Guru", specialty = "Hatha", bio = "", experience = "10 years", avatarUrl = "https://img.test/i.jpg", rating = 4.9, reelCount = 0),
        joinedCountTillDate = 1200, todayActiveCount = 12,
    )

    private class FakeSessions(val catalog: YogaCatalogResponse) : YogaSessionsGateway {
        var streamAnswer: Any? = SessionStream(PlayableStream("https://cdn.test/a.m3u8"))
        val streamCalls = mutableListOf<String>()
        val saved = mutableListOf<Pair<String, Boolean>>()
        val completed = mutableListOf<Pair<String, Boolean>>()
        var entitlementsChanged = 0
        override val catalogChanges = MutableSharedFlow<Unit>()
        override val mySessionsData = MutableStateFlow<MySessionsResponse?>(null)

        override suspend fun catalog(refresh: Boolean) = catalog
        override suspend fun today(refresh: Boolean) = YogaTodayResponse()
        override suspend fun mySessions(refresh: Boolean) = MySessionsResponse(completedSessionIds = listOf("s_done")).also { mySessionsData.value = it }
        override suspend fun setSaved(sessionId: String, saved: Boolean) {
            this.saved += sessionId to saved
        }
        override suspend fun setCompleted(sessionId: String, completed: Boolean) {
            this.completed += sessionId to completed
        }
        override suspend fun stream(sessionId: String): SessionStream? {
            streamCalls += sessionId
            return when (val a = streamAnswer) {
                is Throwable -> throw a
                else -> a as SessionStream?
            }
        }
        override fun entitlementsChanged() {
            entitlementsChanged++
        }
    }

    private val catalog = YogaCatalogResponse(
        sessions = listOf(session("s_free", free = true), session("s_paid", free = false), session("s_done", free = true, category = "cat_core")),
    )

    private fun account(member: Boolean) = FakeAccountGateway().apply {
        val persona = if (member) UserPersona.YOGA_SUBSCRIBER else UserPersona.FREE
        sessionAnswers += sessionResponse().copy(persona = PersonaInfo(persona, hasYoga = member, hasMarathon = false, label = persona.name))
    }

    private fun handle(vararg pairs: Pair<String, Any?>) = SavedStateHandle(mapOf(*pairs))

    // ── Which stream plays ────────────────────────────────────────────────────

    @Test
    fun `the dashboard video plays through its plug-in when one is installed, else the signed URL`() = runTest {
        val resolved = mutableListOf<VideoRef>()
        val resolve: suspend (VideoRef) -> PlayableStream = { ref -> resolved += ref; PlayableStream("https://slike.test/${ref.id}.m3u8") }

        val slike = PlaybackResponse(playbackUrl = "https://media.test/s.m3u8?s=1", video = VideoSource("slike", "sl_1"))
        assertThat(RepositoryYogaSessionsGateway.streamFor(slike, { it == "slike" }, resolve, debug = false)?.stream?.url)
            .isEqualTo("https://slike.test/sl_1.m3u8")
        assertThat(resolved.single()).isEqualTo(VideoRef("slike", "sl_1"))

        // Slike not plugged in yet: the signed URL still plays.
        assertThat(RepositoryYogaSessionsGateway.streamFor(slike, { false }, resolve, debug = false)?.stream?.url)
            .isEqualTo("https://media.test/s.m3u8?s=1")
        // Only Slike has it and Slike isn't plugged in: nothing to play.
        assertThat(RepositoryYogaSessionsGateway.streamFor(slike.copy(playbackUrl = ""), { false }, resolve, debug = false)).isNull()
    }

    @Test
    fun `placeholder media hosts play a labelled sample in debug builds only`() = runTest {
        val placeholder = PlaybackResponse("https://media.timeshealthplus.invalid/sessions/a/master.m3u8?s=1")
        val debug = RepositoryYogaSessionsGateway.streamFor(placeholder, { false }, { error("unused") }, debug = true)!!
        assertThat(debug.isSample).isTrue()
        assertThat(debug.stream.url).isEqualTo(RepositoryYogaSessionsGateway.DEV_SAMPLE_STREAM)

        val release = RepositoryYogaSessionsGateway.streamFor(placeholder, { false }, { error("unused") }, debug = false)!!
        assertThat(release.isSample).isFalse()
        assertThat(release.stream.url).isEqualTo(placeholder.playbackUrl)
        assertThat(release.stream.mimeType).isEqualTo("application/x-mpegURL")
    }

    // ── Session detail ────────────────────────────────────────────────────────

    @Test
    fun `a paid session is locked for a free user, open for a member, and a bad link says so`() = runTest {
        val gateway = FakeSessions(catalog)
        val free = SessionDetailViewModel(handle("id" to "s_paid"), gateway, account(member = false))
        val member = SessionDetailViewModel(handle("id" to "s_paid"), gateway, account(member = true))
        val gone = SessionDetailViewModel(handle("id" to "nope"), gateway, account(member = true))
        runCurrent()

        assertThat((free.state.value as UiState.Ready).data.locked).isTrue()
        assertThat((member.state.value as UiState.Ready).data.locked).isFalse()
        assertThat((gone.state.value as UiState.Failed).error.message).isEqualTo("This session isn’t available any more.")
    }

    @Test
    fun `save and complete send the wanted state`() = runTest {
        val gateway = FakeSessions(catalog)
        val vm = SessionDetailViewModel(handle("id" to "s_done"), gateway, account(member = false))
        runCurrent()
        assertThat((vm.state.value as UiState.Ready).data.completed).isTrue()

        vm.setSaved(true)
        vm.setCompleted(false)
        runCurrent()
        assertThat(gateway.saved).containsExactly("s_done" to true)
        assertThat(gateway.completed).containsExactly("s_done" to false)
    }

    // ── Library ───────────────────────────────────────────────────────────────

    @Test
    fun `the library opens on the asked-for track, else the first`() = runTest {
        val cats = YogaCatalogResponse(
            categories = listOf(category("cat_spine"), category("cat_core")),
            sessions = catalog.sessions,
        )
        val gateway = FakeSessions(cats)
        val asked = YogaExplorerViewModel(handle("categoryId" to "cat_core"), gateway, account(member = true))
        val bad = YogaExplorerViewModel(handle("categoryId" to "cat_gone"), gateway, account(member = true))
        runCurrent()
        assertThat(asked.activeCategory.value).isEqualTo("cat_core")
        assertThat(bad.activeCategory.value).isEqualTo("cat_spine")

        asked.select("cat_spine")
        runCurrent()
        assertThat(asked.activeCategory.value).isEqualTo("cat_spine")
    }

    private fun category(id: String) = timeshealth.app.core.model.YogaCategory(
        id = id, name = id, tagline = "t", bodyTargetSummary = "Spine, Hips", imageUrl = "https://img.test/c.jpg",
        bannerTheme = timeshealth.app.core.model.BannerTheme.PLUM, sessionCount = 1, totalYogisJoined = 2400,
    )

    // ── Player ────────────────────────────────────────────────────────────────

    @Test
    fun `a known non-member goes to the paywall without asking for the video`() = runTest {
        val gateway = FakeSessions(catalog)
        val vm = VideoPlayerViewModel(handle("id" to "s_paid"), gateway, account(member = false))
        runCurrent()
        assertThat(vm.toPaywall.value).isTrue()
        assertThat(gateway.streamCalls).isEmpty()
    }

    @Test
    fun `a member plays, and a 403 refreshes the membership and goes to the paywall`() = runTest {
        val gateway = FakeSessions(catalog)
        val vm = VideoPlayerViewModel(handle("id" to "s_paid"), gateway, account(member = true))
        runCurrent()
        assertThat(vm.stream.value).isInstanceOf(StreamState.Ready::class.java)
        assertThat(gateway.streamCalls).containsExactly("s_paid")

        val lapsed = FakeSessions(catalog).apply { streamAnswer = ApiRequestException(403, ApiError("NOT_ENTITLED", "x")) }
        val vm2 = VideoPlayerViewModel(handle("id" to "s_paid"), lapsed, account(member = true))
        runCurrent()
        assertThat(vm2.toPaywall.value).isTrue()
        assertThat(lapsed.entitlementsChanged).isEqualTo(1)
    }

    @Test
    fun `no video yet and a failed load are told apart, and a retry asks again`() = runTest {
        val gateway = FakeSessions(catalog).apply { streamAnswer = null }
        val vm = VideoPlayerViewModel(handle("id" to "s_free"), gateway, account(member = false))
        runCurrent()
        assertThat(vm.stream.value).isEqualTo(StreamState.Unavailable)

        gateway.streamAnswer = networkError()
        vm.retryStream()
        runCurrent()
        assertThat(vm.stream.value).isEqualTo(StreamState.Failed)

        gateway.streamAnswer = SessionStream(PlayableStream("https://cdn.test/b.m3u8"))
        vm.retryStream()
        runCurrent()
        assertThat((vm.stream.value as StreamState.Ready).stream.stream.url).isEqualTo("https://cdn.test/b.m3u8")
    }

    @Test
    fun `leaving marks the session complete once, then leaves`() = runTest {
        val gateway = FakeSessions(catalog)
        val vm = VideoPlayerViewModel(handle("id" to "s_free"), gateway, account(member = false))
        runCurrent()
        var left = 0
        vm.leave { left++ }
        advanceUntilIdle()
        assertThat(gateway.completed).containsExactly("s_free" to true)
        assertThat(left).isEqualTo(1)

        // Already completed: just leaves.
        val done = VideoPlayerViewModel(handle("id" to "s_done"), gateway, account(member = false))
        runCurrent()
        done.leave { left++ }
        assertThat(left).isEqualTo(2)
        assertThat(gateway.completed).hasSize(1)
    }

    @Test
    fun `player labels`() {
        assertThat(clock(0)).isEqualTo("00:00")
        assertThat(clock(3_725_000)).isEqualTo("62:05")
        assertThat(speedLabel(1f)).isEqualTo("1.0×")
        assertThat(speedLabel(1.25f)).isEqualTo("1.25×")
        assertThat(speedLabel(1.5f)).isEqualTo("1.5×")
        assertThat(speedLabel(0.75f)).isEqualTo("0.75×")
    }

    // ── Paywall ───────────────────────────────────────────────────────────────

    @Test
    fun `an active membership extends from its expiry, a lapsed one from today`() {
        val now = Instant.parse("2026-10-07T06:00:00Z").toEpochMilli()
        assertThat(expiryAfter("2027-01-15T18:29:59Z", 12, now)).isEqualTo(LocalDate.of(2028, 1, 15))
        assertThat(expiryAfter("2026-09-01T00:00:00Z", 1, now)).isEqualTo(LocalDate.of(2026, 11, 7))
        assertThat(expiryAfter(null, 12, now)).isEqualTo(LocalDate.of(2027, 10, 7))
    }

    @Test
    fun `plans and prices`() {
        assertThat(YogaPlans.byId("yoga_monthly").months).isEqualTo(1)
        assertThat(YogaPlans.byId("nope").id).isEqualTo("yoga_annual")
        assertThat(YogaPlans.annualSavingPct).isEqualTo(58)
        assertThat(rupees(499_900)).isEqualTo("₹4,999")
    }
}
