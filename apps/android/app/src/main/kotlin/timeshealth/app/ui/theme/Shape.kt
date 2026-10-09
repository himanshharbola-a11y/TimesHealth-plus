package timeshealth.app.ui.theme

import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Corner radii, from packages/config/design-tokens.ts `radii` (each one counted
 * from the decompiled screens). Use the [ThShapes] shape, or the dp here when a
 * modifier needs the raw radius.
 */
object Radii {
    /** TagPill. */
    val Tag = 6.dp
    val Sm = 8.dp
    val Md = 14.dp

    /** Promo strip, plan cards, onboarding options, form fields. */
    val Option = 16.dp

    /** Content cards: video, reel, article, quote, secondary hero. */
    val Lg = 20.dp

    /** Run tracker tile; the pre-login "Get Started" card. */
    val Xl = 24.dp

    /** Hero cards. */
    val Hero = 26.dp
    val Xxl = 24.dp

    /** Bottom sheets: the login panel, the paywall. Top corners only. */
    val Sheet = 28.dp
}

/** Ready-made shapes for [Radii]. */
object ThShapes {
    val Tag: Shape = RoundedCornerShape(Radii.Tag)
    val Sm: Shape = RoundedCornerShape(Radii.Sm)
    val Md: Shape = RoundedCornerShape(Radii.Md)
    val Option: Shape = RoundedCornerShape(Radii.Option)
    val Lg: Shape = RoundedCornerShape(Radii.Lg)
    val Xl: Shape = RoundedCornerShape(Radii.Xl)
    val Hero: Shape = RoundedCornerShape(Radii.Hero)
    val Xxl: Shape = RoundedCornerShape(Radii.Xxl)

    /** LoginScreenKt's card / PaywallSheetKt: 28dp top corners, square bottom. */
    val SheetTop: Shape = RoundedCornerShape(topStart = Radii.Sheet, topEnd = Radii.Sheet)

    /** design-tokens `radii.pill` (999): fully rounded ends. */
    val Pill: Shape = CircleShape
}

/**
 * The Material shape scale. The design's TimesHealthTheme passes `shapes = null`
 * to MaterialTheme, i.e. M3's defaults, and those already line up with the
 * tokens (small 8 = Sm, medium 12 = Md, large 16 = Lg, extraLarge 28 = Sheet),
 * so M3 components (cards, sheets, text fields) keep the design's corners.
 */
val TimesHealthShapes = Shapes()

/**
 * The refreshed card lift: a soft, wide, low-contrast shadow (not Material's
 * hard elevation), so cards float a little off the warm canvas.
 */
fun androidx.compose.ui.Modifier.softShadow(shape: Shape, elevation: androidx.compose.ui.unit.Dp = 10.dp): androidx.compose.ui.Modifier =
    this.shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.05f),
        spotColor = androidx.compose.ui.graphics.Color(0xFF3B0E4A).copy(alpha = 0.10f),
    )
