package timeshealth.app.ui.home

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import timeshealth.app.core.data.repository.HomeRepository
import timeshealth.app.core.model.HomeFeedResponse
import timeshealth.app.core.network.ServerClock

/**
 * What Home needs from core:data (see ui/session/Gateways.kt for why screens
 * take small interfaces): the server-built feed (GET /home, laid out by the
 * admin CMS) and the server-corrected clock its countdowns tick from.
 */
interface HomeGateway {
    suspend fun home(refresh: Boolean = false): HomeFeedResponse

    /** Fires when something that re-orders Home happened (a purchase, a booking, a profile change). */
    val homeChanges: Flow<Unit>

    /** Server time in epoch ms. Never the device clock: it is often wrong. */
    fun nowMs(): Long
}

class RepositoryHomeGateway @Inject constructor(
    private val repository: HomeRepository,
    private val clock: ServerClock,
) : HomeGateway {
    override suspend fun home(refresh: Boolean): HomeFeedResponse = repository.home.get(refresh)
    override val homeChanges: Flow<Unit> get() = repository.home.changes
    override fun nowMs(): Long = clock.now()
}
