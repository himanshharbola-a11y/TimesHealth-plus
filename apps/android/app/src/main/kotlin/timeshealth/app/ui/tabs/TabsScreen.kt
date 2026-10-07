package timeshealth.app.ui.tabs

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import timeshealth.app.BuildConfig
import timeshealth.app.core.domain.isSafeExternalUrl
import timeshealth.app.ui.components.BottomNavBar
import timeshealth.app.ui.components.ComingSoonScreen
import timeshealth.app.ui.components.TopHeader
import timeshealth.app.ui.home.FeedTarget
import timeshealth.app.ui.home.HomeRoute
import timeshealth.app.ui.home.YOGA_PLAN_ID
import timeshealth.app.ui.inbox.InboxSheet
import timeshealth.app.ui.navigation.appRouteToDestination
import timeshealth.app.ui.profile.ProfileDestination
import timeshealth.app.ui.profile.ProfileSheet
import timeshealth.app.ui.marathon.MarathonRoute
import timeshealth.app.ui.navigation.AppTab
import timeshealth.app.ui.navigation.Route
import timeshealth.app.ui.navigation.TabRoute
import timeshealth.app.ui.navigation.route
import timeshealth.app.ui.yoga.YogaRoute

/**
 * The signed-in shell: the design's MainActivityKt Scaffold. TopHeader in the
 * top bar and BottomNavBar in the bottom bar, the same for all four tabs
 * (PRD §4: fixed tabs, identical for every user; Profile opens from the
 * avatar, not a tab).
 *
 * Each tab is its own destination in a nested NavHost, so each keeps its
 * scroll position and ViewModel across switches (saveState / restoreState).
 * Back on any tab goes to Home first, then leaves the app (RN Tabs
 * `backBehavior: firstRoute`).
 *
 * @param initialTab the tab to open on (Route.Tabs.tab).
 * @param requestedTab a tab a deep link asked for while the shell was already
 *   open; [onTabRequestHandled] clears it.
 * @param openRoute opens a top-level destination over the tabs. Tab screens
 *   take specific callbacks (`onOpenRace: (String) -> Unit`); wire them here as
 *   `{ openRoute(Route.RaceDetail(it)) }`.
 */
@Composable
fun TabsScreen(
    initialTab: AppTab,
    requestedTab: AppTab?,
    onTabRequestHandled: () -> Unit,
    openRoute: (Route) -> Unit,
    viewModel: TabsViewModel = hiltViewModel(),
) {
    val header by viewModel.header.collectAsStateWithLifecycle()
    val tabsNav = rememberNavController()
    val entry by tabsNav.currentBackStackEntryAsState()
    val selected = AppTab.entries.firstOrNull { tab ->
        entry?.destination?.hierarchy?.any { it.hasRoute(tab.route()::class) } == true
    } ?: AppTab.HOME

    // The initial tab is applied once (not again after rotation / process death).
    var initialApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!initialApplied) {
            if (initialTab != AppTab.HOME) tabsNav.selectTab(initialTab)
            initialApplied = true
        }
    }
    val handled by rememberUpdatedState(onTabRequestHandled)
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tabsNav.selectTab(requestedTab)
            handled()
        }
    }

    val uriHandler = LocalUriHandler.current
    // What a tap on a feed card does, the same from every tab.
    val onTarget: (FeedTarget) -> Unit = { target ->
        when (target) {
            is FeedTarget.Open -> openRoute(target.route)
            is FeedTarget.Tab -> tabsNav.selectTab(target.tab)
            // Only http(s) (and dev schemes in debug); a bad link is ignored, never a crash.
            is FeedTarget.External ->
                if (isSafeExternalUrl(target.url, debug = BuildConfig.DEBUG)) runCatching { uriHandler.openUri(target.url) }
            FeedTarget.None -> Unit
        }
    }

    var openSheet by rememberSaveable { mutableStateOf<HeaderSheet?>(null) }

    Scaffold(
        topBar = {
            TopHeader(
                userName = header.userName,
                hasUnread = header.hasUnread,
                onProfileClick = { openSheet = HeaderSheet.PROFILE },
                onNotificationsClick = { openSheet = HeaderSheet.INBOX },
            )
        },
        bottomBar = { BottomNavBar(selectedTab = selected, onTabSelected = { tabsNav.selectTab(it) }) },
    ) { padding ->
        NavHost(
            navController = tabsNav,
            startDestination = TabRoute.Home,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            // RN Tabs: no transition between tabs.
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable<TabRoute.Home> { HomeRoute(viewModel = hiltViewModel(), onTarget = onTarget) }
            composable<TabRoute.Yoga> { YogaRoute(viewModel = hiltViewModel(), openRoute = openRoute, onTarget = onTarget) }
            composable<TabRoute.Marathon> { MarathonRoute(viewModel = hiltViewModel(), openRoute = openRoute) }
            composable<TabRoute.Diet> { ComingSoonScreen("Diet") }
        }
    }

    when (openSheet) {
        HeaderSheet.PROFILE -> ProfileSheet(
            viewModel = hiltViewModel(),
            onNavigate = { destination ->
                when (destination) {
                    ProfileDestination.Paywall -> openRoute(Route.Paywall(YOGA_PLAN_ID))
                    ProfileDestination.MarathonTab -> tabsNav.selectTab(AppTab.MARATHON)
                    is ProfileDestination.Race -> openRoute(Route.RaceDetail(destination.eventId))
                    is ProfileDestination.RaceParticipant -> openRoute(Route.RaceDetail(destination.eventId, edit = "participant"))
                    is ProfileDestination.RaceResults -> openRoute(Route.RaceResults(destination.eventId))
                }
            },
            onDismiss = { openSheet = null },
        )
        HeaderSheet.INBOX -> InboxSheet(
            viewModel = hiltViewModel(),
            onOpenRoute = { route ->
                openSheet = null
                when (val destination = appRouteToDestination(route)) {
                    null -> Unit
                    is Route.Tabs -> tabsNav.selectTab(destination.tab)
                    else -> openRoute(destination)
                }
            },
            onDismiss = { openSheet = null },
        )
        null -> Unit
    }
}

/** The standard bottom-navigation move: one entry per tab, each tab's state saved and restored. */
private fun NavHostController.selectTab(tab: AppTab) {
    navigate(tab.route()) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private enum class HeaderSheet { PROFILE, INBOX }
