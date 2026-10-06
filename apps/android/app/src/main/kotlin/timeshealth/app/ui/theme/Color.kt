package timeshealth.app.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * The TimesHealth+ palette.
 *
 * Every token below is the literal from the designer's own Compose app
 * (assets/design-reference/decompiled-compose/ui/theme/ColorKt.java), with the
 * same names, so a screen ported from the decompiled source reads the same:
 * `ColorKt.getCoralBrand()` there is `CoralBrand` here. The hex values also
 * match packages/config/design-tokens.ts `colors`, which is what the RN app
 * used (`colors.coralBrand`).
 *
 * Rules for screen code:
 * - Never write `Color(0x…)` in a screen. If a value is missing here, find it in
 *   the decompiled screen first and add it below with a comment saying where
 *   it comes from.
 * - The app is light-only (RN `userInterfaceStyle: light`); there is no dark
 *   scheme to keep in sync. Dark SURFACES (heroes, the bib, the player) use the
 *   Carbon tokens directly.
 */

/** [CanvasBg] as a constant, for `@Preview(backgroundColor = …)` (annotations need a const). */
const val PREVIEW_CANVAS_ARGB: Long = 0xFFFAF7F3

/** [LoginBackdrop] as a constant, for previews of the dark login screens. */
const val PREVIEW_LOGIN_ARGB: Long = 0xFF1C0722

// ── Dark surfaces: hero banners, the bib, live states ─────────────────────────
val Carbon950 = Color(0xFF121417)
val Carbon900 = Color(0xFF1A1D21)
val Carbon800 = Color(0xFF242830)
val Carbon700 = Color(0xFF323742)

// ── App canvas: warm off-white, not pure grey ─────────────────────────────────
val CanvasBg = Color(0xFFFAF7F3)
val CanvasBgSecondary = Color(0xFFF3ECE3)
val PaperWhite = Color(0xFFFFFFFF)
val SurfaceSand = Color(0xFFF1EBE3)

val BorderRule = Color(0xFFE6DFD6)

/** 10% black (0x1A000000): composites over imagery, unlike a flat grey. */
val BorderSubtle = Color(0x1A000000)

// ── Coral: primary brand / marathon ───────────────────────────────────────────
val CoralBrand = Color(0xFFE8533A)
val CoralBright = Color(0xFFFF5733)
val CoralDark = Color(0xFFC73C26)
val CoralTint = Color(0xFFFFF0EC)
val CoralBorder = Color(0xFFFFD5CC)

// ── Plum: yoga / premium ──────────────────────────────────────────────────────
val PlumDeep = Color(0xFF3B0E4A)
val PlumBrand = Color(0xFF6B2E8F)
val PlumTint = Color(0xFFF3EAF8)
val PlumLine = Color(0xFFE3D3EE)

// ── Sage: diet / wellness ─────────────────────────────────────────────────────
val SageBrand = Color(0xFF1B4931)
val SageSecondary = Color(0xFF1D9E75)
val SageTint = Color(0xFFEBF5F0)
val SageLine = Color(0xFFCCE8D9)

/** Reserved for the live-session state (PRD §6.1 "visually distinct live state"). */
val LiveEmerald = Color(0xFF10B981)
val LiveEmeraldTint = Color(0xFFE8F8F2)

val GoldAccent = Color(0xFFC98A16)
val GoldTint = Color(0xFFFCF3E2)

val AmberWarn = Color(0xFFF59E0B)
val CrimsonAlert = Color(0xFFEF4444)

// ── Text ──────────────────────────────────────────────────────────────────────
val TextPrimary = Color(0xFF1A1418)
val TextSecondary = Color(0xFF514A57)
val TextMuted = Color(0xFF8A8390)
val TextOnDark = Color(0xFFFFFFFF)
val TextOnDarkMuted = Color(0xFFB5AFB9)

// ─────────────────────────────────────────────────────────────────────────────
// Design literals: colours a decompiled SCREEN writes inline rather than taking
// from ColorKt. Kept here (named after where they come from) so no screen has
// to repeat a hex value.
// ─────────────────────────────────────────────────────────────────────────────

/** LoginScreenKt backdrop, Color(0xFF1C0722), behind the highlight and the sign-in card. */
val LoginBackdrop = Color(0xFF1C0722)

/** VideoPlayerScreenKt canvas, Color(0xFF150A1B). Also the end colour of splash slide 1. */
val PlayerCanvas = Color(0xFF150A1B)

/**
 * OnboardingScreenKt.PreLoginSplashScreen: the three slides' vertical gradients
 * (top → bottom), in slide order: yoga, marathon, diet.
 */
val SplashSlideGradients: List<List<Color>> = listOf(
    listOf(PlumDeep, PlayerCanvas),
    listOf(Color(0xFF1E284A), Color(0xFF11172E)),
    listOf(Color(0xFF2E1C07), Color(0xFF181005)),
)

/** PreLoginSplashScreen's idle carousel bar, Color(0x44FFFFFF). */
val SplashIndicatorIdle = Color(0x44FFFFFF)

/** LoginScreenKt's idle highlight bar: Color.White.copy(alpha = 0.3f). */
val LoginIndicatorIdle = Color.White.copy(alpha = 0.3f)

/** Body copy on the dark splash and login backdrops: PaperWhite.copy(alpha = 0.8f). */
val TextOnDarkSoft = PaperWhite.copy(alpha = 0.8f)

/** ThCardImage's default image placeholder: vertical PlumDeep → Carbon950. */
val ImagePlaceholderGradient: List<Color> = listOf(PlumDeep, Carbon950)

/** ThCardImage's default scrim over a photo: vertical transparent → black at 65%. */
val ImageScrimGradient: List<Color> = listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))

/**
 * Category hero banner gradients, keyed by YogaCategory.bannerTheme
 * (design-tokens.ts `categoryGradients`; CategoryHeroBanner in the decompiled
 * YogaSessionScreenKt). Horizontal, left → right.
 */
object CategoryGradients {
    val Plum = listOf(Color(0xFF2C1338), Color(0xFF4A1A59))
    val Coral = listOf(Color(0xFF8A1E14), Color(0xFFC7432B))
    val Amber = listOf(Color(0xFF7A4A0A), Color(0xFFB87820))
    val Ocean = listOf(Color(0xFF0F3554), Color(0xFF1E5B8C))
    val Emerald = listOf(Color(0xFF0C4A31), Color(0xFF1B7A53))
    val Sage = listOf(Color(0xFF2E453B), Color(0xFF4A6B5D))

    /** The gradient for a `bannerTheme` string; unknown themes fall back to plum. */
    fun forTheme(theme: String?): List<Color> = when (theme?.uppercase()) {
        "CORAL" -> Coral
        "AMBER" -> Amber
        "OCEAN" -> Ocean
        "EMERALD" -> Emerald
        "SAGE" -> Sage
        else -> Plum
    }
}

/**
 * M3's disabled-content and disabled-container alphas on [TextPrimary]
 * (onSurface at 38% / 12%), as the design's disabled Buttons draw them.
 */
val DisabledContent = TextPrimary.copy(alpha = 0.38f)
val DisabledContainer = TextPrimary.copy(alpha = 0.12f)
