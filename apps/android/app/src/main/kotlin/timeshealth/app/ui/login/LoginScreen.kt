package timeshealth.app.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timeshealth.app.ui.components.LightSystemBarIcons
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.LoginBackdrop
import timeshealth.app.ui.theme.LoginIndicatorIdle
import timeshealth.app.ui.theme.PREVIEW_LOGIN_ARGB
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.SplashIndicatorIdle
import timeshealth.app.ui.theme.SplashSlideGradients
import timeshealth.app.ui.theme.Spacing
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextOnDarkSoft
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThShapes
import timeshealth.app.ui.theme.TimesHealthTheme

/*
 * Pre-login + login: PRD §5 "Pre-login screen — auto-moving carousel, single
 * CTA → Login — Google / Email / Phone". Login is mandatory; no guest mode.
 *
 * Two screens from the design, one route (as in RN login.tsx):
 *   1. PreLoginSplash: OnboardingScreenKt.PreLoginSplashScreen. Full-bleed
 *      gradient per slide, wordmark, white "Get Started" card.
 *   2. AuthScreen: LoginScreenKt.LoginScreen. The #1C0722 backdrop, the slide's
 *      highlight above a canvas-coloured card (28dp top corners) holding the
 *      sign-in (AuthCard.kt).
 */

/** PRD §5's carousel moves on its own: ~4 s a slide (RN AUTO_ADVANCE_MS). */
private const val AUTO_ADVANCE_MS = 4_000L

/** A pre-login slide: PreLoginSplashScreen's Triple(title, body, gradient), verbatim. */
private data class SplashSlide(val title: String, val body: String, val gradient: List<Color>)

private val SPLASH_SLIDES = listOf(
    SplashSlide(
        "Eight live classes a day.",
        "Certified master instructors from The Yoga Institute, on your mat, morning and evening.",
        SplashSlideGradients[0],
    ),
    SplashSlide(
        "Run the city with us.",
        "Half marathons across Hyderabad, Bengaluru, Mumbai and Delhi NCR with chip timing.",
        SplashSlideGradients[1],
    ),
    SplashSlide(
        "A dietitian who knows your food.",
        "Personalized nutrition built on what you already cook at home. 2,500+ Indian meal combos.",
        SplashSlideGradients[2],
    ),
)

/** LoginScreenKt's highlights (its listOf(Triple(...))), verbatim; same topics, same order. */
private data class Highlight(val title: String, val body: String, val emoji: String)

private val HIGHLIGHTS = listOf(
    Highlight(
        "Eight live classes a day.",
        "Certified instructors from The Yoga Institute on your mat at your hour.",
        "🧘",
    ),
    Highlight("Run the city with us.", "Half marathons across Hyderabad, Delhi NCR, Bengaluru & Mumbai.", "🏃"),
    Highlight(
        "A dietitian who knows your food.",
        "Plans built around Indian food you already cook. First call is free.",
        "🥗",
    ),
)

/**
 * The Login destination.
 *
 * @param onSignedIn called when the session reports someone signed in; the
 *   caller goes back to the Gate, which decides onboarding vs tabs.
 */
@Composable
fun LoginRoute(viewModel: LoginViewModel, onSignedIn: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val onSignedInNow by rememberUpdatedState(onSignedIn)
    // Navigate when auth SETTLES, not straight after a sign-in call: the
    // identity provider reports the new user a moment later, and leaving first
    // would let the Gate bounce us back here.
    LaunchedEffect(signedIn) { if (signedIn) onSignedInNow() }

    LoginScreen(
        state = state,
        actions = AuthActions(
            onPhoneChange = viewModel::onPhoneChange,
            onOtpChange = viewModel::onOtpChange,
            onEmailChange = viewModel::onEmailChange,
            onPasswordChange = viewModel::onPasswordChange,
            onGoogle = viewModel::continueWithGoogle,
            onSendOtp = viewModel::sendOtp,
            onVerifyOtp = viewModel::verifyOtp,
            onShowEmail = viewModel::showEmail,
            onToggleCreateAccount = viewModel::toggleCreateAccount,
            onSubmitEmail = viewModel::submitEmail,
            onForgotPassword = viewModel::sendPasswordReset,
            onBackToMenu = viewModel::backToMenu,
            onPersona = viewModel::signInPersona,
        ),
    )
}

