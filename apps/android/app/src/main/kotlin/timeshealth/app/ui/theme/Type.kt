package timeshealth.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The two faces. The design pairs a SERIF display face with a SANS body face;
 * that contrast is the most distinctive thing about it.
 *
 * - [Serif] is the platform serif (`FontFamily.Serif`, Noto Serif on Android),
 *   exactly what the design's `FontFamily.getSerif()` and the RN app's
 *   `FONTS.serif = 'serif'` resolve to.
 * - [Sans] is the system sans (`FontFamily.Default`, Roboto), the design's
 *   `getDefault()` and RN's `'sans-serif'`.
 */
object ThFonts {
    val Serif: FontFamily = FontFamily.Serif
    val Sans: FontFamily = FontFamily.Default
}

/**
 * The design's Material 3 type scale, verbatim from
 * decompiled-compose/ui/theme/TypeKt.java (`TimesHealthTypography`), slot for
 * slot, INCLUDING its colours: the body styles carry TextSecondary / TextMuted,
 * the label styles carry none. So `MaterialTheme.typography.bodyMedium` here
 * renders exactly what `getBodyMedium()` renders in the decompiled screens.
 *
 * | M3 slot        | face  | size/line | weight   | tracking | colour        |
 * |----------------|-------|-----------|----------|----------|---------------|
 * | displayLarge   | serif | 40/44     | Medium   | -0.5     | TextPrimary   |
 * | displayMedium  | serif | 32/36     | Medium   | -0.5     | TextPrimary   |
 * | displaySmall   | serif | 26/30     | Medium   | -0.3     | TextPrimary   |
 * | headlineLarge  | serif | 24/28     | SemiBold | -0.2     | TextPrimary   |
 * | headlineMedium | serif | 20/25     | Medium   | -0.2     | TextPrimary   |
 * | headlineSmall  | sans  | 17/22     | Bold     | -0.1     | TextPrimary   |
 * | titleLarge     | sans  | 16/21     | Bold     | 0        | TextPrimary   |
 * | titleMedium    | sans  | 14/18     | SemiBold | 0        | TextPrimary   |
 * | titleSmall     | sans  | 12/16     | Bold     | 0        | TextPrimary   |
 * | bodyLarge      | sans  | 15/22     | Normal   | 0        | TextSecondary |
 * | bodyMedium     | sans  | 13/19     | Normal   | 0        | TextSecondary |
 * | bodySmall      | sans  | 11/15     | Normal   | 0        | TextMuted     |
 * | labelLarge     | sans  | 13/17     | Bold     | +0.2     | (inherits)    |
 * | labelMedium    | sans  | 11/14     | Bold     | +0.4     | (inherits)    |
 * | labelSmall     | sans  | 9.5/12    | Bold     | +0.8     | (inherits)    |
 *
 * Note: M3 `MaterialTheme` provides `bodyLarge` as the default `LocalTextStyle`,
 * and M3 buttons provide `labelLarge`. The decompiled components rely on that
 * (a bare `Text(fontSize = 9.sp, …)` inherits bodyLarge's line height and
 * colour), and so do the ports here: call `Text` with the same arguments the
 * decompiled code passes and the result matches.
 *
 * The NAMES in packages/config/design-tokens.ts (used by the RN app) differ
 * from the M3 slots for the middle of the scale; see [RnType].
 */
