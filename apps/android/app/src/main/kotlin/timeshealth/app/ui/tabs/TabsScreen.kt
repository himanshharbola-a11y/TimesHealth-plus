package timeshealth.app.ui.tabs

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import timeshealth.app.ui.marathon.MarathonRoute
import timeshealth.app.ui.navigation.AppTab
import timeshealth.app.ui.navigation.Route
import timeshealth.app.ui.navigation.TabRoute
import timeshealth.app.ui.navigation.route
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.RnType
import timeshealth.app.ui.theme.Spacing
import timeshealth.app.ui.theme.ThLayout
import timeshealth.app.ui.theme.ThShapes

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

    // Placeholders until the Profile drawer and the notification inbox are built.
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
            composable<TabRoute.Home> {
                HomeRoute(
                    viewModel = hiltViewModel(),
                    onTarget = { target ->
                        when (target) {
                            is FeedTarget.Open -> openRoute(target.route)
                            is FeedTarget.Tab -> tabsNav.selectTab(target.tab)
                            // Only http(s) (and dev schemes in debug); a bad link is ignored, never a crash.
                            is FeedTarget.External ->
                                if (isSafeExternalUrl(target.url, debug = BuildConfig.DEBUG)) runCatching { uriHandler.openUri(target.url) }
                            FeedTarget.None -> Unit
                        }
                    },
                )
            }
            composable<TabRoute.Yoga> { ComingSoonScreen("Yoga") }
            composable<TabRoute.Marathon> { MarathonRoute(viewModel = hiltViewModel(), openRoute = openRoute) }
            composable<TabRoute.Diet> { ComingSoonScreen("Diet") }
        }
    }

    openSheet?.let { sheet ->
        HeaderPlaceholderSheet(
            sheet = sheet,
            name = header.userName,
            onSignOut = {
                openSheet = null
                viewModel.signOut()
            },
            onDismiss = { openSheet = null },
        )
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

/**
 * TEMPORARY stand-ins for the Profile drawer and the notification inbox
 * (screen agents replace them). The profile one carries Sign out, so QA can
 * switch personas and the signed-out guard can be seen working.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeaderPlaceholderSheet(sheet: HeaderSheet, name: String?, onSignOut: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PaperWhite,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ThLayout.Gutter)
                .padding(bottom = Spacing.X6l)
                .navigationBarsPadding(),
        ) {
            Text(
                if (sheet == HeaderSheet.PROFILE) name ?: "Your profile" else "Notifications",
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                if (sheet == HeaderSheet.PROFILE) "Your profile and products are coming soon." else "Your inbox is coming soon.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spacing.Xs, bottom = Spacing.X4l),
            )
            if (sheet == HeaderSheet.PROFILE) {
                OutlinedButton(onClick = onSignOut, shape = ThShapes.Md, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out", style = RnType.titleSmall)
                }
            }
        }
    }
}
