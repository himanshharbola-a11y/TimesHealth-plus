package timeshealth.app.wiring

import java.time.Instant
import javax.inject.Inject
import timeshealth.app.core.data.repository.ProfileRepository
import timeshealth.app.core.data.repository.RunsRepository
import timeshealth.app.core.data.session.SessionRepository
import timeshealth.app.core.data.session.SessionStatus
import timeshealth.app.core.model.UploadRunRequest
import timeshealth.app.core.model.Units
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.core.runtracker.FinishedRun
import timeshealth.app.core.runtracker.PermanentUploadException
import timeshealth.app.core.runtracker.RunDisplayPreferences
import timeshealth.app.core.runtracker.RunOwnerProvider
import timeshealth.app.core.runtracker.RunUploader

/**
 * Uploads a finished run as the signed-in user (POST /runs, idempotent on the
 * run id). A network failure or server error throws, so the run stays on the
 * phone and the worker retries. A 4xx the server will never accept — a
 * validation refusal, not auth or rate limiting — becomes
 * [PermanentUploadException], so one bad run can't block every later one.
 */
class ApiRunUploader @Inject constructor(
    private val runs: RunsRepository,
) : RunUploader {
    override suspend fun upload(run: FinishedRun) {
        try {
            runs.upload(
                UploadRunRequest(
                    id = run.id,
                    startedAt = Instant.ofEpochMilli(run.startedAt).toString(),
                    endedAt = Instant.ofEpochMilli(run.endedAt).toString(),
                    distanceKm = run.distanceKm,
                    durationSeconds = run.durationSeconds,
                    avgPaceSecPerKm = run.avgPaceSecPerKm,
                    caloriesBurned = run.caloriesBurned,
                    // Always sent — null when there is no route (the server requires the key).
                    routePolyline = run.routePolyline,
                    hasAccuracyWarning = run.hasAccuracyWarning,
                ),
            )
        } catch (e: ApiRequestException) {
            if (e.status in 400..499 && e.status !in RETRYABLE_4XX) {
                throw PermanentUploadException("Run ${run.id} refused: ${e.status} ${e.code}", e)
            }
            throw e
        }
    }

    private companion object {
        /** 401 (session ended), 408 (timeout), 429 (rate limited): worth retrying later. */
        val RETRYABLE_4XX = setOf(401, 408, 429)
    }
}

/**
 * Runs belong to the signed-in PROFILE (shared phones): one person's run must
 * never show for, or upload as, someone else.
 */
class SessionRunOwner @Inject constructor(
    private val session: SessionRepository,
    private val profile: ProfileRepository,
) : RunOwnerProvider {
    override suspend fun currentOwner(): String? {
        if (session.status.value !is SessionStatus.SignedIn) return null
        return runCatching { (profile.session.peek() ?: profile.session.get()).profile.id }.getOrNull()
    }
}

/** The tracking notification shows miles for users who chose imperial units. */
class ProfileUnits @Inject constructor(
    private val profile: ProfileRepository,
) : RunDisplayPreferences {
    override suspend fun imperial(): Boolean = profile.session.peek()?.profile?.units == Units.IMPERIAL
}
