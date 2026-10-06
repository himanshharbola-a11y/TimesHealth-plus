package timeshealth.app.core.network

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import retrofit2.Invocation

/**
 * Adds `Authorization: Bearer <token>` to every request except [Anonymous] ones, and reports a
 * rejected token through [onUnauthorized].
 *
 * Session-ending rules, from the RN client and the server's auth plugin (apps/api/src/auth.ts):
 * - Only a 401 means the session is over. A 503 means the server is having a bad moment and must
 *   NEVER sign the user out: the API answers 503, not 401, when its database or the identity
 *   provider is unreachable for exactly this reason. Collapsing the two would sign every active
 *   user out during a two-second database blip.
 * - Only a REJECTED token ends the session. A 401 for a request that carried no token at all
 *   (one that fired before the identity provider finished restoring the saved login on launch,
 *   or an [Anonymous] call) must not sign a real user out.
 *
 * The 401 is still returned as-is, so the caller also gets an [ApiRequestException] with
 * status 401; [onUnauthorized] is the app-wide "session ended" signal on top of that.
 *
 * Runs on OkHttp's threads. Anything thrown from an interceptor that isn't an [IOException]
 * is re-thrown by OkHttp on its dispatcher thread and crashes the app, so every failure here
 * is either absorbed or converted to an [IOException].
 */
internal class AuthInterceptor(
    private val tokenProvider: TokenProvider,
    private val onUnauthorized: () -> Unit,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.isAnonymous()) {
            // Belt and braces: an anonymous call goes out without a credential, whoever built it.
            return chain.proceed(request.newBuilder().removeHeader(AUTHORIZATION).build())
        }

        val token = currentToken()
            ?: return chain.proceed(request)

        val authorized = try {
            request.newBuilder().header(AUTHORIZATION, "Bearer $token").build()
        } catch (e: IllegalArgumentException) {
            // OkHttp rejects header values with control or non-ASCII characters. Never echo the
            // value: it is a credential.
            throw IOException("The sign-in token can't be sent in a header", e)
        }

        val response = chain.proceed(authorized)
        if (response.code == HTTP_UNAUTHORIZED) notifyUnauthorized()
        return response
    }

    /** RN parity: a provider that fails means "signed out" for this request, never a crash. */
    private fun currentToken(): String? =
        try {
            // OkHttp interceptors are blocking and run on OkHttp's own background threads, so
            // bridging to the suspending provider here never blocks the main thread.
            runBlocking { tokenProvider.token() }?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }

    private fun notifyUnauthorized() {
        try {
            onUnauthorized()
        } catch (e: Exception) {
            // The caller still receives the 401 as an ApiRequestException, so the failure isn't
            // silent; letting a broken handler escape would crash the app from OkHttp's thread.
        }
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
        const val HTTP_UNAUTHORIZED = 401
    }
}

/** True for requests made through a [TimesHealthApi] method annotated [Anonymous]. */
internal fun Request.isAnonymous(): Boolean =
    tag(Invocation::class.java)?.method()?.isAnnotationPresent(Anonymous::class.java) == true
