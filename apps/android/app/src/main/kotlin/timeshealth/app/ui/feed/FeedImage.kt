package timeshealth.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import timeshealth.app.ui.components.RemoteImage

/** How a two-stop gradient runs. Compose's `linearGradient` default is diagonal. */
enum class GradientDir { DIAGONAL, VERTICAL, HORIZONTAL }

fun gradient(from: Color, to: Color, dir: GradientDir = GradientDir.DIAGONAL): Brush = when (dir) {
    GradientDir.DIAGONAL -> Brush.linearGradient(listOf(from, to))
    GradientDir.VERTICAL -> Brush.verticalGradient(listOf(from, to))
    GradientDir.HORIZONTAL -> Brush.horizontalGradient(listOf(from, to))
}

/**
 * The design's ThCardImage with its three layers, as the RN `CardImage`: a
 * brand gradient (seen when there is no photo, while it loads, or if it
 * fails), the photo, then a scrim so white text on top stays legible on any
 * photo. Fills its parent; the parent clips the corners. [content] is drawn
 * over the scrim.
 */
@Composable
fun FeedImage(
    url: String?,
    fallback: Brush,
    scrim: Brush?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier) {
        RemoteImage(url = url, contentDescription = null, modifier = Modifier.fillMaxSize(), placeholder = fallback)
        if (scrim != null) Box(Modifier.fillMaxSize().background(scrim))
        content()
    }
}
