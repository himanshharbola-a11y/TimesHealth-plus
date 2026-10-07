package timeshealth.app.ui

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import timeshealth.app.core.data.session.SessionStatus
import timeshealth.app.core.model.LiveWorkshop
import timeshealth.app.core.model.WorkshopCategory
import timeshealth.app.ui.login.DemoSignIn
import timeshealth.app.ui.login.SignInException
import timeshealth.app.ui.session.SessionGateway
import timeshealth.app.ui.workshop.SeatAction
import timeshealth.app.ui.workshop.seatAction

class WorkshopDemoSignInTest {

    private val start = Instant.parse("2026-10-08T13:30:00Z")
    private val before = start.minusSeconds(3600).toEpochMilli()

    private fun workshop(price: Long? = 29_900, spots: Int = 10, registered: Boolean = false, paid: Boolean = false, join: String? = null) = LiveWorkshop(
        id = "w1", title = "Breathwork", description = "d", category = WorkshopCategory.entries.first(), focusArea = "Sleep",
        imageUrl = "https://img.test/w.jpg", startsAt = start.toString(), durationMinutes = 60, level = "All", platform = "Zoom",
        instructorName = "Asha", instructorTitle = "Guru", instructorAvatarUrl = "https://img.test/a.jpg", pricePaise = price,
        spotsRemaining = spots, totalCapacity = 150, isRegistered = registered, paidSeat = paid, joinUrl = join,
    )

    @Test
    fun `the seat button follows price, seats, registration and time`() {
        assertThat(seatAction(workshop(), false, before)).isEqualTo(SeatAction.BUY)
        assertThat(seatAction(workshop(price = null), false, before)).isEqualTo(SeatAction.REGISTER_FREE)
        assertThat(seatAction(workshop(spots = 0), false, before)).isEqualTo(SeatAction.FULL)
        assertThat(seatAction(workshop(price = null), true, before)).isEqualTo(SeatAction.CANCEL_FREE)
        // Bought in this sheet: the stale workshop says unpaid, the seat knows better.
        assertThat(seatAction(workshop(), true, before, paid = true)).isEqualTo(SeatAction.SUPPORT_TO_CANCEL)
        assertThat(seatAction(workshop(join = "https://zoom.test/j"), true, before)).isEqualTo(SeatAction.JOIN)
        assertThat(seatAction(workshop(), false, start.plusSeconds(2 * 3600).toEpochMilli())).isEqualTo(SeatAction.ENDED)
    }

    private class FakeSession : SessionGateway {
        val tokens = mutableListOf<String>()
        override val status = MutableStateFlow<SessionStatus>(SessionStatus.Loading)
        override suspend fun signInPersona(token: String) {
            tokens += token
        }
        override suspend fun signOut() = Unit
    }

    @Test
    fun `demo sign-in turns what was typed into a QA account, and checks the OTP`() = runTest {
        val session = FakeSession()
        val demo = DemoSignIn(session)
        val challenge = demo.startPhoneSignIn("+919812345678")
        val wrong = runCatching { challenge.confirm("000000") }.exceptionOrNull()
        assertThat(wrong).isInstanceOf(SignInException::class.java)
        assertThat(session.tokens).isEmpty()

        challenge.confirm(DemoSignIn.DEMO_OTP)
        assertThat(session.tokens.single()).isEqualTo("qa_demo_ph_919812345678||")

        demo.signInWithEmail("Priya@Example.com", "secret1", createAccount = true)
        demo.signInWithEmail("priya@example.com", "secret1", createAccount = false)
        // Same email, any case: the same account.
        assertThat(session.tokens[1]).isEqualTo(session.tokens[2])
        assertThat(session.tokens[1]).matches("qa_demo_em_[0-9a-f]{16}\\|\\|")
    }
}
