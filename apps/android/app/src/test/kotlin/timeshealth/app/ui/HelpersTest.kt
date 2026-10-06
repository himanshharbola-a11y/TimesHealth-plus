package timeshealth.app.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import timeshealth.app.FakeAccountGateway
import timeshealth.app.FakeHomeGateway
import timeshealth.app.FakeInboxGateway
import timeshealth.app.FakeSessionGateway
import timeshealth.app.MainDispatcherRule
import timeshealth.app.core.data.session.SessionStatus
import timeshealth.app.homeResponse
import timeshealth.app.networkError
import timeshealth.app.sessionResponse
import timeshealth.app.ui.components.avatarInitial
import timeshealth.app.ui.home.HomeViewModel
import timeshealth.app.ui.home.firstNameOf
import timeshealth.app.ui.state.UiState
import timeshealth.app.ui.tabs.TabsViewModel
import timeshealth.app.ui.theme.TagTone

@OptIn(ExperimentalCoroutinesApi::class)
class HelpersTest {

    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `the greeting uses the first name only, never an empty one`() {
        assertThat(firstNameOf("Priya Sharma")).isEqualTo("Priya")
        assertThat(firstNameOf("  Arjun   K  ")).isEqualTo("Arjun")
        assertThat(firstNameOf("   ")).isEqualTo("there")
        assertThat(firstNameOf(null)).isEqualTo("there")
    }

    @Test
    fun `the avatar shows the upper-cased first letter, P without a name`() {
        assertThat(avatarInitial("priya")).isEqualTo("P")
        assertThat(avatarInitial(" arjun")).isEqualTo("A")
        assertThat(avatarInitial("")).isEqualTo("P")
        assertThat(avatarInitial(null)).isEqualTo("P")
    }

    @Test
    fun `badge tone names resolve as the design's BadgePill`() {
        assertThat(TagTone.fromName("Sage")).isEqualTo(TagTone.SAGE)
        assertThat(TagTone.fromName("amber")).isEqualTo(TagTone.GOLD)
        assertThat(TagTone.fromName("LIVE")).isEqualTo(TagTone.LIVE)
        assertThat(TagTone.fromName("ocean")).isEqualTo(TagTone.NEUTRAL)
        assertThat(TagTone.fromName(null)).isEqualTo(TagTone.NEUTRAL)
    }

    @Test
    fun `home greets by first name from the feed and knows the user's membership`() = runTest {
        val account = FakeAccountGateway().apply { sessionAnswers += sessionResponse(name = "Priya Sharma") }
        val feed = FakeHomeGateway().apply { answers += homeResponse(userName = "Priya Sharma") }
        val home = HomeViewModel(feed, account)
        advanceUntilIdle()

        val ready = home.state.value as UiState.Ready
        assertThat(ready.data.greeting.firstName).isEqualTo("Priya")
        assertThat(ready.data.greeting.dayLine).isEqualTo("Good morning · Thursday")
        assertThat(ready.data.entitledToYoga).isFalse()
    }

    @Test
    fun `home without the feed shows an error with a retry that recovers`() = runTest {
        val account = FakeAccountGateway().apply { sessionAnswers += sessionResponse(name = null) }
        val feed = FakeHomeGateway().apply {
            answers += networkError()
            answers += homeResponse(userName = null)
        }
        val home = HomeViewModel(feed, account)
        advanceUntilIdle()
        assertThat(home.state.value).isInstanceOf(UiState.Failed::class.java)

        home.retry()
        advanceUntilIdle()
        assertThat((home.state.value as UiState.Ready).data.greeting.firstName).isEqualTo("there")
    }

    @Test
    fun `pull to refresh asks the server again`() = runTest {
        val account = FakeAccountGateway().apply { sessionAnswers += sessionResponse() }
        val feed = FakeHomeGateway().apply { answers += homeResponse() }
        val home = HomeViewModel(feed, account)
        advanceUntilIdle()
        home.refresh()
        advanceUntilIdle()
        assertThat(feed.calls).containsExactly(false, true).inOrder()
    }

    @Test
    fun `the header follows the cached session and the inbox, and fetches the inbox once`() = runTest {
        val account = FakeAccountGateway()
        val inbox = FakeInboxGateway()
        val session = FakeSessionGateway(SessionStatus.SignedIn(timeshealth.app.core.data.session.SignInMethod.PERSONA))
        val tabs = TabsViewModel(account, inbox, session)
        backgroundScope.launch { tabs.header.collect { } }
        advanceUntilIdle()
        assertThat(inbox.refreshes).isEqualTo(1)

        account.cachedSession.value = sessionResponse(name = "Priya Sharma")
        inbox.hasUnread.value = true
        advanceUntilIdle()
        assertThat(tabs.header.value.userName).isEqualTo("Priya Sharma")
        assertThat(tabs.header.value.hasUnread).isTrue()

        tabs.signOut()
        advanceUntilIdle()
        assertThat(session.signOuts).isEqualTo(1)
        assertThat(session.status.value).isEqualTo(SessionStatus.SignedOut)
    }
}
