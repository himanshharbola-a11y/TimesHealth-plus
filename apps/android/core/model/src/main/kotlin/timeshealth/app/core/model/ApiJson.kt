package timeshealth.app.core.model

import kotlin.reflect.KClass
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The one [Json] configuration for the TimesHealth+ API. Use it everywhere a contract type is
 * encoded or decoded (the Retrofit converter, on-disk caches, tests), so the rules below hold
 * everywhere.
 *
 * - `ignoreUnknownKeys = true`: the server adds fields before apps update ("Old clients live for
 *   years", feed.ts). A new key must never break an old build.
 * - `explicitNulls = false`: a missing nullable key decodes as `null`, and a `null` optional
 *   request field is left out rather than sent as `null`. The server's zod schemas use
 *   `.optional()`. The one exception is a key the server requires even when null; see
 *   [RequiredNullKeysSerializer].
 * - `isLenient = false`: the API speaks strict JSON. Anything else, such as a proxy's HTML error
 *   page, is an error and must not be quietly coerced.
 * - `encodeDefaults = true`: a request property that happens to equal its Kotlin default is still
 *   sent. With `explicitNulls = false`, `null` defaults are still omitted.
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = false
    encodeDefaults = true
}

// ─────────────────────────────────────────────────────────────────────────────
// Forward compatibility
//
// The server ships new values before apps update. An old app must never crash,
// or blank a whole screen, because the server started sending a value this build
// has never seen. Two mechanisms cover every closed set in the contract:
//
//  1. Closed string unions (TS `'A' | 'B'`) are Kotlin enums whose last entry is
//     `UNKNOWN`. Any unrecognised string decodes to UNKNOWN.
//     Swift: `enum X: String, Codable { ...; case unknown }`, with an
//     `init(from:)` that falls back to `.unknown`.
//
//  2. Discriminated unions (FeedComponent "type", HeroSlot "kind",
//     FeedAction "type") are sealed hierarchies with an `Unknown` variant. An
//     unrecognised discriminator decodes to `Unknown`. Swift: an enum with
//     associated values, plus `case unknown(type: String?)`.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Serializer for a closed string union modelled as an enum. Wire values are the entry names
 * verbatim (every TS union in the contract is SCREAMING_CASE). Any other string decodes to
 * [unknown] instead of throwing.
 *
 * [unknown] encodes as the literal `"UNKNOWN"`, because the original string isn't kept. That is
 * fine for re-encoding a cached response, but never send an `UNKNOWN` back in a request: the
 * server rejects it with 400.
 *
 * Usage: each enum declares `internal object Serializer : ForwardCompatibleEnumSerializer<E>(entries, UNKNOWN)`
 * and is annotated `@Serializable(with = E.Serializer::class)`.
 */
abstract class ForwardCompatibleEnumSerializer<E : Enum<E>>(
    entries: List<E>,
    private val unknown: E,
) : KSerializer<E> {
    private val byName: Map<String, E> = entries.associateBy { it.name }

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(unknown.declaringJavaClass.name, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): E = byName[decoder.decodeString()] ?: unknown
}

/** One known member of a discriminated union: its discriminator value and its serializer. */
internal class UnionVariant<V : Any>(
    val tag: String,
    val kClass: KClass<V>,
    val serializer: KSerializer<V>,
)

internal inline fun <reified V : Any> variant(tag: String, serializer: KSerializer<V>) =
    UnionVariant(tag, V::class, serializer)

/**
 * Serializer for a TS discriminated union (`{ type: 'A', ... } | { type: 'B', ... }`).
 *
 * Decoding reads the object, looks up [discriminator], and decodes the matching variant. The
 * discriminator key is removed first, so variants don't declare it. A missing, non-string or
 * unrecognised discriminator, or an element that isn't an object at all, becomes
 * [toUnknown], which never throws. A KNOWN discriminator with a malformed body still throws: that
 * is a contract violation, not forward compatibility, and the contract tests guard against it.
 *
 * Encoding writes the variant's fields with the discriminator first, so re-decoding a cached
 * value gives back the same object. An unknown variant re-encodes as `{ "<discriminator>": raw }`.
 *
 * JSON only. Works with any [Json] instance, not just [ApiJson].
 */
