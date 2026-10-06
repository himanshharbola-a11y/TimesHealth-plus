package timeshealth.server.json

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import timeshealth.app.core.model.ApiJson

/**
 * The JSON every API RESPONSE is written with: the Android app's [ApiJson] (ignoreUnknownKeys,
 * strict parsing, encodeDefaults) with ONE change, `explicitNulls = true`.
 *
 * Why: the Node server writes `JSON.stringify` of Prisma rows, which keeps every `null`
 * (`"name": null`, `"yoga": null`, `"diet": null`) and drops only `undefined`. The Android app
 * decodes either form, but the React Native app does not: it checks `x !== null`
 * (apps/mobile/app/paywall.tsx `yoga !== null && !yoga.active`, WorkshopSection.tsx
 * `w.pricePaise === null`), and an omitted key is `undefined` there. A drop-in server must keep
 * the nulls.
 *
 * Where Node omits a key (an `undefined` value), use [omitKeysWhenNull] on the encoded element.
 * In the routes ported so far the only such key is the error envelope's `fields`, which
 * error/ApiErrors.kt writes with [ApiJson] (nulls omitted).
 */
val ServerJson: Json = Json(ApiJson) {
    explicitNulls = true
}

/** Encodes [value] with [ServerJson], for responses that need post-processing. */
inline fun <reified T> T.toServerJson(): JsonElement = ServerJson.encodeToJsonElement(this)

/**
 * Removes the given top-level keys when their value is null: the Node `undefined` case.
 * Example: a field Node builds as `x: cond ? value : undefined`.
 */
fun JsonElement.omitKeysWhenNull(vararg keys: String): JsonElement {
    if (this !is JsonObject) return this
    return JsonObject(filterNot { (k, v) -> k in keys && v is JsonNull })
}

private val JS_ISO: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/**
 * `Date.prototype.toISOString()`: always UTC, always exactly three fraction digits
 * ("2026-10-06T09:00:07.591Z"). `Instant.toString()` drops a zero fraction and prints up to nine
 * digits, so it must never be used for the wire.
 */
fun Instant.toIsoString(): String = JS_ISO.format(truncatedTo(ChronoUnit.MILLIS))

/** `new Date()` for row timestamps: millisecond precision, like a JS Date and TIMESTAMP(3). */
fun nowMillis(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
