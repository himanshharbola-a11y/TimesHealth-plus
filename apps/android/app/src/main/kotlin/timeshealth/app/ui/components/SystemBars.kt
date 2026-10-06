package timeshealth.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.view.WindowCompat

/**
 * While this is in the composition, the system bar icons are drawn LIGHT
 * (for dark screens drawn edge to edge: the login backdrop, the player, the
 * bib). Restores what was there when it leaves.
 *
 * The app draws edge to edge with dark icons by default (MainActivity), as the
 * design does (`enableEdgeToEdge()` on a light canvas).
 *
 * @param statusBar light status-bar icons.
 * @param navigationBar light navigation-bar icons (only when the bottom of the
 *   screen is dark too).
 */
@Composable
fun LightSystemBarIcons(statusBar: Boolean = true, navigationBar: Boolean = false) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view, statusBar, navigationBar) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val statusBefore = controller.isAppearanceLightStatusBars
            val navBefore = controller.isAppearanceLightNavigationBars
            // "AppearanceLight" means light BARS, i.e. dark icons; light icons are false.
            if (statusBar) controller.isAppearanceLightStatusBars = false
            if (navigationBar) controller.isAppearanceLightNavigationBars = false
            onDispose {
                controller.isAppearanceLightStatusBars = statusBefore
                controller.isAppearanceLightNavigationBars = navBefore
            }
        }
    }
}

/** The Activity behind a (possibly wrapped) context. */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * A dashed rounded border, as RN draws `borderStyle: 'dashed'` on Android
 * (dashes and gaps 3× the stroke width). Used for the QA personas box.
 */
fun Modifier.dashedBorder(width: Dp, color: Color, radius: Dp): Modifier = drawBehind {
    val stroke = width.toPx()
    val dash = stroke * 3
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
    )
}
