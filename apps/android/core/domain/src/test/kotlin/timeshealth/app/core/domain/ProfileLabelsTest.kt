package timeshealth.app.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** apps/mobile/src/lib/profileLabels.ts (+ ProfileDrawer GENDERS). */
class ProfileLabelsTest {

    @Test
    fun `goal labels repeat the onboarding wording`() {
        assertThat(goalLabel("WEIGHT_LOSS")).isEqualTo("Lose weight")
        assertThat(goalLabel("STRENGTH_FLEXIBILITY")).isEqualTo("Build strength & flexibility")
        assertThat(goalLabel("STRESS_ANXIETY")).isEqualTo("Reduce stress & anxiety")
        assertThat(goalLabel("MARATHON_TRAINING")).isEqualTo("Train for a marathon race")
        assertThat(goalLabel("CONSISTENCY")).isEqualTo("Just stay consistent daily")
        assertThat(GOALS.map { it.emoji }).containsExactly("🔥", "🧘", "🍃", "🏃", "📅").inOrder()
    }

    @Test
    fun `concern labels repeat the onboarding wording`() {
        assertThat(concernLabel("LOWER_BACK")).isEqualTo("Lower back")
        assertThat(concernLabel("KNEES_JOINTS")).isEqualTo("Knees & joints")
        assertThat(concernLabel("NECK_SHOULDERS")).isEqualTo("Neck & shoulders")
        assertThat(concernLabel("HIPS_PELVIS")).isEqualTo("Hips & pelvis")
        assertThat(concernLabel("SLEEP_ENERGY")).isEqualTo("Sleep & energy")
        assertThat(concernLabel("NONE")).isEqualTo("Nothing specific")
    }

    @Test
    fun `gender labels`() {
        assertThat(GENDERS.map { genderLabel(it.value) })
            .containsExactly("Female", "Male", "Non-binary", "Prefer not to say").inOrder()
    }

    @Test
    fun `null or an unknown value from a newer server shows a dash`() {
        assertThat(goalLabel(null)).isEqualTo("—")
        assertThat(goalLabel("FLY")).isEqualTo("—")
        assertThat(concernLabel(null)).isEqualTo("—")
        assertThat(concernLabel("lower_back")).isEqualTo("—")
        assertThat(genderLabel(null)).isEqualTo("—")
    }

    @Test
    fun `formatPhone groups an Indian E164 number and leaves anything else as stored`() {
        assertThat(formatPhone("+919000000004")).isEqualTo("+91 90000 00004")
        assertThat(formatPhone("+14155550123")).isEqualTo("+14155550123")
        assertThat(formatPhone("9000000004")).isEqualTo("9000000004")
        assertThat(formatPhone("+9190000000041")).isEqualTo("+9190000000041")
        assertThat(formatPhone("+919000000004\n")).isEqualTo("+919000000004\n")
        assertThat(formatPhone(null)).isEqualTo("—")
        assertThat(formatPhone("")).isEqualTo("—")
    }
}
