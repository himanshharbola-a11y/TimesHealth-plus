package timeshealth.app.ui.marathon

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import timeshealth.app.core.data.pass.OfflinePass
import timeshealth.app.core.data.repository.MarathonRepository
import timeshealth.app.core.model.BibTokenResponse
import timeshealth.app.core.model.ClaimUpgradeResponse
import timeshealth.app.core.model.MarathonListResponse
import timeshealth.app.core.model.UpdateParticipantRequest
import timeshealth.app.core.data.repository.RunsRepository
import timeshealth.app.core.domain.UserSignals
import timeshealth.app.core.domain.parseIsoInstant
import timeshealth.app.core.model.RaceLifecycleStatus
import timeshealth.app.ui.session.AccountGateway
import timeshealth.app.core.model.RaceDetailResponse
import timeshealth.app.core.model.ReferralState
import timeshealth.app.core.network.ServerClock

/** What the marathon screens need from core:data (an interface, so their ViewModels are testable). */
interface MarathonGateway {
    suspend fun events(refresh: Boolean = false): MarathonListResponse
    val eventsChanges: Flow<Unit>
    suspend fun raceDetail(eventId: String, refresh: Boolean = false): RaceDetailResponse
    fun raceDetailChanges(eventId: String): Flow<Unit>
    suspend fun referral(refresh: Boolean = false): ReferralState
    suspend fun bibToken(eventId: String): BibTokenResponse
    suspend fun offlinePass(eventId: String): OfflinePass?
    suspend fun claimUpgrade(eventId: String): ClaimUpgradeResponse

    /** T-shirt size and emergency contact; null fields are left as they are. */
    suspend fun updateParticipant(request: UpdateParticipantRequest)

    /** Goal, races and recent running, for the suggested distance (core:domain Personalization.kt). */
    suspend fun signals(): UserSignals
    fun nowMs(): Long
}

class RepositoryMarathonGateway @Inject constructor(
    private val marathon: MarathonRepository,
    private val runs: RunsRepository,
    private val account: AccountGateway,
    private val clock: ServerClock,
) : MarathonGateway {
    override suspend fun signals(): UserSignals {
        val session = runCatching { account.session() }.getOrNull()
        val history = runCatching { runs.history.get() }.getOrNull()
        val since = clock.now() - 30L * 24 * 3600 * 1000
        val recent = history?.runs.orEmpty().filter { (parseIsoInstant(it.startedAt)?.toEpochMilli() ?: 0L) >= since }
        return UserSignals(
            goal = session?.profile?.healthGoal?.name,
            concern = session?.profile?.concern?.name,
            isYogaMember = session?.persona?.hasYoga == true,
            hasUpcomingRace = session?.entitlements?.marathon.orEmpty().any { it.status == RaceLifecycleStatus.UPCOMING },
            runsLast30Days = recent.size,
            kmLast30Days = recent.sumOf { it.distanceKm },
            longestRunKm = history?.totals?.longestKm ?: 0.0,
        )
    }

    override suspend fun events(refresh: Boolean) = marathon.events().get(refresh)
    override val eventsChanges: Flow<Unit> get() = marathon.events().changes
    override suspend fun raceDetail(eventId: String, refresh: Boolean) = marathon.raceDetail(eventId).get(refresh)
    override fun raceDetailChanges(eventId: String): Flow<Unit> = marathon.raceDetail(eventId).changes
    override suspend fun referral(refresh: Boolean) = marathon.referral.get(refresh)
    override suspend fun bibToken(eventId: String) = marathon.bibToken(eventId)
    override suspend fun offlinePass(eventId: String) = marathon.offlinePass(eventId)
    override suspend fun claimUpgrade(eventId: String) = marathon.claimUpgrade(eventId)
    override suspend fun updateParticipant(request: UpdateParticipantRequest) {
        marathon.updateParticipant(request)
    }
    override fun nowMs(): Long = clock.now()
}