internal abstract class ForwardCompatibleUnionSerializer<T : Any>(
    serialName: String,
    private val discriminator: String,
    variants: List<UnionVariant<out T>>,
) : KSerializer<T> {
    private val byTag: Map<String, UnionVariant<out T>> = variants.associateBy { it.tag }
    private val byClass: Map<KClass<out T>, UnionVariant<out T>> = variants.associateBy { it.kClass }

    /** Builds the fallback for a discriminator this build doesn't know (null when absent). */
    protected abstract fun toUnknown(tag: String?): T

    /** The raw discriminator held by a fallback value, or null if [value] isn't one. */
    protected abstract fun unknownTag(value: T): String?

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(serialName) {
        element(discriminator, String.serializer().descriptor)
    }

    override fun deserialize(decoder: Decoder): T {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("${descriptor.serialName} can only be decoded from JSON")
        val obj = input.decodeJsonElement() as? JsonObject ?: return toUnknown(null)
        val tag = (obj[discriminator] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val variant = tag?.let(byTag::get) ?: return toUnknown(tag)
        return input.json.decodeFromJsonElement(variant.serializer, JsonObject(obj - discriminator))
    }

    override fun serialize(encoder: Encoder, value: T) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("${descriptor.serialName} can only be encoded to JSON")
        val variant = byClass[value::class]
        val tagged = if (variant == null) {
            val raw = unknownTag(value)
            JsonObject(if (raw == null) emptyMap() else mapOf(discriminator to JsonPrimitive(raw)))
        } else {
            @Suppress("UNCHECKED_CAST")
            val body = output.json.encodeToJsonElement(variant.serializer as KSerializer<T>, value).jsonObject
            JsonObject(mapOf(discriminator to JsonPrimitive(variant.tag)) + body)
        }
        output.encodeJsonElement(tagged)
    }
}

/**
 * For request keys typed `T | null` WITHOUT `?` in TS, which the server requires to be present
 * even when null (zod `.nullable()` without `.optional()`). [ApiJson]'s `explicitNulls = false`
 * drops every null property, and the server would answer 400. This writes `null` back for
 * [requiredKeys], keeping declaration order.
 *
 * This can't be done per property: the serialization plugin routes every nullable property
 * through `encodeNullableSerializableElement`, which skips nulls before any property-level
 * serializer runs. Apply it to the class with `@KeepGeneratedSerializer` and
 * `@Serializable(with = ...)`. Swift: `encode(_:forKey:)` instead of `encodeIfPresent`.
 */
internal abstract class RequiredNullKeysSerializer<T : Any>(
    generated: KSerializer<T>,
    private val requiredKeys: Set<String>,
) : JsonTransformingSerializer<T>(generated) {
    override fun transformSerialize(element: JsonElement): JsonElement {
        val obj = element.jsonObject
        if (requiredKeys.all { it in obj }) return obj
        val ordered = LinkedHashMap<String, JsonElement>()
        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            val value = obj[name] ?: if (name in requiredKeys) JsonNull else null
            if (value != null) ordered[name] = value
        }
        return JsonObject(ordered)
    }
}

/**
 * [ApiError.fields]. The TS contract says `Record<string, string>`, but the server actually sends
 * zod's `flatten().fieldErrors`, which is `Record<string, string[]>`
 * (apps/api/src/routes/diet.ts and runs.ts cast it). Both shapes are accepted and normalised to a
 * list of messages per field. Encoding always writes the array form, which is what the server
 * sends.
 */
internal object FieldErrorsSerializer : KSerializer<Map<String, List<String>>> {
    private val delegate = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Map<String, List<String>>) =
        delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): Map<String, List<String>> {
        val input = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val obj = input.decodeJsonElement() as? JsonObject ?: return emptyMap()
        return obj.mapValues { (_, messages) ->
            when (messages) {
                is JsonArray -> messages.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> listOfNotNull(messages.contentOrNull)
                else -> emptyList()
            }
        }
    }
}
