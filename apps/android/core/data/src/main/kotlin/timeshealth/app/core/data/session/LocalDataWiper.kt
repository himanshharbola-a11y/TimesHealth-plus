package timeshealth.app.core.data.session

import javax.inject.Inject
import javax.inject.Singleton
import timeshealth.app.core.data.TrackingSuspender
import timeshealth.app.core.data.cache.ResponseCache
import timeshealth.app.core.data.checkout.PendingOrders
import timeshealth.app.core.data.inbox.InboxSeenStore
import timeshealth.app.core.data.pass.OfflinePassStore
import timeshealth.app.core.data.push.PushRegistration
import timeshealth.app.core.data.quietly

/**
 * Drops everything one user left on the phone. Port of `forgetCachedData` in
 * apps/mobile/src/store/session.ts, called whenever the signed-in identity changes: sign-in,
 * sign-out, a rejected token, or a different account appearing.
 *
 * Phones in India are often shared within a family, so every step is about the NEXT person.
 * Each step is independent and best effort: one failing must not stop the rest.
 */
@Singleton
class LocalDataWiper @Inject constructor(
    private val cache: ResponseCache,
    private val push: PushRegistration,
    private val offlinePasses: OfflinePassStore,
    private val tracking: TrackingSuspender,
    private val inboxSeen: InboxSeenStore,
    private val pendingOrders: PendingOrders,
) {
    suspend fun forgetCachedData() {
        // Every cached response, and any fetch still in flight is kept out of the cache.
        cache.clear()
        // The next user must re-register this device under their own account.
        push.reset()
        // ...and must never see the previous user's race pass.
        quietly { offlinePasses.clear() }
        // ...nor have GPS keep tracking a run for someone who has left. Their runs stay parked
        // on the phone under their name and sync when they return.
        quietly { tracking.suspendTrackingForSignOut() }
        // ...nor find their notifications already marked read by the last person.
        quietly { inboxSeen.reset() }
        // ...nor resume their half-finished checkout.
        pendingOrders.forgetAll()
    }
}
