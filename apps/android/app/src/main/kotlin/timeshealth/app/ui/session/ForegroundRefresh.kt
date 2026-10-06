package timeshealth.app.ui.session

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton
import timeshealth.app.core.data.cache.CacheKeys
import timeshealth.app.core.data.cache.ResponseCache

/**
 * Back after a minute or more in the background, and anything
 * entitlement-shaped is re-read. Port of the AppState listener in
 * apps/mobile/app/_layout.tsx.
 *
 * A subscription can lapse, or a race result publish, while the app sits in
 * the background. Tab screens stay alive, so without this the app would keep
 * showing the old state until a cold start (PRD §3: every screen resolves
 * against the CURRENT entitlement). Invalidating marks the cached responses
 * stale and fires their `changes`, which the screens' Loadables refetch on.
 *
 * Observes the PROCESS lifecycle (ProcessLifecycleOwner, registered in
 * TimesHealthApp): ON_STOP is RN's "background", ON_START its "active".
 */
@Singleton
class ForegroundRefresh internal constructor(
    private val cache: ResponseCache,
    private val clock: () -> Long,
) : DefaultLifecycleObserver {

    @Inject
    constructor(cache: ResponseCache) : this(cache, SystemClock::elapsedRealtime)

    private var backgroundedAt: Long? = null

    override fun onStop(owner: LifecycleOwner) = onBackground()

    override fun onStart(owner: LifecycleOwner) = onForeground()

    /** The app left the foreground. */
    fun onBackground() {
        backgroundedAt = clock()
    }

    /** The app is back. Invalidates when it was away for [STALE_AFTER_BACKGROUND_MS] or more. */
    fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (clock() - since < STALE_AFTER_BACKGROUND_MS) return
        cache.invalidate(REFRESHED)
    }

    companion object {
        /** RN STALE_AFTER_BACKGROUND_MS. */
        const val STALE_AFTER_BACKGROUND_MS = 60_000L

        /** The RN list: session, home, yoga, marathon, notifications. */
        val REFRESHED = listOf(
            CacheKeys.Session,
            CacheKeys.Home,
            CacheKeys.Yoga,
            CacheKeys.Marathon,
            CacheKeys.Notifications,
        )
    }
}
