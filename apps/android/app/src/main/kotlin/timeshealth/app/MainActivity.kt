package timeshealth.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import timeshealth.app.ui.navigation.AppNavHost
import timeshealth.app.ui.navigation.PendingDeepLink
import timeshealth.app.ui.theme.TimesHealthTheme

/**
 * The single activity. Everything is Compose (AppNavHost); classic Views come
 * in through `AndroidView` where a View is the better tool (Media3 PlayerView,
 * WebView).
 *
 * Draws edge to edge, as the design's MainActivity (`enableEdgeToEdge()`):
 * each screen pads for the system bars itself (TopHeader for the status bar,
 * BottomNavBar for the navigation bar).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var deepLinks: PendingDeepLink

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 12+ system splash (canvas + brand mark), handed straight to
        // the Gate's own splash: it is not held, so the Gate can offer the saved
        // race passes on a slow launch.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) offerRoute(intent)
        setContent {
            TimesHealthTheme {
                AppNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        offerRoute(intent)
    }

    /** A notification tap carries its in-app route as the `route` extra (RN `data.route`). */
    private fun offerRoute(intent: Intent?) {
        deepLinks.offer(intent?.getStringExtra(PendingDeepLink.EXTRA_ROUTE))
    }
}
