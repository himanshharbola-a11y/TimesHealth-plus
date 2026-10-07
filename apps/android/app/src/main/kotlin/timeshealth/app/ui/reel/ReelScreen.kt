package timeshealth.app.ui.reel

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.net.URI
import timeshealth.app.BuildConfig
import timeshealth.app.core.integrations.video.PlayableStream
import timeshealth.app.ui.components.LightSystemBarIcons
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.components.ThSpinner
import timeshealth.app.ui.player.buildExoPlayer
import timeshealth.app.ui.theme.Carbon950
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextOnDarkMuted
import timeshealth.app.ui.yoga.RepositoryYogaSessionsGateway

/** A reel ready to play, and whether it is the debug stand-in for placeholder media. */
internal data class ReelStream(val url: String, val isSample: Boolean)

/**
 * The URL arrives through navigation (a deep link could carry anything), so it
 * is checked before it reaches the player: https only. Placeholder `.invalid`
 * media plays the labelled sample in debug builds and is "not available" in
 * release ones.
 */
internal fun reelStream(raw: String?, debug: Boolean): ReelStream? {
    if (raw.isNullOrBlank()) return null
    val host = runCatching { URI(raw).host }.getOrNull() ?: return null
    if (host.endsWith(".invalid")) return if (debug) ReelStream(RepositoryYogaSessionsGateway.DEV_SAMPLE_STREAM, isSample = true) else null
    return if (raw.startsWith("https://")) ReelStream(raw, isSample = false) else null
}

/**
 * Instructor reel (PRD §6.3 "plays inline"): full screen, dark, autoplaying on
 * loop like any short-form reel; tap anywhere to pause. The credit sits at the
 * bottom over a scrim so it reads on any frame.
 */
@Composable
fun ReelScreen(url: String, title: String?, handle: String?, onClose: () -> Unit) {
    val stream = remember(url) { reelStream(url, BuildConfig.DEBUG) }
    LightSystemBarIcons(statusBar = true, navigationBar = true)
    Box(Modifier.fillMaxSize().background(Carbon950)) {
        if (stream != null) {
            ReelPlayer(stream.url)
        } else {
            Message(Icons.Filled.VideocamOff, "This video isn’t available")
        }

        if (!title.isNullOrBlank() || !handle.isNullOrBlank()) {
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                    .padding(start = 18.dp, end = 18.dp, top = 48.dp, bottom = 24.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TagPill(if (stream?.isSample == true) "Sample video" else "Reel", TagTone.CORAL)
                title?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = PaperWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
                handle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Box(
            Modifier.statusBarsPadding().padding(start = 16.dp, top = 8.dp).size(40.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f)).clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = "Close" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, null, tint = PaperWhite) }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ReelPlayer(url: String) {
    val context = LocalContext.current
    val player = remember(url) {
        buildExoPlayer(context, PlayableStream(url, mimeType = if (".m3u8" in url) "application/x-mpegURL" else null)).apply {
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }
    var playing by remember(player) { mutableStateOf(true) }
    var loading by remember(player) { mutableStateOf(true) }
    var failed by remember(player) { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                loading = state == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
                loading = false
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        Modifier.fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                if (player.isPlaying) player.pause() else player.play()
            }
            .semantics { contentDescription = if (playing) "Pause" else "Play" },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    keepScreenOn = true
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        when {
            failed -> Message(Icons.Filled.CloudOff, "This video couldn’t load. Check your connection.")
            loading -> ThSpinner(Modifier.align(Alignment.Center), color = PaperWhite, size = 26.dp)
            // Paused: a quiet play glyph says "tap to resume".
            !playing -> Box(Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, null, tint = PaperWhite, modifier = Modifier.size(36.dp))
            }
        }
    }
}

@Composable
private fun Message(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        Icon(icon, null, tint = TextOnDarkMuted, modifier = Modifier.size(32.dp))
        Text(text, color = TextOnDarkMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}
