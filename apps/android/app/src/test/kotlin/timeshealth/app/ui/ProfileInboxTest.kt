package timeshealth.app.ui

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import timeshealth.app.MainDispatcherRule
import timeshealth.app.core.model.ApiError
import timeshealth.app.core.model.Concern
import timeshealth.app.core.model.Faq
import timeshealth.app.core.model.Gender
import timeshealth.app.core.model.NotificationItem
import timeshealth.app.core.model.NotificationKind
import timeshealth.app.core.model.Units
import timeshealth.app.core.model.UpdateProfileRequest
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.networkError
import timeshealth.app.sessionResponse
import timeshealth.app.ui.inbox.InboxListGateway
import timeshealth.app.ui.inbox.InboxViewModel
import timeshealth.app.ui.inbox.relativeTime
import timeshealth.app.ui.profile.EditField
import timeshealth.app.ui.profile.EditInput
import timeshealth.app.ui.profile.ProfileGateway
import timeshealth.app.ui.profile.ProfileViewModel
import timeshealth.app.ui.profile.formatDate
import timeshealth.app.ui.profile.patchFor
import timeshealth.app.ui.profile.saveErrorText

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileInboxTest {

    @get:Rule val main = MainDispatcherRule()

    private val today = LocalDate.of(2026, 10, 7)

    // ── Profile edits ─────────────────────────────────────────────────────────

    @Test
    fun `each field validates before it is sent`() {
        fun msg(field: EditField, input: EditInput) = patchFor(field, input, today).exceptionOrNull()?.message

        assertThat(msg(EditField.NAME, EditInput(text = "  "))).isEqualTo("Enter your name.")
        assertThat(patchFor(EditField.NAME, EditInput(text = "  Priya  "), today).getOrThrow()).isEqualTo(UpdateProfileRequest(name = "Priya"))
        assertThat(msg(EditField.EMAIL, EditInput(text = "priya@"))).contains("valid email")
        assertThat(msg(EditField.PHONE, EditInput(text = "12345"))).contains("10-digit")
        assertThat(patchFor(EditField.PHONE, EditInput(text = "98765 43210"), today).getOrThrow().phone).isEqualTo("+919876543210")
        assertThat(patchFor(EditField.DOB, EditInput(dd = "05", mm = "03", yyyy = "1990"), today).getOrThrow().dob).isEqualTo("1990-03-05T00:00:00.000Z")
        assertThat(msg(EditField.DOB, EditInput(dd = "31", mm = "02", yyyy = "1990"))).isNotNull()
        assertThat(msg(EditField.DOB, EditInput(dd = "01", mm = "01", yyyy = "2020"))).isNotNull()
        assertThat(msg(EditField.GENDER, EditInput())).isEqualTo("Choose one option.")
        assertThat(patchFor(EditField.GENDER, EditInput(choice = "NON_BINARY"), today).getOrThrow().gender).isEqualTo(Gender.NON_BINARY)
        assertThat(patchFor(EditField.CONCERN, EditInput(choice = "LOWER_BACK"), today).getOrThrow().concern).isEqualTo(Concern.LOWER_BACK)
    }

    @Test
    fun `server refusals become sentences`() {
        assertThat(saveErrorText(ApiRequestException(409, ApiError("LOGIN_IDENTIFIER", "This is your sign-in email.")), EditField.EMAIL))
            .isEqualTo("This is your sign-in email.")
        assertThat(saveErrorText(ApiRequestException(400, ApiError("INVALID_BODY", "x")), EditField.DOB)).contains("between 13 and 100")
        assertThat(saveErrorText(networkError(), EditField.NAME)).contains("connection")
        assertThat(formatDate("2027-01-15T18:29:59Z")).isEqualTo("15 Jan 2027")
    }

    private class FakeProfile : ProfileGateway {
        val updates = mutableListOf<UpdateProfileRequest>()
        var updateError: Exception? = null
        var deleteError: Exception? = null
        var deleted = 0
        var signedOut = 0
        override suspend fun session(refresh: Boolean) = sessionResponse()
        override val sessionChanges = MutableSharedFlow<Unit>()
        override suspend fun update(body: UpdateProfileRequest) {
            updateError?.let { throw it }
            updates += body
        }
        override suspend fun yogaFaqs() = listOf(Faq("Q", "A"))
        override suspend fun deleteAccount() {
            deleteError?.let { throw it }
            deleted++
        }
        override suspend fun signOut() {
            signedOut++
        }
        override fun today() = LocalDate.of(2026, 10, 7)
    }

    @Test
    fun `a valid edit saves and goes back, a bad one stays with its error`() = runTest {
        val gateway = FakeProfile()
        val vm = ProfileViewModel(gateway)
        runCurrent()
        var back = 0

        vm.save(EditField.NAME, EditInput(text = ""), done = { back++ })
        assertThat(vm.editError.value).isEqualTo("Enter your name.")
        assertThat(gateway.updates).isEmpty()

        vm.save(EditField.NAME, EditInput(text = "Asha"), done = { back++ })
        runCurrent()
        assertThat(gateway.updates).containsExactly(UpdateProfileRequest(name = "Asha"))
        assertThat(back).isEqualTo(1)
        assertThat(vm.editError.value).isNull()

        gateway.updateError = networkError()
        vm.save(EditField.NAME, EditInput(text = "Asha R"), done = { back++ })
        runCurrent()
        assertThat(back).isEqualTo(1)
        assertThat(vm.editError.value).contains("connection")
        assertThat(vm.saving.value).isFalse()
    }

    @Test
    fun `units switch to the other system`() = runTest {
        val gateway = FakeProfile()
        val vm = ProfileViewModel(gateway)
        vm.toggleUnits(metric = true)
        runCurrent()
        assertThat(gateway.updates.single().units).isEqualTo(Units.IMPERIAL)
    }

    @Test
    fun `a failed deletion says nothing was deleted`() = runTest {
        val gateway = FakeProfile().apply { deleteError = networkError() }
        val vm = ProfileViewModel(gateway)
        vm.deleteAccount()
        runCurrent()
        assertThat(vm.message.value).contains("Nothing has been deleted")

        gateway.deleteError = null
        vm.deleteAccount()
        runCurrent()
        assertThat(gateway.deleted).isEqualTo(1)
    }

    // ── Inbox ─────────────────────────────────────────────────────────────────

    private val now = Instant.parse("2026-10-07T06:00:00Z").toEpochMilli()

    private fun item(id: String, minutesAgo: Long) = NotificationItem(
        id = id, kind = NotificationKind.SESSION_REMINDER, title = "T$id", body = "B",
        route = "/live/lc1", sentAt = Instant.ofEpochMilli(now - minutesAgo * 60_000).toString(),
    )

    private class FakeInbox(var answer: Any, var seen: Long?) : InboxListGateway {
        val marked = mutableListOf<List<NotificationItem>>()
        @Suppress("UNCHECKED_CAST")
        override suspend fun items(refresh: Boolean): List<NotificationItem> = (answer as? Throwable)?.let { throw it } ?: answer as List<NotificationItem>
        override fun seenAtMs() = seen
        override suspend fun markSeen(items: List<NotificationItem>) {
            marked += items
        }
        override fun nowMs() = Instant.parse("2026-10-07T06:00:00Z").toEpochMilli()
    }

    @Test
    fun `opening marks items newer than the last look as new, then marks all seen`() = runTest {
        val items = listOf(item("a", 5), item("b", 120))
        val gateway = FakeInbox(items, seen = now - 60 * 60_000)
        val vm = InboxViewModel(gateway)
        vm.opened()
        runCurrent()
        val ui = vm.state.value
        assertThat(ui.items).hasSize(2)
        assertThat(ui.isNew(items[0])).isTrue()
        assertThat(ui.isNew(items[1])).isFalse()
        assertThat(gateway.marked.single()).isEqualTo(items)
    }

    @Test
    fun `a failed load keeps what was there and can retry`() = runTest {
        val gateway = FakeInbox(listOf(item("a", 5)), seen = null)
        val vm = InboxViewModel(gateway)
        vm.opened()
        runCurrent()
        gateway.answer = networkError()
        vm.opened()
        runCurrent()
        assertThat(vm.state.value.failed).isTrue()
        assertThat(vm.state.value.items).hasSize(1)

        gateway.answer = emptyList<NotificationItem>()
        vm.retry()
        runCurrent()
        assertThat(vm.state.value.failed).isFalse()
        assertThat(vm.state.value.items).isEmpty()
    }

    @Test
    fun `times read like a person would say them`() {
        fun ago(min: Long) = relativeTime(Instant.ofEpochMilli(now - min * 60_000).toString(), now)
        assertThat(ago(0)).isEqualTo("Just now")
        assertThat(ago(12)).isEqualTo("12m ago")
        assertThat(ago(130)).isEqualTo("2h ago")
        assertThat(ago(30 * 60)).isEqualTo("Yesterday")
        assertThat(ago(4 * 24 * 60)).isNotEmpty()
        assertThat(relativeTime("garbage", now)).isEmpty()
    }
}
