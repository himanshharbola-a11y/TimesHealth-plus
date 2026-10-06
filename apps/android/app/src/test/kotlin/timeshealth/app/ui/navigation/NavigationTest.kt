package timeshealth.app.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import timeshealth.app.core.data.session.SessionStatus
import timeshealth.app.core.data.session.SignInMethod

class NavigationTest {

    // ── Deep links (push / inbox `data.route`) ──────────────────────────────

    @Test
    fun `every app route maps to its destination`() {
        assertThat(appRouteToDestination("/(tabs)")).isEqualTo(Route.Tabs(AppTab.HOME))
        assertThat(appRouteToDestination("/(tabs)/yoga")).isEqualTo(Route.Tabs(AppTab.YOGA))
        assertThat(appRouteToDestination("/(tabs)/marathon")).isEqualTo(Route.Tabs(AppTab.MARATHON))
        assertThat(appRouteToDestination("/(tabs)/diet")).isEqualTo(Route.Tabs(AppTab.DIET))
        assertThat(appRouteToDestination("/race/evt_hyd-2026")).isEqualTo(Route.RaceDetail("evt_hyd-2026"))
        assertThat(appRouteToDestination("/race/evt_hyd/results")).isEqualTo(Route.RaceResults("evt_hyd"))
        assertThat(appRouteToDestination("/bib/evt_hyd")).isEqualTo(Route.Bib("evt_hyd"))
        assertThat(appRouteToDestination("/session/ses_42")).isEqualTo(Route.SessionDetail("ses_42"))
        assertThat(appRouteToDestination("/paywall")).isEqualTo(Route.Paywall())
        assertThat(appRouteToDestination("/run-tracker")).isEqualTo(Route.RunTracker)
        assertThat(appRouteToDestination("/yoga-explorer")).isEqualTo(Route.YogaExplorer())
    }

    @Test
    fun `anything that isn't exactly an app route is refused`() {
        listOf(
            null,
            "",
            "/",
            "/(tabs)/profile",
            "/race/",
            "/race/a/b",
            "/race/" + "x".repeat(65),
            "/bib/evt hyd",
            "/paywall\n",
            "https://evil.example/paywall",
            "intent://bib/1#Intent;end",
            "/login",
            "/video/ses_1", // not pushable in RN either
        ).forEach { assertThat(appRouteToDestination(it)).isNull() }
    }

    @Test
    fun `a pending deep link is queued once and taken once`() {
        val links = PendingDeepLink()
        assertThat(links.offer("/not-a-route")).isFalse()
        assertThat(links.pending.value).isNull()

        assertThat(links.offer("/race/evt_1")).isTrue()
        assertThat(links.pending.value).isEqualTo(Route.RaceDetail("evt_1"))
        assertThat(links.consume()).isEqualTo(Route.RaceDetail("evt_1"))
        assertThat(links.consume()).isNull()
    }

    // ── Signed-out guard ────────────────────────────────────────────────────

    @Test
    fun `signing out anywhere but the gate and login goes to login`() {
        assertThat(shouldRedirectToLogin(SessionStatus.SignedOut, onPublicScreen = false)).isTrue()
        assertThat(shouldRedirectToLogin(SessionStatus.SignedOut, onPublicScreen = true)).isFalse()
        assertThat(shouldRedirectToLogin(SessionStatus.Loading, onPublicScreen = false)).isFalse()
        assertThat(shouldRedirectToLogin(SessionStatus.SignedIn(SignInMethod.PERSONA), onPublicScreen = false)).isFalse()
    }

    @Test
    fun `each tab has its own destination`() {
        assertThat(AppTab.entries.map { it.route() })
            .containsExactly(TabRoute.Home, TabRoute.Yoga, TabRoute.Marathon, TabRoute.Diet).inOrder()
    }
}
