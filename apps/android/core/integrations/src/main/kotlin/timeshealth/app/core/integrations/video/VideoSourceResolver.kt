package timeshealth.app.core.integrations.video

import javax.inject.Inject

/**
 * PLUG-IN POINT — where videos and live classes come from.
 *
 * Content (from the admin CMS) refers to a video as a [VideoRef]: which
 * provider hosts it plus that provider's id — "url" for a plain HLS/MP4
 * address today, "slike" for Slike-hosted streams. The player asks [Videos]
 * to turn the ref into a [PlayableStream] and plays it; it never knows which
 * provider was behind it.
 *
 * Live classes are PREMIERES: pre-recorded, streamed at a scheduled time.
 * [PlayableStream.premiereStartEpochMs] lets the player join "live" at the
 * right point (now − start) instead of from the beginning.
 *
 * TODAY: [DirectUrlResolver] handles provider "url".
 *
 * HOW TO PLUG IN SLIKE
 *  1. Add Slike's SDK/API client to app/build.gradle.kts (or call its REST API).
 *  2. Write `class SlikeResolver @Inject constructor(...) : VideoSourceResolver`
 *     with `provider = "slike"`: exchange the Slike media id for a playable
 *     (signed/DRM) URL and, for premieres, the scheduled start.
 *  3. In app/.../wiring/IntegrationsModule.kt add ONE line:
 *       `@Binds @IntoSet fun slike(impl: SlikeResolver): VideoSourceResolver`
 * Admins can then add Slike ids in the CMS and they just play.
 */
interface VideoSourceResolver {
    /** The provider key this resolver handles ("url", "slike", …). */
    val provider: String

    suspend fun resolve(ref: VideoRef): PlayableStream
}

/** A video as content refers to it. */
data class VideoRef(
    /** "url" | "slike" | … — must match a registered [VideoSourceResolver.provider]. */
    val provider: String,
    /** The provider's id; for "url", the address itself. */
    val id: String,
    /** For premieres (scheduled live classes): when the stream starts. */
    val premiereStartEpochMs: Long? = null,
)

/** Something the player can play. */
data class PlayableStream(
    val url: String,
    /** "application/x-mpegURL" for HLS, "video/mp4", … ; null = let the player sniff. */
    val mimeType: String? = null,
    /** Request headers the stream needs (signed tokens), if any. */
    val headers: Map<String, String> = emptyMap(),
    /** Premiere start; the player seeks to (now − start) so everyone is "in sync". */
    val premiereStartEpochMs: Long? = null,
)

/** Thrown when no resolver is registered for a ref's provider. */
class UnsupportedVideoProviderException(provider: String) :
    IllegalStateException("No video provider \"$provider\" is plugged in")

/**
 * The single entry point: picks the resolver for a ref's provider. Adding a
 * provider never touches this class or the player.
 */
class Videos @Inject constructor(
    resolvers: Set<@JvmSuppressWildcards VideoSourceResolver>,
) {
    private val byProvider = resolvers.associateBy { it.provider }

    suspend fun resolve(ref: VideoRef): PlayableStream =
        (byProvider[ref.provider] ?: throw UnsupportedVideoProviderException(ref.provider)).resolve(ref)

    fun supports(provider: String): Boolean = provider in byProvider
}

/** Plays plain HLS/MP4 addresses as-is (provider "url"). */
class DirectUrlResolver @Inject constructor() : VideoSourceResolver {
    override val provider: String = "url"

    override suspend fun resolve(ref: VideoRef): PlayableStream = PlayableStream(
        url = ref.id,
        mimeType = if (ref.id.contains(".m3u8")) "application/x-mpegURL" else null,
        premiereStartEpochMs = ref.premiereStartEpochMs,
    )
}
