package timeshealth.app.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import timeshealth.app.core.integrations.video.PlayableStream

/**
 * An ExoPlayer for a [PlayableStream] from the video plug-in point, already
 * preparing and set to play from [startPositionMs]. The stream's headers
 * (signed tokens) go with every request, so a provider like Slike needs no
 * change here. The caller releases it.
 */
@OptIn(UnstableApi::class)
fun buildExoPlayer(context: Context, stream: PlayableStream, startPositionMs: Long = 0L): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(stream.headers)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(DefaultDataSource.Factory(context, http)))
        .build()
        .apply {
            val item = MediaItem.Builder().setUri(stream.url).apply {
                stream.mimeType?.let { setMimeType(if (it.contains("mpegurl", ignoreCase = true)) MimeTypes.APPLICATION_M3U8 else it) }
            }.build()
            setMediaItem(item, startPositionMs)
            playWhenReady = true
            prepare()
        }
}
