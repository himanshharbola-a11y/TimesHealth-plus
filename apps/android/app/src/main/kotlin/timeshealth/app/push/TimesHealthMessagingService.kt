package timeshealth.app.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timeshealth.app.core.data.di.ApplicationScope
import timeshealth.app.core.data.push.PushRegistration
import timeshealth.app.core.data.session.SessionRepository
import timeshealth.app.core.data.session.SessionStatus
import timeshealth.app.core.integrations.push.PushMessage
import timeshealth.app.core.integrations.push.PushRouter

/**
 * Firebase Cloud Messaging entry point. Pushes are sent by our server's
 * scheduler (class reminders, race day) and, once added, by GrowthRx
 * campaigns scheduled from its panel.
 *
 * - While the app is in the BACKGROUND, Android shows a push itself on the
 *   "reminders" channel and a tap opens MainActivity with the data payload as
 *   extras (so `route` deep-links). This class isn't involved.
 * - In the FOREGROUND, [onMessageReceived] gets it: plugged-in handlers first
 *   (see [timeshealth.app.core.integrations.push.PushHandler]), else our own
 *   notification via [NotificationPresenter].
 * - [onNewToken]: FCM rotated this device's token; the server must learn it.
 *   If GrowthRx needs the token too, hand it over here.
 */
@AndroidEntryPoint
class TimesHealthMessagingService : FirebaseMessagingService() {

    @Inject lateinit var registration: PushRegistration
    @Inject lateinit var session: SessionRepository
    @Inject lateinit var router: PushRouter
    @Inject lateinit var presenter: NotificationPresenter
    @Inject @field:ApplicationScope lateinit var scope: CoroutineScope

    override fun onNewToken(token: String) {
        // Signed out: nobody to register it for. The next sign-in registers it (PushTokenSync).
        if (session.status.value !is SessionStatus.SignedIn) return
        // Exactly the token handed to us — never fetch one here (see PushRegistration).
        scope.launch { registration.onTokenRotated(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val push = PushMessage(
            title = message.notification?.title ?: message.data["title"],
            body = message.notification?.body ?: message.data["body"],
            data = message.data,
        )
        router.route(push) { presenter.show(it) }
    }
}
