package timeshealth.app.core.network

/**
 * Supplies the bearer token for each authenticated request. Implemented outside this module
 * (the app wires it), so the network layer never knows which identity provider is in use.
 *
 * Two kinds of credential, as in the RN client (apps/mobile/src/api/client.ts):
 * - A QA persona token, chosen on the login screen of test builds and stored by the app. Only a
 *   server with ALLOW_DEV_TOKENS=true accepts it. When set it wins, so testers can switch
 *   states without signing out of a real account.
 * - A fresh ID token from the identity provider (Firebase today, Times SSO later). Never stored
 *   by us: the provider keeps the session and refreshes the token before it expires, so ask it
 *   on every call rather than caching one here.
 */
interface TokenProvider {
    /**
     * The token for the next request, or null when signed out. Called on an OkHttp background
     * thread for every authenticated request, so it may suspend (e.g. on a token refresh), but
     * should be quick when the provider has a cached token.
     *
     * Throwing is treated exactly like returning null: the request goes out without a token.
     * That matches RN, where a failing secure store or provider means "signed out", not a
     * crash, and it is safe because a 401 for a request that carried no token never signs the
     * user out (see [AuthInterceptor]).
     *
     * The value goes into an HTTP header verbatim, so it must be printable ASCII. JWTs and
     * persona tokens are.
     */
    suspend fun token(): String?
}
