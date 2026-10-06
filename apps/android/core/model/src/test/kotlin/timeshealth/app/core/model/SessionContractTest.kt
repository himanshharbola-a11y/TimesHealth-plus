package timeshealth.app.core.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** GET /session and GET /config: the entitlement spine (PRD §2, §3). */
class SessionContractTest {

    private fun session(persona: String) = Fixtures.decode<SessionResponse>("session.$persona.json")

    private data class Expected(
        val persona: UserPersona,
        val hasYoga: Boolean,
        val hasMarathon: Boolean,
        val yogaStatus: YogaStatus?,
        val marathonStatuses: List<RaceLifecycleStatus>,
        val concern: Concern?,
    )

    @Test
    fun `persona and entitlements resolve per QA account`() {
        val expected = mapOf(
            "free" to Expected(UserPersona.FREE, false, false, null, emptyList(), null),
            "yoga" to Expected(UserPersona.YOGA_SUBSCRIBER, true, false, YogaStatus.ACTIVE, emptyList(), Concern.LOWER_BACK),
            "marathon" to Expected(
                UserPersona.MARATHON_REGISTRANT, false, true, null, listOf(RaceLifecycleStatus.UPCOMING), Concern.KNEES_JOINTS,
            ),
            "both" to Expected(
                UserPersona.BOTH, true, true, YogaStatus.ACTIVE, listOf(RaceLifecycleStatus.UPCOMING), Concern.SLEEP_ENERGY,
            ),
            // An expired member is not "hasYoga", but the entitlement row (and its streak) survives.
            "expired" to Expected(UserPersona.YOGA_EXPIRED, false, false, YogaStatus.EXPIRED, emptyList(), Concern.NECK_SHOULDERS),
            "finisher" to Expected(
                UserPersona.MARATHON_REGISTRANT, false, true, null, listOf(RaceLifecycleStatus.COMPLETED), null,
            ),
        )
        for ((persona, want) in expected) {
            val s = session(persona)
            assertWithMessage("$persona persona").that(s.persona.persona).isEqualTo(want.persona)
            assertWithMessage("$persona hasYoga").that(s.persona.hasYoga).isEqualTo(want.hasYoga)
            assertWithMessage("$persona hasMarathon").that(s.persona.hasMarathon).isEqualTo(want.hasMarathon)
            assertWithMessage("$persona yoga").that(s.entitlements.yoga?.status).isEqualTo(want.yogaStatus)
            assertWithMessage("$persona marathon").that(s.entitlements.marathon.map { it.status })
                .containsExactlyElementsIn(want.marathonStatuses).inOrder()
            // PRD §9: diet is always null in V1.
            assertWithMessage("$persona diet").that(s.entitlements.diet).isNull()
            assertWithMessage("$persona concern").that(s.profile.concern).isEqualTo(want.concern)
            assertWithMessage("$persona onboarding").that(s.needsOnboarding).isFalse()
            assertWithMessage("$persona maintenance").that(s.maintenance).isEqualTo(Maintenance(active = false, message = null))
        }
    }

    @Test
    fun `marathon entitlement carries tier, category and bib`() {
        val classic = session("marathon").entitlements.marathon.single()
        assertThat(classic.tier).isEqualTo(RaceTier.CLASSIC)
        assertThat(classic.category).isEqualTo("21K")
        assertThat(classic.bibNumber).isEqualTo("DEL-8892A")
        assertThat(classic.eventName).isEqualTo("Delhi Half Marathon")

        val premium = session("both").entitlements.marathon.single()
        assertThat(premium.tier).isEqualTo(RaceTier.PREMIUM)
        assertThat(premium.category).isEqualTo("10K")
    }

    @Test
    fun `yoga entitlement keeps the reminder slot, even once expired`() {
        val active = session("yoga").entitlements.yoga!!
        assertThat(active.active).isTrue()
        assertThat(active.planId).isEqualTo("yoga_annual")
        assertThat(active.reminderSlotId).isEqualTo("b2")

        val expired = session("expired").entitlements.yoga!!
        assertThat(expired.active).isFalse()
        assertThat(expired.reminderSlotId).isEqualTo("b2")
    }

    @Test
    fun `profile decodes nullable fields and login flags`() {
        val profile = session("free").profile

        assertThat(profile.name).isEqualTo("Freya Free")
        assertThat(profile.emailIsLogin).isTrue()
        assertThat(profile.dob).isNull()
        assertThat(profile.gender).isNull()
        assertThat(profile.healthGoal).isEqualTo(HealthGoal.CONSISTENCY)
        assertThat(profile.units).isEqualTo(Units.METRIC)
        assertThat(profile.profileCompletion).isEqualTo(67)
    }

    @Test
    fun `app config gates version and maintenance before sign-in`() {
        val config = Fixtures.decode<AppConfigResponse>("config.json")

        assertThat(config.minSupportedAppVersion).isEqualTo("1.0.0")
        assertThat(config.maintenance.active).isFalse()
        assertThat(config.maintenance.message).isNull()
        assertThat(config.serverTime).isNotEmpty()
    }
}
