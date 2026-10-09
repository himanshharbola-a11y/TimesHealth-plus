package timeshealth.app.ui.home

import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.workshop.WorkshopSheet
import timeshealth.app.core.model.LiveWorkshop
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import timeshealth.app.core.model.ArticleRailComponent
import timeshealth.app.core.model.EntryTileComponent
import timeshealth.app.core.model.FeedAction
import timeshealth.app.core.model.HeroStackComponent
import timeshealth.app.core.model.KnownFeedComponent
import timeshealth.app.core.model.LiveClassRailComponent
import timeshealth.app.core.model.PromoStripComponent
import timeshealth.app.core.model.QuoteRailComponent
import timeshealth.app.core.model.ReelRailComponent
import timeshealth.app.core.model.VideoRailComponent
import timeshealth.app.core.model.WorkshopRailComponent
import timeshealth.app.ui.components.SectionHeader
import timeshealth.app.ui.components.UiStateContent
import timeshealth.app.ui.feed.ArticleCard
import timeshealth.app.ui.feed.EntryTile
import timeshealth.app.ui.feed.HeroCard
import timeshealth.app.ui.feed.HeroSecondaryCard
import timeshealth.app.ui.feed.LiveClassCardView
import timeshealth.app.ui.feed.LocalServerNow
import timeshealth.app.ui.feed.PromoStrip
import timeshealth.app.ui.feed.QuoteCard
import timeshealth.app.ui.feed.ReelCard
import timeshealth.app.ui.feed.VideoCard
import timeshealth.app.ui.feed.WorkshopCard
import timeshealth.app.ui.navigation.AppTab
import timeshealth.app.ui.navigation.Route
import timeshealth.app.ui.state.UiState
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.Spacing
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThLayout

@Composable
fun HomeRoute(viewModel: HomeViewModel, onTarget: (FeedTarget) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var workshop by remember { mutableStateOf<LiveWorkshop?>(null) }
    CompositionLocalProvider(LocalServerNow provides viewModel::nowMs) {
        HomeScreen(state = state, onRetry = viewModel::retry, onRefresh = viewModel::refresh, onTarget = onTarget, modifier = modifier, onWorkshop = { workshop = it })
        workshop?.let {
            WorkshopSheet(it, hiltViewModel(), onDismiss = {
                workshop = null
                // Seats changed: the rail's counts and "Registered" refresh.
                viewModel.refresh()
            })
        }
    }
}

