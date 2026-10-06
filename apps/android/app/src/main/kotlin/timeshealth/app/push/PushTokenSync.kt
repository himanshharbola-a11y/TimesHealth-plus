package timeshealth.app.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timeshealth.app.BuildConfig
import timeshealth.app.core.data.di.ApplicationScope
import timeshealth.app.core.data.push.PushRegistration
import timeshealth.app.core.data.session.SessionRepository
import timeshealth.app.core.data.session.SessionStatus

/**
 * Keeps this device registered for the signed-in user's pushes: on every
 * sign-in (and launch while signed in) and after the user allows
 * notifications, it hands the current FCM token to [PushRegistration], which
 * makes it a no-op when nothing changed.
 *
 * Does nothing when this build has no Firebase (no google-services.json) or
 * notifications aren't allowed — push is never on the critical path.
 */
@Singleton
class PushTokenSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SessionRepository,
    private val registration: PushRegistration,
    private val presenter: NotificationPresenter,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Call once from Application.onCreate. */
    fun start() {
        scope.launch {
            session.status
                .filterIsInstance<SessionStatus.SignedIn>()
                .distinctUntilChanged()
                .collect { sync() }
        }
    }

    /** Registers now, e.g. right after the user granted the notification permission. */
    fun syncNow() {
        scope.launch { sync() }
    }

    private suspend fun sync() {
        if (!firebaseAvailable() || !presenter.canNotify()) return
        if (session.status.value !is SessionStatus.SignedIn) return
        val token = try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // Play services missing or offline: the next launch tries again.
        }
        registration.registerToken(token)
    }

    private fun firebaseAvailable(): Boolean =
        BuildConfig.FIREBASE_ENABLED && FirebaseApp.getApps(context).isNotEmpty()
}
