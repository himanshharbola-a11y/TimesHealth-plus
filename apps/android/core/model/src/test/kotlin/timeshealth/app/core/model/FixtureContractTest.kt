package timeshealth.app.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Runs once per recorded staging response. Each fixture must:
 * 1. decode into its contract type with [ApiJson];
 * 2. decode with unknown keys DISALLOWED, which proves the Kotlin model covers every field the
 *    server sends today, so nothing is silently dropped;
 * 3. re-encode to the same data. That proves the model is lossless, including the hand-written
 *    union serializers. It also proves no real value fell back to UNKNOWN, because UNKNOWN
 *    re-encodes differently from the original.
 */
@RunWith(Parameterized::class)
class FixtureContractTest(private val fixture: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<String> = Fixtures.names.also { check(it.isNotEmpty()) { "No fixtures found" } }

        private val strict = Json(ApiJson) { ignoreUnknownKeys = false }
    }

    private val serializer = Fixtures.serializerFor(fixture).asAny()

    @Test
    fun `decodes into its contract type`() {
        val decoded = ApiJson.decodeFromString(serializer, Fixtures.text(fixture))

        assertThat(decoded.javaClass.simpleName).isEqualTo(serializer.descriptor.serialName.substringAfterLast('.'))
    }

    @Test
    fun `models every field the server sends`() {
        strict.decodeFromString(serializer, Fixtures.text(fixture))
    }

    @Test
    fun `re-encodes without losing data`() {
        val decoded = ApiJson.decodeFromString(serializer, Fixtures.text(fixture))
        val reEncoded = ApiJson.encodeToJsonElement(serializer, decoded)

        assertJsonEquivalent(expected = Fixtures.tree(fixture), actual = reEncoded)
        assertThat(ApiJson.decodeFromJsonElement(serializer, reEncoded)).isEqualTo(decoded)
    }
}
