package timeshealth.app.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Headers every API request carries, as in the RN client.
 *
 * `Content-Type` is deliberately NOT set here: Retrofit adds `application/json` only when there
 * is a body. A body-less POST (onboarding/skip, claim-upgrade, workshop register) must not claim
 * JSON, because Fastify rejects an empty body declared as JSON with a 400.
 */
internal class StandardHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain.request().newBuilder()
                .header("Accept", "application/json")
                // While the API is hosted through ngrok's free tier, this makes the request reach
                // the API instead of ngrok's browser-warning page (an HTML 200 that would
                // otherwise look like success). Ignored by every other host, so it can stay.
                .header("ngrok-skip-browser-warning", "1")
                .build(),
        )
}
