package timeshealth.app.wiring

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import timeshealth.app.core.data.IdentityGateway

/**
 * THE identity seam, Firebase edition. Firebase Auth is a placeholder: Times
 * Internet will swap in its own SSO by writing another [IdentityGateway] and
 * changing one binding in [AppModule] — nothing else in the app knows which
 * provider is behind it (docs/06).
 */
class FirebaseIdentityGateway : IdentityGateway {
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    override fun currentUserId(): String? = auth.currentUser?.uid

    override suspend fun idToken(forceRefresh: Boolean): String? =
        auth.currentUser?.getIdToken(forceRefresh)?.await()?.token

    override suspend fun signOut() = auth.signOut()

    /** Emits the current uid as soon as it is collected, then on every change. */
    override val authState: Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }
}

/**
 * A build without Firebase (no google-services.json): nobody is ever signed in
 * through a provider, so only QA persona sign-in works — the same as the React
 * Native app's unconfigured builds.
 */
class NoIdentityGateway : IdentityGateway {
    override fun currentUserId(): String? = null
    override suspend fun idToken(forceRefresh: Boolean): String? = null
    override suspend fun signOut() = Unit
    override val authState: Flow<String?> = flowOf(null)
}
