package timeshealth.server.identity

/*
 * THE server-side identity seam (port of apps/api/src/identity/provider.ts).
 *
 * Firebase Auth is a placeholder: Times Internet will swap in its own SSO. Everything outside
 * this package consumes this interface — the bearer filter verifies tokens through it and account
 * deletion erases the provider record through it. Swapping providers means implementing it once
 * and setting AUTH_PROVIDER; resolveUser, routes and the client contract do not change.
 *
 * NOT part of this seam: FCM push. Messaging needs the Firebase APP credentials regardless of who
 * verifies logins, so the push port must depend on [FirebaseAppHolder] directly, never on the
 * provider.
 */

/** What a provider must prove about the caller. Provider-neutral. */
data class VerifiedIdentity(
    /** The provider's stable subject. Stored in User.firebaseUid (see docs/06). */
    val uid: String,
    val email: String?,
    /**
     * True only when the provider has verified the caller OWNS the email. An unverified email must
     * never be used to claim an existing account.
     */
    val emailVerified: Boolean,
    val phone: String?,
    val name: String?,
)

/**
 * A DEFINITIVE "this token is no good" (expired, revoked, malformed, disabled user): 401, which
 * signs the app out. Anything else a provider throws — a network blip, an outage, a quota spike —
 * becomes 503 ("try again"), so an outage never signs every active user out.
 */
class TokenRejected(message: String) : RuntimeException(message)

interface IdentityProvider {
    val name: String

    /** True when credentials/config are present. Cheap and idempotent. */
    fun configured(): Boolean

    /**
     * Verifies a bearer token and returns who it belongs to. Throws [TokenRejected] for a bad
     * token; any other exception means the provider is unavailable.
     */
    fun verify(token: String): VerifiedIdentity

    /**
     * Erases the account at the provider (DELETE /account). False when the provider holds no
     * erasable record (unconfigured, or SSO identities that outlive the app).
     */
    fun deleteAccount(uid: String): Boolean
}

/**
 * Times Internet SSO — NOT IMPLEMENTED YET (port of identity/timesSso.ts). ServerEnv refuses to
 * boot with AUTH_PROVIDER=times-sso, so this only gives the swap an exact shape. What the Times
 * SSO team must supply is listed in apps/api/src/identity/timesSso.ts and docs/06.
 */
class TimesSsoIdentityProvider : IdentityProvider {
    override val name = "times-sso"

    override fun configured() = false

    override fun verify(token: String): VerifiedIdentity =
        throw IllegalStateException(
            "AUTH_PROVIDER=times-sso is selected but not implemented — see docs/06 and src/identity/timesSso.ts",
        )

    override fun deleteAccount(uid: String) = false
}
