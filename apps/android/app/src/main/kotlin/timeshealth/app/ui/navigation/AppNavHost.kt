package timeshealth.app.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import timeshealth.app.core.data.session.SessionStatus
import timeshealth.app.ui.components.ComingSoonScreen
import timeshealth.app.ui.components.SheetDestination
import timeshealth.app.ui.home.YOGA_PLAN_ID
import timeshealth.app.ui.live.LiveClassRoute
import timeshealth.app.ui.run.RunTrackerRoute
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.gate.GateDestination
import timeshealth.app.ui.gate.GateRoute
import timeshealth.app.ui.login.LoginRoute
import timeshealth.app.ui.tabs.TabsScreen
import timeshealth.app.ui.theme.Spacing
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.ThLayout

/** savedStateHandle key on the Tabs entry: a tab a deep link asked for. */
internal const val REQUESTED_TAB = "requestedTab"

/**
 * The app's navigation graph: every screen of the RN app as a type-safe route
 * ([Route]), the Gate first.
 *
 * Also hosts the two app-wide behaviours:
 * - the signed-out guard: when the session becomes SignedOut anywhere but the
 *   Gate or Login, go to Login and clear the back stack ([shouldRedirectToLogin]);
 * - pending deep links (push / inbox routes), opened once the user is on the tabs.
 *
 * Screen agents: replace a destination's [ComingSoonScreen] with the real
 * screen's `XRoute(viewModel = hiltViewModel(), onBack = navController::back, …)`
 * and wire its callbacks to `navController.navigate(Route.Y(...))` here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val status by appViewModel.sessionStatus.collectAsStateWithLifecycle()
    val pendingLink by appViewModel.pendingDeepLink.collectAsStateWithLifecycle()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination

    // Signed-out guard.
    LaunchedEffect(status, destination) {
        if (destination == null) return@LaunchedEffect
        if (shouldRedirectToLogin(status, onPublicScreen = destination.isPublic())) {
            navController.navigate(Route.Login) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // A push / inbox route waiting for the signed-in shell.
    LaunchedEffect(pendingLink, status, destination) {
        if (pendingLink == null || status !is SessionStatus.SignedIn || !navController.hasTabs()) return@LaunchedEffect
        appViewModel.consumeDeepLink()?.let(navController::open)
    }

    NavHost(
        navController = navController,
        startDestination = Route.Gate,
        enterTransition = Transitions.pushEnter,
        exitTransition = Transitions.pushExit,
        popEnterTransition = Transitions.popEnter,
        popExitTransition = Transitions.popExit,
    ) {
        composable<Route.Gate>(enterTransition = Transitions.fadeEnter, exitTransition = Transitions.fadeExit) {
            GateRoute(
                viewModel = hiltViewModel(),
                onDecided = { decided ->
                    navController.navigate(decided.route()) {
                        popUpTo<Route.Gate> { inclusive = true }
                    }
                },
                onOpenPass = { eventId -> navController.navigate(Route.Bib(eventId)) },
            )
        }

        composable<Route.Login>(enterTransition = Transitions.fadeEnter, exitTransition = Transitions.fadeExit) {
            LoginRoute(
                viewModel = hiltViewModel(),
                // Back through the Gate: it decides onboarding vs tabs.
                onSignedIn = {
                    navController.navigate(Route.Gate) {
                        popUpTo<Route.Login> { inclusive = true }
                    }
                },
            )
        }

        composable<Route.Onboarding> {
            ComingSoonScreen(
                screenName = "Onboarding",
                detail = "The 4-step personalisation (PRD §5).",
                actionLabel = "Continue to Home",
                onAction = {
                    navController.navigate(Route.Tabs()) {
                        popUpTo<Route.Onboarding> { inclusive = true }
                    }
                },
            )
        }

        composable<Route.Tabs>(enterTransition = Transitions.fadeEnter, exitTransition = Transitions.pushExit) { backStackEntry ->
            val route = backStackEntry.toRoute<Route.Tabs>()
            val requested by backStackEntry.savedStateHandle
                .getStateFlow<AppTab?>(REQUESTED_TAB, null)
                .collectAsStateWithLifecycle()
            TabsScreen(
                initialTab = route.tab,
                requestedTab = requested,
                onTabRequestHandled = { backStackEntry.savedStateHandle[REQUESTED_TAB] = null },
                openRoute = { navController.navigate(it) },
            )
        }

        // Design: the paywall is a bottom SHEET over the current screen.
        dialog<Route.Paywall>(
            dialogProperties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<Route.Paywall>()
            SheetDestination(onDismiss = { navController.popBackStack() }) {
                PaywallPlaceholder(productId = route.productId)
            }
        }

        composable<Route.YogaExplorer> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.YogaExplorer>()
            ComingSoonScreen("Yoga explorer", detail = route.categoryId?.let { "Category $it" }, onBack = navController::back)
        }

        composable<Route.SessionDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.SessionDetail>()
            ComingSoonScreen("Session", detail = "Session ${route.id}", onBack = navController::back)
        }

        composable<Route.VideoPlayer>(
            enterTransition = Transitions.fadeEnter,
            popExitTransition = Transitions.fadeExit,
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<Route.VideoPlayer>()
            ComingSoonScreen("Video player", detail = "Session ${route.id}", onBack = navController::back)
        }

        composable<Route.LiveClass>(
            enterTransition = Transitions.fadeEnter,
            popExitTransition = Transitions.fadeExit,
        ) {
            LiveClassRoute(
                viewModel = hiltViewModel(),
                onBack = navController::back,
                onOpenPaywall = { navController.navigate(Route.Paywall(YOGA_PLAN_ID)) },
            )
        }

        // Instructor reels play in-app (§6.3 "plays inline"), full screen.
        composable<Route.Reel>(
            enterTransition = Transitions.fadeEnter,
            popExitTransition = Transitions.fadeExit,
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<Route.Reel>()
            ComingSoonScreen("Reel", detail = listOfNotNull(route.title, route.handle).joinToString(" · "), onBack = navController::back)
        }

        composable<Route.RaceDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.RaceDetail>()
            ComingSoonScreen("Race", detail = "Event ${route.eventId}", onBack = navController::back)
        }

        composable<Route.RaceResults> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.RaceResults>()
            ComingSoonScreen("Race results", detail = "Event ${route.eventId}", onBack = navController::back)
        }

        composable<Route.Bib>(
            enterTransition = Transitions.slideUpEnter,
            popExitTransition = Transitions.slideDownExit,
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<Route.Bib>()
            ComingSoonScreen("Digital bib", detail = "Event ${route.eventId}", onBack = navController::back)
        }

        composable<Route.RunTracker>(
            enterTransition = Transitions.slideUpEnter,
            popExitTransition = Transitions.slideDownExit,
        ) {
            RunTrackerRoute(viewModel = hiltViewModel(), onClose = navController::back)
        }
    }
}

/** Where the Gate's decision goes. */
private fun GateDestination.route(): Route = when (this) {
    GateDestination.LOGIN -> Route.Login
    GateDestination.ONBOARDING -> Route.Onboarding
    GateDestination.TABS -> Route.Tabs()
}

