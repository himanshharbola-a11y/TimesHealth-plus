package timeshealth.app.ui.tabs

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timeshealth.app.ui.session.AccountGateway
import timeshealth.app.ui.session.InboxGateway
import timeshealth.app.ui.session.SessionGateway

/** What the TopHeader shows. */
@Immutable
data class HeaderState(val userName: String? = null, val hasUnread: Boolean = false)

/**
 * The signed-in shell around the four tabs: the TopHeader's name and unread
 * dot. Lives as long as the Tabs destination (so across tab switches).
 */
@HiltViewModel
class TabsViewModel @Inject constructor(
    account: AccountGateway,
    private val inbox: InboxGateway,
    private val session: SessionGateway,
) : ViewModel() {

    val header: StateFlow<HeaderState> =
        combine(account.cachedSession.map { it?.profile?.name }, inbox.hasUnread) { name, unread ->
            HeaderState(userName = name, hasUnread = unread)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HeaderState())

    init {
        // The bell's dot needs the saved "seen" marker and the inbox itself
        // (RN TopHeader's useNotificationInbox). Push is an enhancement (§11):
        // a failure here only means no dot, never an error on screen.
        viewModelScope.launch { quietly { inbox.loadSeen() } }
        viewModelScope.launch { quietly { inbox.refresh() } }
        // Out of date (a push arrived, back from the background): fetch again.
        viewModelScope.launch { inbox.changes.collect { quietly { inbox.refresh() } } }
    }

    /**
     * Signs out. The app-wide signed-out guard (AppNavHost) then takes the user
     * to Login and clears the back stack. Temporary home: the Profile drawer.
     */
    fun signOut() {
        viewModelScope.launch { session.signOut() }
    }

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort; see the call sites.
        }
    }
}
