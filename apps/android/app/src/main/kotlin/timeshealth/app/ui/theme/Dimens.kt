package timeshealth.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing scale (design-tokens.ts `spacing`), ranked by how often the prototype
 * uses each value. It is a 2dp-based scale, NOT an 8dp grid: 12, 18, 14 and 10
 * are all heavily used. Do not "tidy" them to multiples of 8; the design's
 * rhythm depends on them.
 *
 * Kotlin names can't start with a digit, so the token `'3xl'` is [X3l] and so on.
 */
object Spacing {
    val Xxs = 2.dp
    val Xs = 4.dp
    val Sm = 6.dp
    val Md = 8.dp
    val Lg = 10.dp
    val Xl = 12.dp
    val Xxl = 14.dp
    val X3l = 16.dp
    val X4l = 18.dp
    val X5l = 20.dp
    val X6l = 24.dp
    val X7l = 28.dp
    val X8l = 32.dp
    val X9l = 48.dp
}

/** Layout constants (design-tokens.ts `layout`, `categoryBanner`). */
object ThLayout {
    /**
     * 18dp: the screen gutter. TopHeader, SectionHeader, hero wrapper, LazyRow
     * content padding, promo strip, tiles all inset by it.
     */
    val Gutter = 18.dp

    /** BottomNavBarKt: `navigationBarsPadding().height(64.dp)`, the inset is added on top. */
    val BottomNavHeight = 64.dp

    /** PRD §8.2: a free user's primary race box is ~80% of the first screen. */
    const val PrimaryRaceBoxScreenFraction = 0.8f

    /** CategoryHeroBanner (YogaSessionScreenKt): 165dp tall, 18dp inset, 18dp corners. */
    val CategoryBannerHeight = 165.dp
    val CategoryBannerInsetX = 18.dp
    val CategoryBannerRadius = 18.dp
}