/** Gate and Login deal with being signed out themselves. */
private fun NavDestination.isPublic(): Boolean = hasRoute<Route.Gate>() || hasRoute<Route.Login>()

private fun NavHostController.hasTabs(): Boolean =
    runCatching { getBackStackEntry<Route.Tabs>() }.isSuccess

/**
 * Back from a full-screen route. Never leaves an empty back stack (a deep link
 * can open a screen with nothing under it): then it goes to the tabs.
 */
fun NavHostController.back() {
    if (previousBackStackEntry != null) {
        popBackStack()
    } else {
        navigate(Route.Tabs()) { popUpTo(graph.id) { inclusive = true } }
    }
}

/**
 * Opens an app route (push / inbox, after [appRouteToDestination]). A tab
 * route switches the existing tab shell instead of stacking a second one.
 */
fun NavHostController.open(route: Route) {
    if (route is Route.Tabs && hasTabs()) {
        popBackStack<Route.Tabs>(inclusive = false)
        getBackStackEntry<Route.Tabs>().savedStateHandle[REQUESTED_TAB] = route.tab
    } else {
        navigate(route)
    }
}

/** Placeholder content for the paywall sheet (PaywallSheetKt is the design to build). */
@Composable
private fun PaywallPlaceholder(productId: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ThLayout.Gutter)
            .padding(bottom = Spacing.X6l)
            .navigationBarsPadding(),
    ) {
        TagPill(text = "Coming soon", tone = TagTone.NEUTRAL)
        Text("Plans", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = Spacing.Md))
        Text(
            productId?.let { "Selected: $it" } ?: "Choose a plan.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = Spacing.Xs),
        )
    }
}
