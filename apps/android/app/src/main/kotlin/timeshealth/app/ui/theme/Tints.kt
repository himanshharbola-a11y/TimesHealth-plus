package timeshealth.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * A soft tint for pills and badges: background, hairline, and the text/icon
 * colour drawn on it. design-tokens.ts `tints`.
 */
@Immutable
data class Tint(val bg: Color, val line: Color, val fg: Color)

/**
 * The tint palette, keyed like design-tokens.ts `tints`. The TagPill pairs
 * (bg/fg) are CommonComponentsKt.TagPill's own; [Plum], [Amber] and [Ocean] are
 * the RN app's additions on the same tokens.
 */
object Tints {
    val Coral = Tint(bg = CoralTint, line = CoralBorder, fg = CoralBrand)
    val Plum = Tint(bg = PlumTint, line = PlumLine, fg = PlumBrand)
    val Sage = Tint(bg = SageTint, line = SageLine, fg = SageBrand)
    val Amber = Tint(bg = GoldTint, line = GoldAccent, fg = GoldAccent)
    val Emerald = Tint(bg = LiveEmeraldTint, line = SageLine, fg = LiveEmerald)
    val Ocean = Tint(bg = CanvasBgSecondary, line = BorderRule, fg = Carbon800)
    val Neutral = Tint(bg = SurfaceSand, line = BorderRule, fg = TextSecondary)
    val Gold = Tint(bg = GoldTint, line = GoldAccent, fg = GoldAccent)

    /** Solid emerald with white text: the one "on" state. */
    val Live = Tint(bg = LiveEmerald, line = LiveEmerald, fg = PaperWhite)
}

/**
 * TagPill tones. The design's `PillStyle` (NEUTRAL, CORAL, SAGE, EMERALD, GOLD,
 * LIVE) plus the RN app's PLUM. The design's `BadgePill(text, "amber")` maps to
 * [GOLD]; any unknown name is [NEUTRAL] ([fromName]).
 */
enum class TagTone(val tint: Tint) {
    CORAL(Tints.Coral),
    SAGE(Tints.Sage),
    EMERALD(Tints.Emerald),
    GOLD(Tints.Gold),
    NEUTRAL(Tints.Neutral),
    PLUM(Tints.Plum),

    /** Solid emerald with a 5dp white dot before the label: "LIVE NOW". */
    LIVE(Tints.Live),
    ;

    companion object {
        /**
         * The design's `BadgePill(text, styleName)` lookup: case-insensitive,
         * "amber" is GOLD, anything else unknown is NEUTRAL. For server-supplied
         * tone names (feed badges).
         */
        fun fromName(name: String?): TagTone = when (name?.lowercase()) {
            "emerald" -> EMERALD
            "live" -> LIVE
            "sage" -> SAGE
            "amber", "gold" -> GOLD
            "coral" -> CORAL
            "plum" -> PLUM
            else -> NEUTRAL
        }
    }
}
