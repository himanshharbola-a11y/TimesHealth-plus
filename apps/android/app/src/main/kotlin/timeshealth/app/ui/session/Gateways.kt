package timeshealth.app.ui.session

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import timeshealth.app.core.data.pass.OfflinePass
import timeshealth.app.core.data.pass.OfflinePassStore
import timeshealth.app.core.data.repository.NotificationsRepository
import timeshealth.app.core.data.repository.ProfileRepository
import timeshealth.app.core.data.session.SessionRepository
import timeshealth.app.core.data.session.SessionStatus
import timeshealth.app.core.model.AppConfigResponse
import timeshealth.app.core.model.SessionResponse

/*
 * The seams between the UI and core:data.
 *
 * core:data's repositories are final classes with internal constructors (and
 * deep dependency graphs), so a ViewModel that takes one directly can't be
 * unit-tested with a fake. ViewModels take these small interfaces instead;
 * wiring/UiWiring.kt binds each to a thin adapter over the real repository,
 * and tests pass a fake (see src/test/.../Fakes.kt).
 *
 * Screen agents: add a gateway per feature in the same shape (interface + an
 * `@Inject` adapter class right below it + a `@Binds` in UiWiring), exposing
 * only what the screen needs. Keep them thin: logic belongs in core:domain or
 * the ViewModel, not here.
 */

/** Who is signed in, and the transitions the UI can start. Over [SessionRepository]. */
interface SessionGateway {
    /** Loading until the saved login is restored, then SignedIn / SignedOut. */
    val status: StateFlow<SessionStatus>

    /** QA persona sign-in (debug / DEV_SIGNIN builds). Wipes the previous user's data first. */
    suspend fun signInPersona(token: String)

    /** Signs out and wipes everything the user left on the phone. Always ends signed out. */
    suspend fun signOut()
}

class RepositorySessionGateway @Inject constructor(
    private val sessions: SessionRepository,
) : SessionGateway {
    override val status: StateFlow<SessionStatus> get() = sessions.status
    override suspend fun signInPersona(token: String) = sessions.signInPersona(token)
    override suspend fun signOut() = sessions.signOut()
}

/**
 * The launch config, the signed-in session (profile, entitlements, onboarding
 * flag) and the race passes saved on the phone: what the Gate and the
 * signed-in shell read. Over [ProfileRepository] and [OfflinePassStore].
 */
interface AccountGateway {
    /** GET /config: the force-update gate and maintenance switch (public, pre-sign-in). */
    suspend fun config(refresh: Boolean = false): AppConfigResponse

    /** GET /session. Only once the session status has left Loading. */
    suspend fun session(refresh: Boolean = false): SessionResponse

    /** The last session response held (null until fetched and after a sign-out wipe). */
    val cachedSession: Flow<SessionResponse?>

    /** Emits when the session is out of date and must be refetched. */
    val sessionChanges: Flow<Unit>

    /** Race passes still inside their offline signature window, in save order (PRD §8.3). */
    suspend fun savedPasses(): List<OfflinePass>
}

class RepositoryAccountGateway @Inject constructor(
    private val profile: ProfileRepository,
    private val passes: OfflinePassStore,
) : AccountGateway {
    override suspend fun config(refresh: Boolean): AppConfigResponse = profile.config.get(refresh)
    override suspend fun session(refresh: Boolean): SessionResponse = profile.session.get(refresh)
    override val cachedSession: Flow<SessionResponse?> get() = profile.session.data
    override val sessionChanges: Flow<Unit> get() = profile.session.changes
    override suspend fun savedPasses(): List<OfflinePass> = passes.list()
}

/** The bell's inbox, as far as the TopHeader needs it. Over [NotificationsRepository]. */
interface InboxGateway {
    /** True while the inbox holds something newer than the user last saw (the coral dot). */
    val hasUnread: Flow<Boolean>

    /** Emits when the inbox is out of date (a push arrived, back from the background). */
    val changes: Flow<Unit>

    /** Reads the saved "seen" marker; until then [hasUnread] is false. */
    suspend fun loadSeen()

    /** Fetches the inbox so [hasUnread] can light up. */
    suspend fun refresh(force: Boolean = false)
}

class RepositoryInboxGateway @Inject constructor(
    private val notifications: NotificationsRepository,
) : InboxGateway {
    override val hasUnread: Flow<Boolean> get() = notifications.hasUnread
    override val changes: Flow<Unit> get() = notifications.inbox.changes
    override suspend fun loadSeen() = notifications.loadSeen()
    override suspend fun refresh(force: Boolean) {
        notifications.inbox.get(refresh = force)
    }
}
