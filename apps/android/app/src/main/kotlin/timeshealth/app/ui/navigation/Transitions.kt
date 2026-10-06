package timeshealth.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavBackStackEntry

/*
 * Screen transitions, after the RN native stack's (apps/mobile/app/_layout.tsx):
 * pushes slide in from the right over a still screen (`slide_from_right`),
 * Login / Tabs / the player / reels fade, the run tracker and the bib slide up
 * from the bottom.
 */

private typealias Scope = AnimatedContentTransitionScope<NavBackStackEntry>

private const val DURATION_MS = 300

internal object Transitions {
    val pushEnter: Scope.() -> EnterTransition = {
        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(DURATION_MS))
    }

    /** The screen underneath stays put while the new one covers it. */
    val pushExit: Scope.() -> ExitTransition = { ExitTransition.KeepUntilTransitionsFinished }

    val popEnter: Scope.() -> EnterTransition = { EnterTransition.None }

    val popExit: Scope.() -> ExitTransition = {
        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(DURATION_MS))
    }

    val fadeEnter: Scope.() -> EnterTransition = { fadeIn(tween(DURATION_MS)) }
    val fadeExit: Scope.() -> ExitTransition = { fadeOut(tween(DURATION_MS)) }

    val slideUpEnter: Scope.() -> EnterTransition = {
        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up, tween(DURATION_MS))
    }
    val slideDownExit: Scope.() -> ExitTransition = {
        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down, tween(DURATION_MS))
    }
}
