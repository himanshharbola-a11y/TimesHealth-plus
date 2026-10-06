package timeshealth.app.ui.yoga

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import timeshealth.app.ui.components.ErrorState
import timeshealth.app.ui.components.LightSystemBarIcons
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.components.ThSpinner
import timeshealth.app.ui.player.buildExoPlayer
import timeshealth.app.ui.state.UiState
import timeshealth.app.ui.theme.Carbon950
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlayerCanvas
import timeshealth.app.ui.theme.PlumDeep
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.ThFonts

/** VideoPlayerScreenKt: the stage's plum gradient into the player canvas. */
private val StageGradient = Brush.linearGradient(listOf(PlumDeep, PlayerCanvas))
private val Muted = Color.White.copy(alpha = 0.6f)

/** The design's speed toggle cycles 1.0× → 1.25× → 1.5× → 0.75×. */
internal val SPEEDS = listOf(1f, 1.25f, 1.5f, 0.75f)

internal fun speedLabel(rate: Float): String = if (rate == 1f) "1.0×" else "${rate.toString().trimEnd('0').trimEnd('.')}×"

/** "%02d:%02d"; minutes keep counting past 59, as in the design. */
internal fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

private const val NO_CAPTIONS = "Captions aren’t available for this class yet."

/**
 * The in-app player for recordings (PRD §6.3, design VideoPlayerScreenKt): a
 * rounded plum stage holding the picture and the title, then the control panel
 * (scrubber, time and speed, back 10 / play / forward 10) and the leave button.
 *
 * One deliberate departure from the design, as in the RN app: its exit button
 * reads "Save Attendance", but PRD §7.1 says watching a recording does not count
 * toward the streak. Here it marks the session complete, a separate list.
 */