/**
 * Splash, then the sign-in card. Android back from the card returns to the
 * splash instead of leaving the app (the splash is the only way back to it).
 */
@Composable
fun LoginScreen(state: LoginUiState, actions: AuthActions, modifier: Modifier = Modifier) {
    // null = still on the pre-login splash; otherwise the slide to open on.
    var authSlide by rememberSaveable { mutableStateOf<Int?>(null) }
    BackHandler(enabled = authSlide != null) { authSlide = null }

    if (authSlide == null) {
        LightSystemBarIcons(statusBar = true, navigationBar = true)
        PreLoginSplash(continueLabel = state.continueLabel, onContinue = { authSlide = it }, modifier = modifier)
    } else {
        LightSystemBarIcons(statusBar = true, navigationBar = false)
        AuthScreen(initialSlide = authSlide ?: 0, state = state, actions = actions, modifier = modifier)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Pre-login splash
// ─────────────────────────────────────────────────────────────────────────────

/**
 * PreLoginSplashScreen: 24dp padding inside the system bars, wordmark at the
 * top, the slide copy in the middle, the "Get Started" card at the bottom.
 * The copy is a swipeable pager (RN) that also moves on its own every 4 s,
 * never while a finger is on it; the backdrop cross-fades between slides.
 */
@Composable
private fun PreLoginSplash(continueLabel: String, onContinue: (slide: Int) -> Unit, modifier: Modifier = Modifier) {
    val pager = rememberPagerState { SPLASH_SLIDES.size }
    val scope = rememberCoroutineScope()
    val dragged by pager.interactionSource.collectIsDraggedAsState()

    // A timeout per slide rather than an interval, so a swipe or an indicator
    // tap restarts the full 4 s instead of jumping early.
    LaunchedEffect(pager.settledPage, dragged) {
        if (dragged) return@LaunchedEffect
        delay(AUTO_ADVANCE_MS)
        pager.animateScrollToPage((pager.settledPage + 1) % SPLASH_SLIDES.size)
    }

    Box(modifier.fillMaxSize().background(SPLASH_SLIDES[0].gradient.last())) {
        SPLASH_SLIDES.forEachIndexed { i, slide ->
            val alpha by animateFloatAsState(
                targetValue = if (i == pager.currentPage) 1f else 0f,
                animationSpec = tween(durationMillis = 450),
                label = "splashBackdrop$i",
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = alpha }
                    .background(Brush.verticalGradient(slide.gradient)),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(vertical = Spacing.X6l),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.X6l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Times", color = PaperWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Health+", color = CoralBrand, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            }

            Column {
                // Full-bleed pages (the 24dp inset is inside each page), so a
                // swipe can start anywhere across the screen.
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    beyondViewportPageCount = SPLASH_SLIDES.size - 1,
                ) { page ->
                    val slide = SPLASH_SLIDES[page]
                    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.X6l)) {
                        Text(
                            slide.title,
                            color = PaperWhite,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = ThFonts.Serif,
                            lineHeight = 42.sp,
                        )
                        Spacer(Modifier.height(Spacing.Lg))
                        Text(slide.body, color = TextOnDarkSoft, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
                Spacer(Modifier.height(Spacing.X6l))
                SlideIndicators(
                    count = SPLASH_SLIDES.size,
                    active = pager.currentPage,
                    activeColor = PaperWhite,
                    idleColor = SplashIndicatorIdle,
                    onPick = { scope.launch { pager.animateScrollToPage(it) } },
                    modifier = Modifier.padding(horizontal = Spacing.X6l),
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.X6l),
                shape = ThShapes.Xl,
                colors = CardDefaults.cardColors(containerColor = PaperWhite),
            ) {
                Column(Modifier.padding(Spacing.X4l)) {
                    Text(
                        "Get Started",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = ThFonts.Serif,
                    )
                    Text(
                        "One login across Yoga, Marathon & Diet.",
                        modifier = Modifier.padding(bottom = Spacing.Xxl),
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                    Button(
                        onClick = { onContinue(pager.currentPage) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = ThShapes.Md,
                        colors = ButtonDefaults.buttonColors(containerColor = CoralBrand, contentColor = PaperWhite),
                    ) {
                        Text(continueLabel, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * The design's carousel bars: 4dp tall, 2dp corners, 6dp apart, the active
 * one 28dp wide and the rest 8dp. Tappable (Compose widens a small clickable's
 * touch target to 48dp on its own, without changing the layout).
 */
@Composable
private fun SlideIndicators(
    count: Int,
    active: Int,
    activeColor: Color,
    idleColor: Color,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .width(if (i == active) 28.dp else 8.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i == active) activeColor else idleColor)
                    .clickable(role = Role.Button) { onPick(i) }
                    .semantics {
                        contentDescription = "Show slide ${i + 1} of $count"
                        selected = i == active
                    },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. Sign-in
// ─────────────────────────────────────────────────────────────────────────────

/**
 * LoginScreenKt: the highlight (26dp padding, centred in whatever height the
 * card leaves) over the sign-in card. Scrolls as one, so with the keyboard up
 * the fields stay reachable; the highlight gives way first.
 *
 * Deliberate difference from the design: the card's canvas runs on under the
 * navigation bar (its bottom padding adds the inset) instead of the design's
 * whole-screen navigationBarsPadding, which left a strip of the dark backdrop
 * under the card on gesture-nav phones. The RN port did the same.
 */
@Composable
private fun AuthScreen(initialSlide: Int, state: LoginUiState, actions: AuthActions, modifier: Modifier = Modifier) {
    // No auto-advance here: the design steps it by hand ("Next Highlight →"),
    // and copy shifting while someone types a phone number is a distraction.
    var slide by rememberSaveable { mutableStateOf(initialSlide % HIGHLIGHTS.size) }
    val density = LocalDensity.current

    Box(modifier.fillMaxSize().background(LoginBackdrop)) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            val viewport = maxHeight
            var cardHeight by remember { mutableStateOf(0.dp) }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                HighlightBlock(
                    highlight = HIGHLIGHTS[slide],
                    index = slide,
                    onPick = { slide = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = (viewport - cardHeight).coerceAtLeast(0.dp))
                        .statusBarsPadding()
                        .padding(26.dp),
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { cardHeight = with(density) { it.height.toDp() } },
                    shape = ThShapes.SheetTop,
                    colors = CardDefaults.cardColors(containerColor = CanvasBg),
                ) {
                    AuthCard(
                        state = state,
                        actions = actions,
                        onNextHighlight = { slide = (slide + 1) % HIGHLIGHTS.size },
                        modifier = Modifier.padding(Spacing.X6l).navigationBarsPadding(),
                    )
                }
            }
        }
    }
}

/** The highlight: sage "ONE TH+ LOGIN" pill, emoji, serif title, body, bars. */
@Composable
private fun HighlightBlock(highlight: Highlight, index: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        TagPill(text = "ONE TH+ LOGIN", tone = TagTone.SAGE)
        Spacer(Modifier.height(Spacing.Xxl))
        Text(highlight.emoji, fontSize = 44.sp)
        Spacer(Modifier.height(Spacing.Md))
        Text(
            highlight.title,
            style = MaterialTheme.typography.displayLarge,
            color = PaperWhite,
            fontFamily = ThFonts.Serif,
            lineHeight = 42.sp,
        )
        Spacer(Modifier.height(Spacing.Md))
        Text(highlight.body, color = TextOnDarkSoft, fontSize = 13.5.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(Spacing.X5l))
        SlideIndicators(
            count = HIGHLIGHTS.size,
            active = index,
            activeColor = CoralBrand,
            idleColor = LoginIndicatorIdle,
            onPick = onPick,
        )
    }
}

@Preview(showBackground = true, backgroundColor = PREVIEW_LOGIN_ARGB, heightDp = 800)
@Composable
private fun SplashPreview() {
    TimesHealthTheme { PreLoginSplash(continueLabel = "Continue with Google / Mobile", onContinue = {}) }
}

@Preview(showBackground = true, backgroundColor = PREVIEW_LOGIN_ARGB, heightDp = 1100)
@Composable
private fun AuthScreenPreview() {
    TimesHealthTheme {
        AuthScreen(
            initialSlide = 0,
            state = LoginUiState(personas = DEV_PERSONAS, phone = "98765", error = SignInCopy.PHONE_UNAVAILABLE),
            actions = AuthActions.None,
        )
    }
}
