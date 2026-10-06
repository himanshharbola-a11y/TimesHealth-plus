package timeshealth.app.core.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import timeshealth.app.core.data.pass.OfflinePassStore
import timeshealth.app.core.data.pass.clip
import timeshealth.app.core.data.pass.offlineSignatureExpiryMs
import timeshealth.app.core.model.RaceTier
import timeshealth.app.core.network.ServerClock

@OptIn(ExperimentalCoroutinesApi::class)
class OfflinePassStoreTest {

    private val store = FakeSecureStore()
    private var now = NOW_MS
    private val passes = OfflinePassStore(store, ServerClock { now })

    @Test
    fun `a saved pass loads with what the gate needs, encrypted store only`() = runTest {
        passes.save("delhi_half", bib(), passes.generation(), marathonEvent())

        val pass = passes.load("delhi_half")!!

        assertThat(pass.eventId).isEqualTo("delhi_half")
        assertThat(pass.bibNumber).isEqualTo("A1024")
        assertThat(pass.participantName).isEqualTo("Asha Rao")
        assertThat(pass.category).isEqualTo("21K")
        assertThat(pass.tier).isEqualTo(RaceTier.PREMIUM)
        assertThat(pass.eventName).isEqualTo("Delhi Half Marathon")
        assertThat(pass.offlinePayload).isEqualTo(offlinePayload())
        assertThat(pass.flagOffTime).isEqualTo("2026-10-26T00:45:00.000Z")
        assertThat(pass.expo).isEqualTo(expo())
        // JavaScript toISOString(): UTC, always with milliseconds.
        assertThat(pass.savedAt).isEqualTo("2026-10-06T06:00:00.000Z")
        // Never the short-lived QR token, which is meaningless offline.
        assertThat(store.values.values.joinToString()).doesNotContain("qr-token-short-lived")
    }

    @Test
    fun `passes are listed in save order, a re-save doesn't duplicate`() = runTest {
        val gen = passes.generation()
        passes.save("mumbai_full", bib(), gen)
        passes.save("delhi_half", bib(), gen)
        passes.save("mumbai_full", bib(), gen)

        assertThat(passes.list().map { it.eventId }).containsExactly("mumbai_full", "delhi_half").inOrder()
    }

    @Test
    fun `a pass past its signature is not offered`() = runTest {
        passes.save("delhi_half", bib(payload = offlinePayload(expiresInS = 60)), passes.generation())
        assertThat(passes.load("delhi_half")).isNotNull()

        now += 61_000

        assertThat(passes.load("delhi_half")).isNull()
        assertThat(passes.list()).isEmpty()
    }

    @Test
    fun `an unreadable signature counts as expired`() {
        assertThat(offlineSignatureExpiryMs("REF.user.1791266400.sig")).isEqualTo(1_791_266_400_000L)
        assertThat(offlineSignatureExpiryMs("REF.user.notanumber.sig")).isEqualTo(0)
        assertThat(offlineSignatureExpiryMs("REF.user")).isEqualTo(0)
        assertThat(offlineSignatureExpiryMs("REF.user..sig")).isEqualTo(0)
        assertThat(offlineSignatureExpiryMs("REF.user.Infinity.sig")).isEqualTo(0)
    }

    @Test
    fun `the server stopped issuing a bib - the pass is removed`() = runTest {
        val gen = passes.generation()
        passes.save("delhi_half", bib(), gen)
        passes.save("mumbai_full", bib(), gen)

        passes.remove("delhi_half", gen)

        assertThat(passes.load("delhi_half")).isNull()
        assertThat(passes.list().map { it.eventId }).containsExactly("mumbai_full")
        assertThat(store.values).doesNotContainKey(OfflinePassStore.keyFor("delhi_half"))
    }

