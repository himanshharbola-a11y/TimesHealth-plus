package timeshealth.app

/**
 * Build-time facts the UI branches on, as one injectable value (from
 * [BuildConfig] in wiring/UiWiring.kt) so ViewModels never read BuildConfig and
 * tests can pass any combination.
 *
 * @property versionName compared against the server's minSupportedAppVersion.
 * @property applicationId the Play Store listing to send "Update now" to.
 * @property devSignIn the DEV_SIGNIN build flag (test APKs).
 * @property firebaseEnabled google-services.json was present at build time.
 */
data class AppBuildInfo(
    val versionName: String,
    val applicationId: String,
    val debug: Boolean,
    val devSignIn: Boolean,
    val firebaseEnabled: Boolean,
) {
    /**
     * QA personas on the login screen: debug builds and DEV_SIGNIN test APKs
     * only (RN `devSignInEnabled = __DEV__ || EXPO_PUBLIC_DEV_SIGNIN === '1'`).
     * A server must also run with ALLOW_DEV_TOKENS=true to accept them.
     */
    val personasEnabled: Boolean get() = debug || devSignIn
}
