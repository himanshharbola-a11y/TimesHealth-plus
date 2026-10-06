package timeshealth.app.core.domain

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Test

/**
 * Run tracker distance rules (PRD §8.6) — a one-to-one port of the 13 tests in
 * apps/mobile/src/lib/runMath.test.ts, same fixtures and expectations, in the
 * same order. (Colons and dots in the test names became dashes: the JVM
 * forbids them in method names.) Extra Kotlin-only cases are in RunMathExtraTest.
 */
class RunMathTest {

    private companion object {
        const val T0 = 1_790_000_000_000L
        const val M_PER_DEG_LAT = 111_195.0
    }

    /** A fix `metres` north of the start line, `seconds` into the run. */
    private fun fix(metres: Int, seconds: Int, accuracy: Double = 5.0) = GeoFix(
        lat = 28.6139 + metres / M_PER_DEG_LAT,
        lng = 77.209,
        accuracy = accuracy,
        altitude = null,
        speed = null,
        timestamp = T0 + seconds * 1000L,
    )

    private fun anchorAt(metres: Int, seconds: Int): Anchor {
        val p = fix(metres, seconds)
        return Anchor(p.lat, p.lng, p.timestamp)
    }

    private fun near(actual: Double, expected: Double, tolerance: Double = 0.5) =
        assertWithMessage("$actual is not within $tolerance of $expected")
            .that(abs(actual - expected) <= tolerance).isTrue()

    // ── describe('run distance') ────────────────────────────────────────────

    @Test
    fun `a steady run counts every segment`() {
        val points = listOf(1, 2, 3, 4, 5).map { fix(it * 13, it * 3) }
        val plan = planBatch(anchorAt(0, 0), points, null, null)
        near(plan.addedM, 65.0)
        assertThat(plan.store).hasSize(5)
        assertThat(plan.dropped).isEqualTo(0)
    }

    @Test
    fun `the first fix of a run anchors it and adds nothing`() {
        val plan = planBatch(null, listOf(fix(0, 0), fix(13, 3)), null, null)
        assertThat(plan.store).hasSize(2)
        near(plan.addedM, 13.0)
    }

    @Test
    fun `standing still - jitter under 4 m is ignored`() {
        val plan = planBatch(anchorAt(0, 0), listOf(fix(2, 3), fix(1, 6), fix(3, 9)), null, null)
        assertThat(plan.addedM).isEqualTo(0.0)
        assertThat(plan.store).hasSize(0)
    }

    @Test
    fun `a poor fix is dropped, never averaged in`() {
        val plan = planBatch(anchorAt(0, 0), listOf(fix(13, 3), fix(80, 6, 60.0), fix(26, 9)), null, null)
        near(plan.addedM, 26.0)
        assertThat(plan.dropped).isEqualTo(1)
    }

    @Test
    fun `an underpass gap still counts - 150 m in a minute is a run, not a jump`() {
        val plan = planBatch(anchorAt(0, 0), listOf(fix(150, 60), fix(163, 63)), null, null)
        near(plan.addedM, 163.0)
        assertThat(plan.dropped).isEqualTo(0)
    }

    @Test
    fun `a one-off spike is not counted and does not derail the run`() {
        val points = listOf(fix(13, 3), fix(500, 6), fix(26, 9), fix(39, 12))
        val plan = planBatch(anchorAt(0, 0), points, null, null)
        near(plan.addedM, 39.0)
        assertThat(plan.dropped).isEqualTo(1)
        assertThat(plan.candidate).isNull()
    }

    @Test
    fun `after a real relocation the run carries on — the jump itself is not counted`() {
        // Signal comes back 600 m on, three seconds after the last fix.
        val points = listOf(fix(600, 3), fix(613, 6), fix(626, 9), fix(639, 12))
        val plan = planBatch(anchorAt(0, 0), points, null, null)
        near(plan.addedM, 39.0) // 613→626→639 plus the 13 m confirming step
        assertThat(plan.candidate).isNull()
        assertThat(plan.store.lastOrNull()?.timestamp).isEqualTo(fix(639, 12).timestamp)
    }

    @Test
    fun `the confirmation can arrive in the next batch`() {
        val first = planBatch(anchorAt(0, 0), listOf(fix(600, 3)), null, null)
        assertThat(first.addedM).isEqualTo(0.0)
        assertThat(first.candidate).isNotNull()
        val second = planBatch(anchorAt(0, 0), listOf(fix(613, 6)), null, first.candidate)
        near(second.addedM, 13.0)
        assertThat(second.store).hasSize(2)
    }

    @Test
    fun `after resume, ground covered while paused is not counted`() {
        val resumedAt = T0 + 600_000
        val plan = planBatch(anchorAt(0, 0), listOf(fix(300, 600), fix(313, 603)), resumedAt, null)
        near(plan.addedM, 13.0)
        assertThat(plan.reanchorAfter).isNull()
    }

    @Test
    fun `a cached pre-pause fix replayed on resume is ignored, not anchored on`() {
        // Paused at 570 s having reached 0 m, walked 300 m, resumed at 600 s. The
        // OS replays the 570 s fix first; anchoring on it would make the walk look
        // like a 300 m run in 33 s.
        val resumedAt = T0 + 600_000
        val plan = planBatch(anchorAt(0, 570), listOf(fix(0, 570), fix(300, 603), fix(313, 606)), resumedAt, null)
        near(plan.addedM, 13.0)
        assertThat(plan.store.firstOrNull()?.timestamp).isEqualTo(fix(300, 603).timestamp)
    }

    @Test
    fun `a fix cached from before the run started never becomes its first point`() {
        val startedAt = T0 + 300_000
        val plan = planBatch(null, listOf(fix(-2000, 0), fix(0, 303), fix(13, 306)), startedAt, null)
        near(plan.addedM, 13.0)
        assertThat(plan.store).hasSize(2)
    }

    @Test
    fun `a resume that sees only poor fixes keeps waiting to re-anchor`() {
        val resumedAt = T0 + 600_000
        val plan = planBatch(anchorAt(0, 0), listOf(fix(300, 600, 80.0)), resumedAt, null)
        assertThat(plan.reanchorAfter).isEqualTo(resumedAt)
        assertThat(plan.addedM).isEqualTo(0.0)
    }

    // ── describe('run ids') ─────────────────────────────────────────────────

    @Test
    fun `without crypto randomUUID (as on Hermes) ids are still RFC 4122 v4 — the server rejects anything else`() {
        // The TS test removed crypto.randomUUID to force the Math.random
        // fallback; here the fallback is called directly with a plain PRNG.
        val v4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        val ids = List(2000) { uuidV4(Random.Default) }.toSet()
        assertThat(ids).hasSize(2000)
        for (id in ids) {
            assertThat(v4.matches(id)).isTrue()
        }
    }
}
