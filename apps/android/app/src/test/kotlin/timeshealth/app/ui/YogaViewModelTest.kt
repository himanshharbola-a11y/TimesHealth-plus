package timeshealth.app.ui

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import timeshealth.app.FakeAccountGateway
import timeshealth.app.MainDispatcherRule
import timeshealth.app.core.model.ApiError
import timeshealth.app.core.model.AttendanceSource
import timeshealth.app.core.model.BatchPeriod
import timeshealth.app.core.model.JoinMode
import timeshealth.app.core.model.JoinSessionResponse
import timeshealth.app.core.model.LiveClassCard
import timeshealth.app.core.model.LiveClassListResponse
import timeshealth.app.core.model.LiveClassState
import timeshealth.app.core.model.PersonaInfo
import timeshealth.app.core.model.UserPersona
import timeshealth.app.core.model.YogaAttendance
import timeshealth.app.core.model.YogaBatch
import timeshealth.app.core.model.YogaCatalogResponse
import timeshealth.app.core.model.YogaTodayResponse
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.networkError
import timeshealth.app.sessionResponse
import timeshealth.app.ui.state.UiState
import timeshealth.app.ui.yoga.JoinTarget
import timeshealth.app.ui.yoga.YogaGateway
import timeshealth.app.ui.yoga.YogaUi
import timeshealth.app.ui.yoga.YogaViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class YogaViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    // 06:00 IST on 8 Oct.
    private val sixAm = Instant.parse("2026-10-08T00:30:00Z")
    private val now = sixAm.minusSeconds(30 * 60).toEpochMilli()

    private fun batch(id: String, at: Instant, slot: Boolean = false) = YogaBatch(
        id = id, title = "Batch $id", time = "06:00", period = BatchPeriod.MORNING, instructorName = "Asha",
        isUserReminderSlot = slot, isLiveNow = false, startsAt = at.toString(), endsAt = at.plusSeconds(3600).toString(),
    )

    private fun liveClass(id: String, batchId: String?, at: Instant, state: LiveClassState = LiveClassState.STARTING_SOON) = LiveClassCard(
        id = id, title = "Premiere $id", startsAt = at.toString(), endsAt = at.plusSeconds(3600).toString(),
        durationMinutes = 60, isFree = false, state = state, batchId = batchId, canJoin = true,
    )

    private class FakeYoga(val nowMs: Long) : YogaGateway {
        var today = YogaTodayResponse()
        var live: Any = LiveClassListResponse(serverTime = "2026-10-08T00:00:00Z")
        var catalog: Any = YogaCatalogResponse()
        var attendanceAnswer: Any = YogaAttendance(emptyList(), 0, 0, 0, 0)
        var joinAnswer: Any = JoinSessionResponse("https://class.test/zoom", JoinMode.EXTERNAL_APP, true, AttendanceSource.APP)
        val joins = mutableListOf<String>()
        val slots = mutableListOf<String>()
        val todayCalls = mutableListOf<Boolean>()
        var attendanceCalls = 0

        override val todayChanges = MutableSharedFlow<Unit>()
        override suspend fun today(refresh: Boolean): YogaTodayResponse = today.also { todayCalls += refresh }
        override suspend fun catalog(refresh: Boolean) = answer(catalog) as YogaCatalogResponse
        override suspend fun liveClasses(refresh: Boolean) = answer(live) as LiveClassListResponse
        override suspend fun attendance(refresh: Boolean): YogaAttendance {
            attendanceCalls++
            return answer(attendanceAnswer) as YogaAttendance
        }
        override suspend fun join(batchId: String): JoinSessionResponse {
            joins += batchId
            return answer(joinAnswer) as JoinSessionResponse
        }
        override suspend fun setReminderSlot(batchId: String) {
            slots += batchId
        }
        override fun nowMs() = nowMs
        override suspend fun workshops(refresh: Boolean) = emptyList<timeshealth.app.core.model.LiveWorkshop>()
        override suspend fun mySessions(refresh: Boolean) = timeshealth.app.core.model.MySessionsResponse()

        private fun answer(a: Any): Any = if (a is Throwable) throw a else a
    }

    private fun account(persona: UserPersona = UserPersona.YOGA_SUBSCRIBER) = FakeAccountGateway().apply {
        val member = persona == UserPersona.YOGA_SUBSCRIBER
        sessionAnswers += sessionResponse().copy(persona = PersonaInfo(persona, hasYoga = member, hasMarathon = false, label = persona.name))
    }

    private fun YogaViewModel.ui(): YogaUi = (state.value as UiState.Ready).data

    private fun YogaViewModel.joined(batchId: String): JoinTarget? {
        var target: JoinTarget? = null
        join(batchId) { target = it }
        return target
    }

    @Test
    fun `members get the schedule, others the sales page, lapsed members a welcome back`() = runTest {
        val gateway = FakeYoga(now).apply { today = YogaTodayResponse(listOf(batch("b1", sixAm))) }
        for ((persona, member, expired) in listOf(
            Triple(UserPersona.YOGA_SUBSCRIBER, true, false),
            Triple(UserPersona.FREE, false, false),
            Triple(UserPersona.YOGA_EXPIRED, false, true),
        )) {
            val vm = YogaViewModel(gateway, account(persona))
            runCurrent()
            assertThat(vm.ui().member).isEqualTo(member)
            assertThat(vm.ui().expired).isEqualTo(expired)
        }
    }

    @Test
    fun `a batch with a premiere scheduled today plays in the app`() = runTest {
        val gateway = FakeYoga(now).apply {
            today = YogaTodayResponse(listOf(batch("b1", sixAm), batch("b2", sixAm.plusSeconds(3600))))
            live = LiveClassListResponse(
                listOf(
                    // Tomorrow's premiere for b1 and a cancelled one today: neither is today's class.
                    liveClass("lc_tomorrow", "b1", sixAm.plusSeconds(86_400), LiveClassState.SCHEDULED),
                    liveClass("lc_cancelled", "b1", sixAm, LiveClassState.CANCELLED),
                    liveClass("lc_today", "b1", sixAm),
                ),
                serverTime = "2026-10-08T00:00:00Z",
            )
        }
        val vm = YogaViewModel(gateway, account())
        runCurrent()

        assertThat(vm.joined("b1")).isEqualTo(JoinTarget.InApp("lc_today"))
        assertThat(gateway.joins).isEmpty()
    }

    @Test
    fun `a batch without a premiere joins through the server and opens the class link`() = runTest {
        val gateway = FakeYoga(now).apply {
            today = YogaTodayResponse(listOf(batch("b1", sixAm)))
            live = LiveClassListResponse(listOf(liveClass("lc_other", "b9", sixAm)), serverTime = "2026-10-08T00:00:00Z")
        }
        val vm = YogaViewModel(gateway, account())
        runCurrent()

        var target: JoinTarget? = null
        vm.join("b1") { target = it }
        assertThat(vm.busyBatch.value).isEqualTo("b1")
        runCurrent()
        assertThat(target).isEqualTo(JoinTarget.External("https://class.test/zoom"))
        assertThat(gateway.joins).containsExactly("b1")
        assertThat(vm.busyBatch.value).isNull()
    }

    @Test
    fun `a refused join says why and frees the button`() = runTest {
        val gateway = FakeYoga(now).apply {
            today = YogaTodayResponse(listOf(batch("b1", sixAm)))
            joinAnswer = ApiRequestException(409, ApiError("JOIN_NOT_OPEN", "This class opens 60 minutes before it starts."))
        }
        val vm = YogaViewModel(gateway, account())
        runCurrent()

        assertThat(vm.joined("b1")).isNull()
        runCurrent()
        assertThat(vm.message.value).isEqualTo("This class opens 60 minutes before it starts.")
        assertThat(vm.busyBatch.value).isNull()

        gateway.joinAnswer = networkError()
        vm.join("b1") {}
        runCurrent()
        assertThat(vm.message.value).contains("connection")
    }

    @Test
    fun `picking a reminder slot saves it and reloads the schedule`() = runTest {
        val gateway = FakeYoga(now).apply { today = YogaTodayResponse(listOf(batch("b1", sixAm), batch("b2", sixAm.plusSeconds(3600)))) }
        val vm = YogaViewModel(gateway, account())
        runCurrent()
        gateway.today = YogaTodayResponse(listOf(batch("b1", sixAm), batch("b2", sixAm.plusSeconds(3600), slot = true)))

        vm.setReminderSlot("b2")
        runCurrent()

        assertThat(gateway.slots).containsExactly("b2")
        assertThat(gateway.todayCalls.last()).isTrue()
        assertThat(vm.ui().today.batches.single { it.isUserReminderSlot }.id).isEqualTo("b2")
        assertThat(vm.message.value).isEqualTo("Reminders set for this batch.")
    }

    @Test
    fun `optional sections failing leave the tab up`() = runTest {
        val gateway = FakeYoga(now).apply {
            today = YogaTodayResponse(listOf(batch("b1", sixAm)))
            catalog = networkError()
            live = networkError()
        }
        val vm = YogaViewModel(gateway, account())
        runCurrent()

        assertThat(vm.ui().catalog).isNull()
        assertThat(vm.ui().liveClasses).isNull()
        assertThat(vm.ui().today.batches).hasSize(1)
    }

    @Test
    fun `attendance loads only when the Tracker opens, and once`() = runTest {
        val gateway = FakeYoga(now).apply { attendanceAnswer = networkError() }
        val vm = YogaViewModel(gateway, account())
        runCurrent()
        assertThat(gateway.attendanceCalls).isEqualTo(0)
        assertThat(vm.attendance.value).isNull()

        vm.loadAttendance()
        runCurrent()
        assertThat(vm.attendance.value).isInstanceOf(UiState.Failed::class.java)

        gateway.attendanceAnswer = YogaAttendance(listOf("2026-10-07"), 1, 1, 1, 100)
        vm.loadAttendance()
        runCurrent()
        assertThat((vm.attendance.value as UiState.Ready).data.classesAttended).isEqualTo(1)
        vm.loadAttendance()
        runCurrent()
        assertThat(gateway.attendanceCalls).isEqualTo(2)
    }
}
