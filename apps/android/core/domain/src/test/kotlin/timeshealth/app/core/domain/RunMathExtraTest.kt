package timeshealth.app.core.domain

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

/** Boundaries of the run rules that the TS suite doesn't pin, plus the id helpers. */
class RunMathExtraTest {

    private val t0 = 1_790_000_000_000L
    private val mPerDegLat = 111_195.0

    private fun fix(metres: Double, ms: Long, accuracy: Double = 5.0) =
        GeoFix(lat = 28.6139 + metres / mPerDegLat, lng = 77.209, accuracy = accuracy, timestamp = t0 + ms)

    private val start = fix(0.0, 0).toAnchor()

    @Test
    fun `haversine - one degree of latitude is about 111 km, and distance is symmetric`() {
        val a = Anchor(28.0, 77.0, 0)
        val b = Anchor(29.0, 77.0, 0)
        assertThat(haversineM(a, b)).isWithin(1.0).of(111_195.0)
        assertThat(haversineM(b, a)).isWithin(1e-6).of(haversineM(a, b))
        assertThat(haversineM(a, a)).isEqualTo(0.0)
    }

    @Test
    fun `accuracy gate - exactly 25 m is kept, worse is dropped`() {
        val kept = planBatch(start, listOf(fix(13.0, 3_000, accuracy = 25.0)), null, null)
        assertThat(kept.store).hasSize(1)
        assertThat(kept.dropped).isEqualTo(0)
        val dropped = planBatch(start, listOf(fix(13.0, 3_000, accuracy = 25.01)), null, null)
        assertThat(dropped.store).isEmpty()
        assertThat(dropped.dropped).isEqualTo(1)
        assertThat(dropped.total).isEqualTo(1)
    }

    @Test
    fun `minimum segment - 4 m counts, just under is jitter`() {
        assertThat(planBatch(start, listOf(fix(4.01, 3_000)), null, null).store).hasSize(1)
        assertThat(planBatch(start, listOf(fix(3.99, 3_000)), null, null).store).isEmpty()
    }

    @Test
    fun `speed limit - 12 m per s is a run, faster is held as a candidate`() {
        val ok = planBatch(start, listOf(fix(119.9, 10_000)), null, null)
        assertThat(ok.addedM).isWithin(0.5).of(119.9)
        assertThat(ok.candidate).isNull()
        val tooFast = planBatch(start, listOf(fix(121.0, 10_000)), null, null)
        assertThat(tooFast.addedM).isEqualTo(0.0)
        assertThat(tooFast.candidate).isNotNull()
        assertThat(tooFast.dropped).isEqualTo(1)
    }

    @Test
    fun `fixes with the same timestamp are measured over one second, not zero`() {
        val plan = planBatch(start, listOf(fix(10.0, 0)), null, null)
        assertThat(plan.addedM).isWithin(0.5).of(10.0)
    }

    @Test
    fun `stale fix grace - 2 s before the resume still anchors, a millisecond more is a replay`() {
        val resumedAt = t0 + 600_000
        val edge = planBatch(start, listOf(fix(300.0, 600_000 - STALE_FIX_GRACE_MS)), resumedAt, null)
        assertThat(edge.store).hasSize(1)
        assertThat(edge.reanchorAfter).isNull()
        val replay = planBatch(start, listOf(fix(300.0, 600_000 - STALE_FIX_GRACE_MS - 1)), resumedAt, null)
        assertThat(replay.store).isEmpty()
        assertThat(replay.reanchorAfter).isEqualTo(resumedAt)
        // A replay is skipped silently: not counted as dropped.
        assertThat(replay.dropped).isEqualTo(0)
    }

    @Test
    fun `re-anchoring clears a held candidate`() {
        val held = fix(600.0, 3_000)
        val plan = planBatch(start, listOf(fix(5.0, 700_000)), t0 + 700_000, held)
        assertThat(plan.candidate).isNull()
        assertThat(plan.addedM).isEqualTo(0.0)
    }

    @Test
    fun `a second impossible fix that disagrees with the candidate replaces it`() {
        val plan = planBatch(start, listOf(fix(600.0, 3_000), fix(-600.0, 6_000)), null, null)
        assertThat(plan.dropped).isEqualTo(2)
        assertThat(plan.candidate?.timestamp).isEqualTo(t0 + 6_000)
        assertThat(plan.store).isEmpty()
    }

    @Test
    fun `a confirming fix within 4 m of the candidate re-anchors without adding distance`() {
        val plan = planBatch(start, listOf(fix(600.0, 3_000), fix(602.0, 6_000)), null, null)
        assertThat(plan.store.map { it.timestamp }).containsExactly(t0 + 3_000)
        assertThat(plan.addedM).isEqualTo(0.0)
        assertThat(plan.candidate).isNull()
    }

    @Test
    fun `UUID_RE accepts any-case UUIDs and nothing else`() {
        assertThat(UUID_RE.matches("123e4567-e89b-42d3-a456-426614174000")).isTrue()
        assertThat(UUID_RE.matches("123E4567-E89B-42D3-A456-426614174000")).isTrue()
        assertThat(UUID_RE.matches("1790000000000-abc123")).isFalse() // the old fallback id
        assertThat(UUID_RE.matches("123e4567e89b42d3a456426614174000")).isFalse()
        assertThat(UUID_RE.matches("123e4567-e89b-42d3-a456-426614174000\n")).isFalse()
        assertThat(UUID_RE.matches("")).isFalse()
    }

    @Test
    fun `the platform uuidV4 is v4 and passes UUID_RE`() {
        val v4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        repeat(200) {
            val id = uuidV4()
            assertThat(v4.matches(id)).isTrue()
            assertThat(UUID_RE.matches(id)).isTrue()
        }
    }

    @Test
    fun `the fallback uuidV4 is deterministic for a seeded generator`() {
        assertThat(uuidV4(Random(42))).isEqualTo(uuidV4(Random(42)))
        assertThat(uuidV4(Random(42))).isNotEqualTo(uuidV4(Random(43)))
    }
}