    @Test
    fun `clear wipes every pass and the index`() = runTest {
        val gen = passes.generation()
        passes.save("delhi_half", bib(), gen)
        passes.save("mumbai_full", bib(), gen)

        passes.clear()

        assertThat(passes.list()).isEmpty()
        assertThat(store.values).isEmpty()
    }

    @Test
    fun `a response that lands after sign-out can't write the previous user's pass back`() = runTest {
        val genAtFetchStart = passes.generation()

        passes.clear() // sign-out while the race page was loading
        passes.save("delhi_half", bib(), genAtFetchStart)

        assertThat(passes.load("delhi_half")).isNull()
        assertThat(store.values).isEmpty()
    }

    @Test
    fun `nor remove the next user's pass`() = runTest {
        val staleGen = passes.generation()
        passes.clear()
        passes.save("delhi_half", bib(), passes.generation())

        passes.remove("delhi_half", staleGen)

        assertThat(passes.load("delhi_half")).isNotNull()
    }

    @Test
    fun `a wipe waits for a save in progress, and a save queued behind it is dropped`() = runTest {
        val gen = passes.generation()
        val gate = CompletableDeferred<Unit>()
        store.pausePut = OfflinePassStore.keyFor("delhi_half") to gate

        launch { passes.save("delhi_half", bib(), gen) } // already writing
        runCurrent()
        launch { passes.clear() } // sign-out
        launch { passes.save("mumbai_full", bib(), gen) } // fetched before the sign-out
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertThat(passes.list()).isEmpty()
        // Nothing left outside the index either.
        assertThat(store.values).isEmpty()
    }

    @Test
    fun `the expo card is clipped to keep each pass small`() = runTest {
        val longExpo = expo(
            venue = "V".repeat(200),
            instructions = "I".repeat(500),
            documents = (1..10).map { "Document $it" },
        )

        passes.save("delhi_half", bib(), passes.generation(), marathonEvent(expo = longExpo))
        val expo = passes.load("delhi_half")!!.expo!!

        assertThat(expo.venue).hasLength(160)
        assertThat(expo.venue).endsWith("V…")
        assertThat(expo.instructions).hasLength(400)
        assertThat(expo.instructions).endsWith("I…")
        assertThat(expo.requiredDocuments).containsExactlyElementsIn((1..6).map { "Document $it" }).inOrder()
        // The rest is kept as is.
        assertThat(expo.address).isEqualTo(longExpo.address)
    }

    @Test
    fun `clip leaves short text alone and never splits an emoji`() {
        assertThat(clip("Gate 4", 160)).isEqualTo("Gate 4")
        assertThat(clip("x".repeat(160), 160)).hasLength(160)
        assertThat(clip("abcdef", 4)).isEqualTo("abc…")
        // "ab" + 🏃 (a surrogate pair) + "cd": cutting at 3 units would split the pair.
        assertThat(clip("ab🏃cd", 4)).isEqualTo("ab…")
    }

    @Test
    fun `two ids sharing a sanitised key never show each other's pass`() = runTest {
        passes.save("race/1", bib(), passes.generation())

        assertThat(OfflinePassStore.keyFor("race/1")).isEqualTo(OfflinePassStore.keyFor("race_1"))
        assertThat(passes.load("race_1")).isNull()
        assertThat(passes.load("race/1")).isNotNull()
    }

    @Test
    fun `a pass the index doesn't list is never shown, since sign-out couldn't wipe it`() = runTest {
        passes.save("delhi_half", bib(), passes.generation())

        store.values.remove(OfflinePassStore.INDEX_KEY)

        assertThat(store.values).containsKey(OfflinePassStore.keyFor("delhi_half"))
        assertThat(passes.load("delhi_half")).isNull()
        assertThat(passes.list()).isEmpty()
    }

    @Test
    fun `an unreadable store reads as no passes`() = runTest {
        passes.save("delhi_half", bib(), passes.generation())
        store.failReads = true

        assertThat(passes.list()).isEmpty()
        assertThat(passes.load("delhi_half")).isNull()
    }
}
