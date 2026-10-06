package timeshealth.app.ui.live

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.delay
import timeshealth.app.core.integrations.video.PlayableStream
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.player.buildExoPlayer
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.ThShapes

/** Further behind live than this and the player offers "Go live". */
private const val DRIFT_MS = 10_000L

/** Where "live" is in the stream at [nowMs]: the premiere has been running for (now − start). */
internal fun livePositionMs(startsAtMs: Long, nowMs: Long): Long = (nowMs - startsAtMs).coerceAtLeast(0L)

/**
 * The premiere player: Media3 ExoPlayer in a classic PlayerView (through
 * AndroidView, the team's Views interop), with a minimal Compose overlay
 * instead of a seek bar, since a live class has no scrubbing:
 *
 * - starts at the live position (now − start), so everyone is in sync;
 * - pause is allowed; "Go live" jumps back when more than [DRIFT_MS] behind;
 * - leaving the app pauses, and coming back rejoins live;
 * - the screen stays on while it plays.
 *
 * [stream] comes from the video plug-in point (core:integrations Videos), so
 * a Slike resolver changes nothing here; its headers (signed tokens) are sent
 * with every request.
 */
@OptIn(UnstableApi::class)
@Composable
fun PremierePlayer(stream: PlayableStream, startsAtMs: Long, nowMs: () -> Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var failed by remember(stream) { mutableStateOf(false) }
    var videoEnded by remember(stream) { mutableStateOf(false) }
    var playing by remember(stream) { mutableStateOf(true) }
    var behindMs by remember(stream) { mutableLongStateOf(0L) }
    // Only a deliberate pause may leave the viewer behind; buffering never should.
    var userPaused by remember(stream) { mutableStateOf(false) }

    val player = remember(stream) { buildExoPlayer(context, stream, livePositionMs(startsAtMs, nowMs())) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) videoEnded = true
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying || player.playWhenReady
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Away from the app: pause. Back: rejoin the class where it is now.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> if (!videoEnded) {
                    userPaused = false
                    player.seekTo(livePositionMs(startsAtMs, nowMs()))
                    player.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // How far behind live the viewer is. Fallen behind through buffering (the first load
    // included) rather than a pause: catch up, so everyone stays at the same point.
    LaunchedEffect(player) {
        while (true) {
            behindMs = livePositionMs(startsAtMs, nowMs()) - player.currentPosition
            if (!userPaused && behindMs > DRIFT_MS && player.playbackState == Player.STATE_READY) {
                player.seekTo(livePositionMs(startsAtMs, nowMs()))
                behindMs = 0
            }
            delay(1_000)
        }
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            Modifier.align(Alignment.TopStart).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (userPaused && behindMs > DRIFT_MS && !videoEnded) {
                Text(
                    "Go live",
                    color = PaperWhite,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(ThShapes.Tag)
                        .background(CoralBrand)
                        .clickable(role = Role.Button) {
                            userPaused = false
                            player.seekTo(livePositionMs(startsAtMs, nowMs()))
                            player.play()
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            } else {
                TagPill("Live", TagTone.LIVE)
            }
        }

        if (!videoEnded && !failed) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(role = Role.Button) {
                        if (playing) {
                            userPaused = true
                            player.pause()
                        } else {
                            player.play()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = PaperWhite,
                )
            }
        }

        if (failed || videoEnded) {
            Column(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (failed) "The stream didn't load." else "That's the end of today's class.",
                    color = PaperWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                )
                if (failed) {
                    Text(
                        "Try again",
                        color = PaperWhite,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clip(ThShapes.Md)
                            .background(CoralBrand)
                            .clickable(role = Role.Button) {
                                failed = false
                                player.prepare()
                                player.seekTo(livePositionMs(startsAtMs, nowMs()))
                                player.play()
                            }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
