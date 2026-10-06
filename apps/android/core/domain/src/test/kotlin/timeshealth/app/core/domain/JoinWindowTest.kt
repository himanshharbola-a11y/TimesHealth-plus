package timeshealth.app.core.domain

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

/** The class join window: start − 60 min ≤ now < start + 60 min (PRD §7.1, §2). */
class JoinWindowTest {

    /** The 6:00 AM IST batch on 6 Oct 2026. */
    private val startIso = "2026-10-06T00:30:00.000Z"
    private val start = Instant.parse(startIso).toEpochMilli()
    private val minute = 60_000L

    @Test
    fun `constants are one hour each, as the server's`() {
        assertThat(WAIT_ROOM_MS).isEqualTo(60 * minute)
        assertThat(CLASS_MS).isEqualTo(60 * minute)
        assertThat(WAIT_ROOM_MINUTES).isEqualTo(60)
        assertThat(YOGA_BATCH_DURATION_MINUTES).isEqualTo(60)
    }

    @Test
    fun `opens exactly 60 minutes before the start, not a millisecond earlier`() {
        assertThat(isJoinOpen(start, start - 60 * minute)).isTrue()
        assertThat(isJoinOpen(start, start - 60 * minute - 1)).isFalse()
    }

    @Test
    fun `stays open through the start and the class`() {
        assertThat(isJoinOpen(start, start - 1)).isTrue()
        assertThat(isJoinOpen(start, start)).isTrue()
        assertThat(isJoinOpen(start, start + 30 * minute)).isTrue()
    }

    @Test
    fun `closes exactly 60 minutes after the start`() {
        assertThat(isJoinOpen(start, start + 60 * minute - 1)).isTrue()
        assertThat(isJoinOpen(start, start + 60 * minute)).isFalse()
    }

    @Test
    fun `a tap at 10 PM for tomorrow's batch is not a join`() {
        val tenPmIstTheNightBefore = Instant.parse("2026-10-05T16:30:00Z").toEpochMilli()
        assertThat(isJoinOpen(start, tenPmIstTheNightBefore)).isFalse()
    }

    @Test
    fun `the ISO overload parses the API's startsAt, and an unparseable one is closed`() {
        assertThat(isJoinOpen(startIso, start)).isTrue()
        assertThat(isJoinOpen("2026-10-06T06:00:00+05:30", start)).isTrue()
        assertThat(isJoinOpen("2026-10-06T00:30:00Z", start + 60 * minute)).isFalse()
        assertThat(isJoinOpen("not a date", start)).isFalse()
        assertThat(isJoinOpen("", start)).isFalse()
    }

    @Test
    fun `the app's isJoinOpen and the server's inJoinWindow agree at every edge`() {
        val offsets = buildList {
            for (m in -61L..61L) add(m * minute)
            for (edge in listOf(-60 * minute, 0L, 60 * minute)) {
                add(edge - 1); add(edge); add(edge + 1)
            }
        }
        for (offset in offsets) {
            val now = start + offset
            val server = inJoinWindow(Instant.ofEpochMilli(start), Instant.ofEpochMilli(now))
            assertThat(isJoinOpen(start, now)).isEqualTo(server)
            // The server's own formula, written out as in apps/api/src/time.ts.
            val expected = now >= start - WAIT_ROOM_MINUTES * minute && now < start + YOGA_BATCH_DURATION_MINUTES * minute
            assertThat(server).isEqualTo(expected)
        }
    }

    @Test
    fun `isLiveNow runs from the start to the end of the class`() {
        assertThat(isLiveNow(start, start - 1)).isFalse()
        assertThat(isLiveNow(start, start)).isTrue()
        assertThat(isLiveNow(start, start + 60 * minute - 1)).isTrue()
        assertThat(isLiveNow(start, start + 60 * minute)).isFalse()
        assertThat(isLiveNow(Instant.ofEpochMilli(start), Instant.ofEpochMilli(start))).isTrue()
    }
}