/**
 * Home: the greeting, then the server's components in the admin's order
 * (GET /home). Types this build doesn't know were already dropped, so a new
 * rail from a newer server never breaks an old app.
 *
 * Spacing is the design's LazyColumn rhythm (HomeScreenKt): every block owns
 * its padding, nothing between blocks: hero slots 18×6, section headers 18×12,
 * rails 18 at the sides with 12 between cards, promo / tiles 18×14.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState<HomeUi>,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onTarget: (FeedTarget) -> Unit,
    modifier: Modifier = Modifier,
    onWorkshop: (LiveWorkshop) -> Unit = {},
) {
    UiStateContent(state = state, onRetry = onRetry, modifier = modifier) { ui ->
        val refreshing = (state as? UiState.Ready)?.refreshing == true
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize().background(CanvasBg)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "greeting") { Greeting(ui.greeting) }
                item(key = "primer") { NotificationPrimer() }
                for (component in ui.components) {
                    item(key = component.id, contentType = component.type) {
                        FeedComponentView(component, ui, onTarget, onWorkshop)
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedComponentView(component: KnownFeedComponent, ui: HomeUi, onTarget: (FeedTarget) -> Unit, onWorkshop: (LiveWorkshop) -> Unit) {
    when (component) {
        is HeroStackComponent -> {
            val slots = component.knownSlots
            Column {
                slots.getOrNull(0)?.let { first ->
                    HeroCard(first, onClick = { onTarget(targetFor(first)) }, modifier = Modifier.padding(horizontal = ThLayout.Gutter, vertical = 6.dp))
                }
                slots.getOrNull(1)?.let { second ->
                    HeroSecondaryCard(second, onClick = { onTarget(targetFor(second)) }, modifier = Modifier.padding(horizontal = ThLayout.Gutter, vertical = 6.dp))
                }
            }
        }

        is VideoRailComponent -> Rail(
            title = component.title,
            // "See all" opens the rail's category; "Explore" (no category) the whole catalogue.
            actionText = component.actionLabel,
            onAction = { onTarget(targetFor(FeedAction.OpenYogaExplorer(component.seeAllCategoryId))) },
        ) {
            items(component.items, key = { it.id }) { session ->
                val locked = !session.isFree && !ui.entitledToYoga
                VideoCard(session, locked, onClick = { onTarget(targetFor(session, ui.entitledToYoga)) })
            }
        }

        is LiveClassRailComponent -> Rail(
            title = component.title,
            actionText = component.actionLabel,
            onAction = { onTarget(FeedTarget.Tab(AppTab.YOGA)) },
        ) {
            items(component.items, key = { it.id }) { card -> LiveClassCardView(card, onClick = { onTarget(targetFor(card)) }) }
        }

        is ReelRailComponent -> Rail(component.title) {
            // §6.3 "plays inline": reels open the in-app player, not a browser.
            items(component.items, key = { it.id }) { reel ->
                ReelCard(reel, onClick = { onTarget(FeedTarget.Open(Route.Reel(reel.playbackUrl, reel.instructorName, reel.instructorHandle))) })
            }
        }

        is ArticleRailComponent -> Rail(component.title) {
            items(component.items, key = { it.id }) { article ->
                ArticleCard(article, onClick = { onTarget(targetFor(FeedAction.OpenArticle(article.url))) })
            }
        }

        is QuoteRailComponent -> Rail(component.title) {
            items(component.items, key = { it.id }) { quote -> QuoteCard(quote) }
        }

        is WorkshopRailComponent -> Rail(component.title, modifier = Modifier.padding(top = 10.dp)) {
            items(component.items, key = { it.id }) { w -> WorkshopCard(w, onClick = { onWorkshop(w) }) }
        }

        is EntryTileComponent -> EntryTile(
            component.title, component.subtitle, component.imageUrl,
            onClick = { onTarget(targetFor(component.action)) },
            modifier = Modifier.padding(horizontal = ThLayout.Gutter, vertical = 14.dp),
        )

        is PromoStripComponent -> PromoStrip(
            component.title, component.subtitle, component.ctaLabel, component.backgroundColor, component.imageUrl,
            gold = ui.holdsBoth,
            onClick = { onTarget(targetFor(component.action)) },
            modifier = Modifier.padding(horizontal = ThLayout.Gutter, vertical = 14.dp),
        )
    }
}

/** A section header over a horizontal rail with the design's 18dp sides and 12dp gaps. */
@Composable
private fun Rail(
    title: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(modifier) {
        SectionHeader(title = title, actionText = actionText, onAction = onAction)
        LazyRow(
            contentPadding = PaddingValues(horizontal = ThLayout.Gutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * HomeScreenKt's greeting item: 18×8 padding, the muted 12sp Medium day line
 * over the serif 24sp SemiBold "Hello, {firstName} 👋".
 */
@Composable
fun Greeting(greeting: HomeGreeting, modifier: Modifier = Modifier) {
    // Refreshed: the time of day as a small coral kicker, the name large and bold.
    Column(modifier.padding(horizontal = ThLayout.Gutter).padding(top = Spacing.Lg, bottom = Spacing.Md)) {
        Text(
            greeting.dayLine.uppercase(), color = CoralBrand, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Hi ${greeting.firstName} 👋", color = TextPrimary, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
        )
        Text("Here’s what’s good for you today.", color = TextSecondary, fontSize = 13.5.sp, modifier = Modifier.padding(top = 2.dp))
    }
}
