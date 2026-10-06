package timeshealth.app.ui.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * The server-corrected clock for countdowns ("Starts in 4 min"), provided by
 * the screen from its ViewModel (core:network ServerClock). Countdowns tick
 * locally from it and never poll the server (PRD §6.1).
 */
val LocalServerNow = compositionLocalOf<() -> Long> { System::currentTimeMillis }

/**
 * Server time in epoch ms, re-read on every whole second while [ticking].
 * Pass `ticking = false` for cards that show no countdown: they read once.
 */
@Composable
fun rememberServerNow(ticking: Boolean = true): Long {
    val clock = LocalServerNow.current
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(ticking, clock) {
        now = clock()
        while (ticking) {
            delay(1_000 - (now % 1_000))
            now = clock()
        }
    }
    return now
}
