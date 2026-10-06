package timeshealth.app.core.model

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The real responses in src/test/resources/fixtures, recorded from the live staging API for the
 * six QA personas (free, yoga, marathon, both, expired, finisher).
 *
 * File names are `<endpoint>[.<persona or variant>][.error<status>].json`.
 */
internal object Fixtures {
    private val dir: File by lazy {
        val url = checkNotNull(Fixtures::class.java.classLoader.getResource("fixtures")) {
            "fixtures/ is not on the test classpath"
        }
        File(url.toURI())
    }

    val names: List<String> get() = dir.list { _, name -> name.endsWith(".json") }.orEmpty().sorted()

    fun text(name: String): String = File(dir, name).readText()

    fun tree(name: String): JsonObject = ApiJson.parseToJsonElement(text(name)).jsonObject

    inline fun <reified T> decode(name: String): T = ApiJson.decodeFromString<T>(text(name))

    /** The contract type each recorded endpoint returns. Unmapped fixtures fail loudly. */
    fun serializerFor(name: String): KSerializer<out Any> {
        if (Regex("""\.error\d{3}\.json$""").containsMatchIn(name)) return ApiError.serializer()
        return when (val endpoint = name.substringBefore('.')) {
            "bib-token" -> BibTokenResponse.serializer()
            "config" -> AppConfigResponse.serializer()
            "content" -> ContentResponse.serializer()
            "home" -> HomeFeedResponse.serializer()
            "marathon-events" -> MarathonListResponse.serializer()
            "notifications" -> NotificationListResponse.serializer()
            "playback" -> PlaybackResponse.serializer()
            "race-detail" -> RaceDetailResponse.serializer()
            "referral" -> ReferralState.serializer()
            "runs" -> RunHistoryResponse.serializer()
            "session" -> SessionResponse.serializer()
            "workshops" -> WorkshopListResponse.serializer()
            "yoga-attendance" -> YogaAttendance.serializer()
            "yoga-catalog" -> YogaCatalogResponse.serializer()
            "yoga-mine" -> MySessionsResponse.serializer()
            "yoga-today" -> YogaTodayResponse.serializer()
            else -> error("Fixture $name has no contract type: map '$endpoint' in Fixtures.serializerFor")
        }
    }
}

/**
 * Asserts [actual] carries exactly the data in [expected]. Two differences are ignored, because
 * ApiJson produces them by design: keys whose value is `null` (explicitNulls = false omits them)
 * and number formatting (`8` and `8.0` are the same value).
 */
internal fun assertJsonEquivalent(expected: JsonElement, actual: JsonElement, path: String = "$") {
    when (expected) {
        is JsonObject -> {
            assertWithMessage("$path should be an object").that(actual).isInstanceOf(JsonObject::class.java)
            val want = expected.filterValues { it !is JsonNull }
            val got = (actual as JsonObject).filterValues { it !is JsonNull }
            assertWithMessage("keys at $path").that(got.keys).containsExactlyElementsIn(want.keys)
            want.forEach { (key, value) -> assertJsonEquivalent(value, got.getValue(key), "$path.$key") }
        }
        is JsonArray -> {
            assertWithMessage("$path should be an array").that(actual).isInstanceOf(JsonArray::class.java)
            val got = actual as JsonArray
            assertWithMessage("size of $path").that(got.size).isEqualTo(expected.size)
            expected.forEachIndexed { i, value -> assertJsonEquivalent(value, got[i], "$path[$i]") }
        }
        is JsonNull -> assertWithMessage(path).that(actual).isEqualTo(JsonNull)
        is JsonPrimitive -> {
            assertWithMessage("$path should be a primitive").that(actual).isInstanceOf(JsonPrimitive::class.java)
            val got = actual as JsonPrimitive
            val number = expected.takeUnless { it.isString }?.doubleOrNull
            if (number != null) {
                assertWithMessage(path).that(got.isString).isFalse()
                assertWithMessage(path).that(got.doubleOrNull).isEqualTo(number)
            } else {
                assertWithMessage(path).that(got).isEqualTo(expected)
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun KSerializer<out Any>.asAny(): KSerializer<Any> = this as KSerializer<Any>
