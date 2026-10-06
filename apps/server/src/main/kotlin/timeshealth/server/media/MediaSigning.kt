package timeshealth.server.media

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.stereotype.Component
import timeshealth.server.config.ServerEnv

/**
 * Port of services/media.ts `signPlaybackUrl`: entitlement-gated playback URLs (docs/04 T4).
 *
 * A signed, short-TTL URL bound to the user and the asset; never a permanent media URL, since
 * the session library is the main thing the yoga membership buys and a stable URL can be
 * forwarded. In production this signs a CDN path; the shape is the same, so swapping the CDN is
 * a one-function change.
 */
@Component
class MediaSigning(private val env: ServerEnv) {

    fun signPlaybackUrl(mediaKey: String, userId: String, now: Instant): String {
        val expires = now.epochSecond + env.mediaUrlTtlSeconds
        val sig = hmacHex(env.mediaSigningSecret, "$mediaKey:$userId:$expires")
        return "${env.mediaBaseUrl}/$mediaKey?u=${enc(userId)}&e=$expires&s=$sig"
    }

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

    companion object {
        fun hmacHex(secret: String, payload: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
