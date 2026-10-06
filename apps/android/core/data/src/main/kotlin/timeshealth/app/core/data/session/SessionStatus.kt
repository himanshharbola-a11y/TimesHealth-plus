package timeshealth.app.core.data.session

/** Whether someone is signed in. Port of the RN session store's `status` + `method`. */
sealed interface SessionStatus {

    /**
     * Restoring the saved login on launch. The entry gate must wait for this to end before asking
     * for GET /session: a request made now would go out before the identity provider has
     * restored the user.
     */
    data object Loading : SessionStatus

    data class SignedIn(val method: SignInMethod) : SessionStatus

    data object SignedOut : SessionStatus
}

/** How the user signed in; drives which sign-out steps run. */
enum class SignInMethod {
    /** A real account through the identity provider (Firebase today, Times SSO later). */
    IDENTITY,

    /**
     * A QA persona chosen on the login screen of test builds, accepted only by a server with
     * ALLOW_DEV_TOKENS=true. It wins over a real account, so testers can switch states without
     * signing out of their own.
     */
    PERSONA,
}
