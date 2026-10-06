package timeshealth.app.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import timeshealth.app.core.data.cache.CacheKeys
import timeshealth.app.core.data.cache.CachedResource
import timeshealth.app.core.data.cache.ResponseCache
import timeshealth.app.core.data.inbox.InboxSeenStore
import timeshealth.app.core.domain.parseIsoInstant
import timeshealth.app.core.model.NotificationItem
import timeshealth.app.core.model.NotificationListResponse
import timeshealth.app.core.network.TimesHealthApi

/**
 * The bell inbox: every push this user was sent in the last 30 days, so a tap on the bell is never
 * a dead end (§11: push is an enhancement, so this is where a missed one can still be found).
 * An item's route must pass `isAppRoute` (core:domain) before the app navigates to it.
 */
@Singleton
class NotificationsRepository @Inject constructor(
    api: TimesHealthApi,
    cache: ResponseCache,
    private val seen: InboxSeenStore,
) {

    /** GET /notifications. Refresh it when the inbox opens: "did I miss something?" means now. */
    val inbox: CachedResource<NotificationListResponse> =
        CachedResource(cache, CacheKeys.Notifications, 60.seconds) { api.notifications() }

    /** The bell's dot: true when the cached inbox holds something newer than the user last saw. */
    val hasUnread: Flow<Boolean> =
        combine(inbox.data, seen.state) { list, state -> seen.hasUnread(list?.items.orEmpty(), state) }
            .distinctUntilChanged()

    /** Reads the saved "seen" marker (once per process). Until then [hasUnread] is false. */
    suspend fun loadSeen(): Unit = seen.load()

    /** Opening the inbox marks everything in it as seen. */
    suspend fun markSeen(items: List<NotificationItem>): Unit = seen.markSeen(items)

    /** [items] newest first; an unreadable `sentAt` sorts last. */
    fun newestFirst(items: List<NotificationItem>): List<NotificationItem> =
        items.sortedByDescending { parseIsoInstant(it.sentAt)?.toEpochMilli() ?: 0L }
}
