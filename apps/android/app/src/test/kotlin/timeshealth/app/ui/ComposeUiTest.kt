package timeshealth.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import timeshealth.app.ui.components.BottomNavBar
import timeshealth.app.ui.components.ErrorState
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.components.TopHeader
import timeshealth.app.ui.gate.GatePhase
import timeshealth.app.ui.gate.GateScreen
import timeshealth.app.ui.gate.GateUiState
import timeshealth.app.ui.gate.PassShortcut
import timeshealth.app.ui.login.AuthActions
import timeshealth.app.ui.login.AuthCard
import timeshealth.app.ui.login.DEV_PERSONAS
import timeshealth.app.ui.login.DevPersona
import timeshealth.app.ui.login.LoginUiState
import timeshealth.app.ui.navigation.AppTab
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TimesHealthTheme

/**
 * Compose UI on the JVM (Robolectric). SDK 34: Robolectric's SDK 35+ runtimes
 * need JDK 21 and the build runs on 17. A plain Application, so the tests
 * don't start the app's Hilt graph.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ComposeUiTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun tagPill_upperCasesItsLabel() {
        compose.setContent { TimesHealthTheme { TagPill(text = "Live now", tone = TagTone.LIVE) } }
        compose.onNodeWithText("LIVE NOW").assertIsDisplayed()
    }

    @Test
    fun bottomNav_marksTheSelectedTab_andReportsTaps() {
        var tapped: AppTab? = null
        compose.setContent {
            TimesHealthTheme { BottomNavBar(selectedTab = AppTab.HOME, onTabSelected = { tapped = it }) }
        }
        compose.onNodeWithText("Home").assertIsSelected()
        compose.onNodeWithText("Marathon").assertIsNotSelected().performClick()
        assertThat(tapped).isEqualTo(AppTab.MARATHON)
    }

    @Test
    fun topHeader_showsTheInitial_andTheUnreadState() {
        var profile = 0
        var inbox = 0
        compose.setContent {
            TimesHealthTheme {
                TopHeader(userName = "priya", hasUnread = true, onProfileClick = { profile++ }, onNotificationsClick = { inbox++ })
            }
        }
        compose.onNodeWithText("P").assertIsDisplayed()
        compose.onNodeWithContentDescription("Notifications, new").performClick()
        compose.onNodeWithContentDescription("Open profile").performClick()
        assertThat(inbox).isEqualTo(1)
        assertThat(profile).isEqualTo(1)
    }

    @Test
    fun errorState_offersRetryAndBack() {
        var retries = 0
        var backs = 0
        compose.setContent {
            TimesHealthTheme {
                ErrorState(message = "No connection. Check your network and try again.", onRetry = { retries++ }, onBack = { backs++ })
            }
        }
        compose.onNodeWithText("No connection. Check your network and try again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Go back").performClick()
        assertThat(retries).isEqualTo(1)
        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun gate_unreachable_offersTheSavedRacePass() {
        var opened: String? = null
        compose.setContent {
            TimesHealthTheme {
                GateScreen(
                    state = GateUiState(
                        phase = GatePhase.Unreachable(),
                        passes = listOf(PassShortcut("evt_hyd", "Hyderabad Half Marathon")),
                    ),
                    onRetry = {},
                    onUpdate = {},
                    onOpenPass = { opened = it },
                )
            }
        }
        compose.onNodeWithText("We couldn’t reach TimesHealth+").assertIsDisplayed()
        compose.onNodeWithText("Open race pass · Hyderabad Half Marathon").performClick()
        assertThat(opened).isEqualTo("evt_hyd")
    }

    @Test
    fun login_listsTheQaPersonas_andSignsInWithTheTappedOne() {
        var picked: DevPersona? = null
        compose.setContent {
            TimesHealthTheme {
                // The card scrolls with the login screen; give it the same here.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AuthCard(
                        state = LoginUiState(personas = DEV_PERSONAS),
                        actions = AuthActions.None.copy(onPersona = { picked = it }),
                        onNextHighlight = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Log In or Sign Up").assertIsDisplayed()
        compose.onNodeWithText("TEST PERSONAS · NOT IN RELEASE BUILDS").assertExists()
        compose.onNodeWithText("Race finisher").performScrollTo().performClick()
        assertThat(picked?.token).isEqualTo("qa_finisher|finisher@th.test|+919000000006")
    }

    @Test
    fun login_hidesThePersonas_inAReleaseBuild() {
        compose.setContent {
            TimesHealthTheme {
                AuthCard(state = LoginUiState(personas = emptyList()), actions = AuthActions.None, onNextHighlight = {})
            }
        }
        compose.onNodeWithText("Send OTP & Log In").assertExists()
        compose.onNodeWithText("Free user").assertDoesNotExist()
    }
}
