package timeshealth.app.ui.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import timeshealth.app.core.data.session.SessionStatus
import timeshealth.app.ui.session.SessionGateway

/**
 * App-wide navigation state, scoped to MainActivity: the session status the
 * signed-out guard watches, and a deep link waiting for the tabs.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    session: SessionGateway,
    private val deepLinks: PendingDeepLink,
) : ViewModel() {

    val sessionStatus: StateFlow<SessionStatus> = session.status

    val pendingDeepLink: StateFlow<Route?> = deepLinks.pending

    fun consumeDeepLink(): Route? = deepLinks.consume()
}

/**
 * The signed-out guard's rule (RN `SignedOutRedirect` in app/_layout.tsx): a
 * token rejected anywhere (revoked, the account deleted on another device) or
 * a sign-out flips the session to SignedOut, and whatever screen is showing,
 * the user lands on Login, not on tabs that can only keep failing with 401s.
 * The Gate and Login handle signed-out themselves, so they are exempt.
 */
fun shouldRedirectToLogin(status: SessionStatus, onPublicScreen: Boolean): Boolean =
    status == SessionStatus.SignedOut && !onPublicScreen