@Composable
fun VideoPlayerRoute(viewModel: VideoPlayerViewModel, onBack: () -> Unit, onPaywall: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stream by viewModel.stream.collectAsStateWithLifecycle()
    val toPaywall by viewModel.toPaywall.collectAsStateWithLifecycle()
    LaunchedEffect(toPaywall) {
        if (toPaywall) {
            viewModel.paywallShown()
            onPaywall()
        }
    }
    var captions by rememberSaveable { mutableStateOf(false) }
    LightSystemBarIcons(statusBar = true, navigationBar = true)

    Box(Modifier.fillMaxSize().background(PlayerCanvas)) {
        when (val s = state) {
            UiState.Loading -> ThSpinner(Modifier.align(Alignment.Center), color = PaperWhite, size = 28.dp)
            is UiState.Failed -> ErrorState(s.error, Modifier.fillMaxSize().safeDrawingPadding(), onRetry = viewModel::retry, onBack = onBack, dark = true)
            is UiState.Ready -> {
                val ui = s.data
                val title = ui.session.title
                val subtitle = "${ui.session.instructor.name} · The Yoga Institute"
                val fallbackMs = ui.session.durationMinutes * 60_000L
                val leave: @Composable () -> Unit = {
                    LeaveButton(ui.completed) { viewModel.leave(onBack) }
                }
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    TopBar(
                        pill = when {
                            ui.session.isLive -> "Live · ${ui.session.todayActiveCount} practicing" to TagTone.LIVE
                            (stream as? StreamState.Ready)?.stream?.isSample == true -> "Sample video" to TagTone.NEUTRAL
                            else -> "On-demand class" to TagTone.NEUTRAL
                        },
                        captions = captions,
                        onCaptions = { captions = !captions },
                        onClose = onBack,
                    )
                    when (val st = stream) {
                        is StreamState.Ready -> Player(st.stream, title, subtitle, fallbackMs, captions, leave, viewModel::retryStream)
                        else -> {
                            Stage(title, subtitle, if (captions) NO_CAPTIONS else null) {
                                when (st) {
                                    StreamState.Unavailable -> StageMessage(Icons.Filled.VideocamOff, "This session’s video isn’t available yet.")
                                    StreamState.Failed -> StageMessage(Icons.Filled.CloudOff, "This video couldn’t load. Check your connection.", viewModel::retryStream)
                                    else -> ThSpinner(color = PaperWhite, size = 26.dp)
                                }
                            }
                            ControlPanel(
                                enabled = false, progress = 0f, timeText = "${clock(0)} / ${clock(fallbackMs)}", speed = 1f, playing = false,
                                leave = leave,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(pill: Pair<String, TagTone>, captions: Boolean, onCaptions: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = PaperWhite) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TagPill(pill.first, pill.second)
            IconButton(onClick = onCaptions, modifier = Modifier.semantics { contentDescription = if (captions) "Captions on" else "Captions off" }) {
                Icon(Icons.Filled.Subtitles, contentDescription = null, tint = if (captions) CoralBrand else Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

/** The stage: weight(1), 20dp inset, R24, plum gradient; the picture over the title. */
@Composable
private fun ColumnScope.Stage(title: String, subtitle: String, captionNote: String?, media: @Composable () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(24.dp)).background(StageGradient),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) { media() }
        Text(
            title, color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp).padding(horizontal = 24.dp),
        )
        Text(subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp).padding(horizontal = 24.dp))
        captionNote?.let {
            Text(
                it, color = PaperWhite, fontSize = 11.5.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp).padding(horizontal = 24.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.73f)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun StageMessage(icon: ImageVector, text: String, onRetry: (() -> Unit)? = null) {
    Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = Muted, modifier = Modifier.size(32.dp))
        Text(text, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        onRetry?.let {
            Text(
                "Try again", color = PaperWhite, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.16f))
                    .clickable(role = Role.Button, onClick = it).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ColumnScope.Player(
    stream: SessionStream,
    title: String,
    subtitle: String,
    fallbackMs: Long,
    captions: Boolean,
    leave: @Composable () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(stream) { buildExoPlayer(context, stream.stream) }
    var playing by remember(player) { mutableStateOf(true) }
    var loading by remember(player) { mutableStateOf(true) }
    var failed by remember(player) { mutableStateOf(false) }
    var hasText by remember(player) { mutableStateOf(false) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var rate by remember(player) { mutableFloatStateOf(1f) }
    // While the thumb is dragged: a local preview, one seek on release.
    var drag by remember(player) { mutableStateOf<Float?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                loading = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE && !failed
                if (state == Player.STATE_READY) duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
                loading = false
            }

            override fun onTracksChanged(tracks: Tracks) {
                hasText = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Away from the app: pause (no audio from the background).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            delay(500)
        }
    }

    // Captions are the stream's own subtitle track, drawn by the PlayerView. A class
    // without one says so instead of showing invented text.
    LaunchedEffect(player, captions, hasText) {
        val group = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            if (captions && group != null) {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            } else {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            }
        }.build()
    }

    val total = if (duration > 0) duration else fallbackMs
    val progress = drag ?: if (total > 0) (position.toFloat() / total).coerceIn(0f, 1f) else 0f

    Stage(title, subtitle, if (captions && !hasText) NO_CAPTIONS else null) {
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
        if (loading && !failed) ThSpinner(color = PaperWhite, size = 26.dp)
        if (failed) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                StageMessage(Icons.Filled.CloudOff, "This video couldn’t load. Check your connection.", onRetry)
            }
        }
    }
    ControlPanel(
        enabled = !failed,
        progress = progress,
        timeText = "${clock((progress * total).toLong())} / ${clock(total)}",
        speed = rate,
        playing = playing,
        onScrub = { drag = it },
        onSeek = {
            player.seekTo((it * total).toLong())
            position = (it * total).toLong()
            drag = null
        },
        onSpeed = {
            rate = SPEEDS[(SPEEDS.indexOf(rate) + 1) % SPEEDS.size]
            player.setPlaybackSpeed(rate)
        },
        onToggle = { toggle(player, total) },
        onBack = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) },
        onForward = { player.seekTo((player.currentPosition + 10_000).coerceAtMost(total)) },
        leave = leave,
    )
}

/** Play/pause; finished, it plays again from the top rather than doing nothing. */
private fun toggle(player: ExoPlayer, total: Long) {
    if (player.isPlaying) return player.pause()
    if (player.playbackState == Player.STATE_ENDED || total > 0 && player.currentPosition >= total - 500) player.seekTo(0)
    player.play()
}

/** padding(24, 18): scrubber, time · speed, 14, transport, 18, leave. */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlPanel(
    enabled: Boolean,
    progress: Float,
    timeText: String,
    speed: Float,
    playing: Boolean,
    leave: @Composable () -> Unit,
    onScrub: (Float) -> Unit = {},
    onSeek: (Float) -> Unit = {},
    onSpeed: () -> Unit = {},
    onToggle: () -> Unit = {},
    onBack: () -> Unit = {},
    onForward: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp)) {
        Column(Modifier.alpha(if (enabled) 1f else 0.4f)) {
            var last by remember { mutableFloatStateOf(progress) }
            // The design's slider: a 20dp PaperWhite thumb on a 4dp track, CoralBrand
            // played and white 20% to go. Drags preview locally and seek once on release.
            Slider(
                value = progress,
                onValueChange = {
                    last = it
                    onScrub(it)
                },
                onValueChangeFinished = { onSeek(last) },
                enabled = enabled,
                thumb = { Box(Modifier.size(20.dp).clip(CircleShape).background(PaperWhite)) },
                track = { state ->
                    val fraction = (state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(CoralBrand))
                    }
                },
                modifier = Modifier.semantics { contentDescription = "Seek" },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(timeText, color = Muted, fontSize = 11.sp)
                Text(
                    speedLabel(speed), color = CoralBrand, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onSpeed)
                        .semantics { contentDescription = "Playback speed ${speedLabel(speed)}" }.padding(6.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds", tint = PaperWhite, modifier = Modifier.size(30.dp))
                }
                Box(
                    Modifier.size(62.dp).clip(CircleShape).background(PaperWhite).clickable(enabled = enabled, role = Role.Button, onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play", tint = Carbon950, modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = onForward, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds", tint = PaperWhite, modifier = Modifier.size(30.dp))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        leave()
    }
}

@Composable
private fun LeaveButton(completed: Boolean, onClick: () -> Unit) {
    var leaving by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.16f))
            .clickable(enabled = !leaving, role = Role.Button) {
                leaving = true
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (completed) "Leave Session · Completed ✓" else if (leaving) "Saving…" else "Leave Session · Mark Complete",
            color = PaperWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        )
    }
}