val TimesHealthTypography = Typography(
    displayLarge = TextStyle(
        color = TextPrimary, fontSize = 40.sp, fontWeight = FontWeight.Medium,
        fontFamily = ThFonts.Serif, letterSpacing = (-0.5).sp, lineHeight = 44.sp,
    ),
    displayMedium = TextStyle(
        color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Medium,
        fontFamily = ThFonts.Serif, letterSpacing = (-0.5).sp, lineHeight = 36.sp,
    ),
    displaySmall = TextStyle(
        color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Medium,
        fontFamily = ThFonts.Serif, letterSpacing = (-0.3).sp, lineHeight = 30.sp,
    ),
    headlineLarge = TextStyle(
        color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
        fontFamily = ThFonts.Serif, letterSpacing = (-0.2).sp, lineHeight = 28.sp,
    ),
    headlineMedium = TextStyle(
        color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium,
        fontFamily = ThFonts.Serif, letterSpacing = (-0.2).sp, lineHeight = 25.sp,
    ),
    headlineSmall = TextStyle(
        color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
        fontFamily = ThFonts.Sans, letterSpacing = (-0.1).sp, lineHeight = 22.sp,
    ),
    titleLarge = TextStyle(
        color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
        fontFamily = ThFonts.Sans, lineHeight = 21.sp,
    ),
    titleMedium = TextStyle(
        color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        fontFamily = ThFonts.Sans, lineHeight = 18.sp,
    ),
    titleSmall = TextStyle(
        color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        fontFamily = ThFonts.Sans, lineHeight = 16.sp,
    ),
    bodyLarge = TextStyle(
        color = TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Normal,
        fontFamily = ThFonts.Sans, lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Normal,
        fontFamily = ThFonts.Sans, lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Normal,
        fontFamily = ThFonts.Sans, lineHeight = 15.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 13.sp, fontWeight = FontWeight.Bold,
        fontFamily = ThFonts.Sans, letterSpacing = 0.2.sp, lineHeight = 17.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 11.sp, fontWeight = FontWeight.Bold,
        fontFamily = ThFonts.Sans, letterSpacing = 0.4.sp, lineHeight = 14.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 9.5.sp, fontWeight = FontWeight.Bold,
        fontFamily = ThFonts.Sans, letterSpacing = 0.8.sp, lineHeight = 12.sp,
    ),
)

/**
 * The type scale under the NAMES the RN app used (packages/config/design-tokens.ts
 * `typography`, apps/mobile `type.X`), for porting RN screens without
 * mis-mapping a style. Same values as [TimesHealthTypography]; only the names
 * differ in the middle of the scale:
 *
 * | RN `type.X`   | = M3 slot (MaterialTheme.typography.X) |
 * |---------------|----------------------------------------|
 * | headlineSmall | headlineMedium (serif 20/25)           |
 * | titleLarge    | headlineSmall  (sans 17/22 bold)       |
 * | titleMedium   | titleLarge     (sans 16/21 bold)       |
 * | titleSmall    | titleMedium    (sans 14/18 semibold)   |
 * | titleTiny     | titleSmall     (sans 12/16 bold)       |
 * | labelSmall    | labelMedium    (sans 11/14 bold +0.4)  |
 * | labelTiny     | labelSmall     (sans 9.5/12 bold +0.8) |
 *
 * As in the RN `type` helper, EVERY style here is TextPrimary; the RN screens
 * set any other colour explicitly. Prefer the M3 slots when porting from the
 * decompiled design; use these when porting from apps/mobile.
 */
object RnType {
    private val t = TimesHealthTypography
    private fun TextStyle.ink() = copy(color = TextPrimary)

    val displayLarge = t.displayLarge.ink()
    val displayMedium = t.displayMedium.ink()
    val displaySmall = t.displaySmall.ink()
    val headlineLarge = t.headlineLarge.ink()
    val headlineSmall = t.headlineMedium.ink()

    val titleLarge = t.headlineSmall.ink()
    val titleMedium = t.titleLarge.ink()
    val titleSmall = t.titleMedium.ink()
    val titleTiny = t.titleSmall.ink()

    val bodyLarge = t.bodyLarge.ink()
    val bodyMedium = t.bodyMedium.ink()
    val bodySmall = t.bodySmall.ink()

    /** All-caps eyebrow labels: "MEMBER ENTITLEMENT", "OFFICIAL FINISH TIME". */
    val labelLarge = t.labelLarge.ink()
    val labelSmall = t.labelMedium.ink()

    /** Section eyebrows: the design's M3 labelSmall. */
    val labelTiny = t.labelSmall.ink()
}
