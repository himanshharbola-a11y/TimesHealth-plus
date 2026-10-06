package timeshealth.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The design's light colour scheme, slot for slot from
 * decompiled-compose/ui/theme/ThemeKt.java (`LightColorScheme`). Unlisted slots
 * keep M3's defaults, as they do in the design. This is what M3 components
 * (Button, OutlinedTextField, Card, ModalBottomSheet…) colour themselves from:
 * an OutlinedTextField's focused outline is coral because primary is coral.
 */
internal val LightColors: ColorScheme = lightColorScheme(
    primary = CoralBrand,
    onPrimary = PaperWhite,
    primaryContainer = CoralTint,
    onPrimaryContainer = CoralDark,
    secondary = SageBrand,
    onSecondary = PaperWhite,
    secondaryContainer = SageTint,
    onSecondaryContainer = SageBrand,
    tertiary = PlumBrand,
    onTertiary = PaperWhite,
    tertiaryContainer = PlumTint,
    onTertiaryContainer = PlumDeep,
    background = CanvasBg,
    onBackground = TextPrimary,
    surface = PaperWhite,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceSand,
    onSurfaceVariant = TextSecondary,
    error = CrimsonAlert,
    onError = PaperWhite,
    outline = BorderRule,
    outlineVariant = BorderSubtle,
)

/**
 * The app theme. Light only: the app ships `userInterfaceStyle: light` and the
 * design's dark scheme was never used by any screen, so there is no `darkTheme`
 * switch to get wrong. Wrap every `setContent` and every @Preview in it.
 *
 * What lives where:
 * - colours: top-level tokens in Color.kt (`CoralBrand`…), [Tints] / [TagTone];
 * - type: `MaterialTheme.typography` (the design's M3 slots) or [RnType];
 * - shapes: [ThShapes] / [Radii]; spacing: [Spacing], [ThLayout].
 */
@Composable
fun TimesHealthTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = TimesHealthTypography,
        shapes = TimesHealthShapes,
        content = content,
    )
}
