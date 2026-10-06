package timeshealth.server.validation

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import timeshealth.app.core.domain.isValidEmail
import timeshealth.app.core.domain.trimJs
import timeshealth.server.json.RawBody

/**
 * The subset of zod v3 (apps/api uses 3.25) the Node routes use, with zod's semantics, so a body
 * the Node server accepts is accepted here and one it rejects is rejected:
 *
 *  - `.optional()` means the key may be ABSENT; an explicit `null` is still a type error.
 *  - unknown keys are stripped (ignored), as `z.object()` does by default.
 *  - every check runs and every failure is recorded (zod does not stop at the first).
 *  - `.trim()` transforms the value before the checks that follow it, using JavaScript's
 *    definition of whitespace.
 *  - lengths are UTF-16 code units, like `String.length` in both languages.
 *
 * Usage mirrors a schema, field by field, in the schema's order:
 * ```
 * val z = ZodObject(body)
 * val name = z.string("name", trim = true, min = 1, max = 80)
 * if (!z.success) throw ApiException(400, "INVALID_BODY", "Invalid profile payload")
 * ```
 * [fieldErrors] is zod's `error.flatten().fieldErrors`, for routes that return it as `fields`.
 */
class ZodObject(body: JsonElement?) {
    constructor(body: RawBody) : this(body.value)

    private val obj: JsonObject? = body as? JsonObject
    private val fields = linkedMapOf<String, MutableList<String>>()

    /** zod's `formErrors`: issues not tied to a field (the body is not an object). */
    val formErrors: List<String> =
        if (obj == null) listOf("Expected object, received ${typeName(body)}") else emptyList()

    val success: Boolean get() = obj != null && fields.isEmpty()

    val fieldErrors: Map<String, List<String>> get() = fields

    private fun fail(key: String, message: String) {
        fields.getOrPut(key) { mutableListOf() } += message
    }

    /** The raw value: null when absent (Node's undefined); [JsonNull] when sent as null. */
    private fun raw(key: String): JsonElement? = obj?.get(key)

    /** Checks presence/type; returns the string content, or null (absent, or an issue recorded). */
    private fun presentString(key: String, optional: Boolean): String? {
        if (obj == null) return null
        val v = raw(key)
        if (v == null) {
            if (!optional) fail(key, "Required")
            return null
        }
        if (v !is JsonPrimitive || !v.isString) {
            fail(key, "Expected string, received ${typeName(v)}")
            return null
        }
        return v.content
    }

    /**
     * `z.string()` with the usual chain. Checks run in zod's order for this codebase's schemas:
     * trim, then email, min, max, datetime. The refinement runs only when they all pass (zod
     * also runs it on a value that already failed a check, which can only add a second message
     * to [fieldErrors]; whether the body is accepted is the same).
     */
    fun string(
        key: String,
        optional: Boolean = true,
        trim: Boolean = false,
        min: Int? = null,
        max: Int? = null,
        email: Boolean = false,
        datetime: Boolean = false,
        refine: ((String) -> Boolean)? = null,
    ): String? {
        var value = presentString(key, optional) ?: return null
        if (trim) value = value.trimJs()
        val before = fields[key]?.size ?: 0
        if (email && !isValidEmail(value)) fail(key, "Invalid email")
        if (min != null && value.length < min) fail(key, "String must contain at least $min character(s)")
        if (max != null && value.length > max) fail(key, "String must contain at most $max character(s)")
        if (datetime && parseZodDatetime(value) == null) fail(key, "Invalid datetime")
        if ((fields[key]?.size ?: 0) > before) return null
        if (refine != null && !refine(value)) {
            fail(key, "Invalid input")
            return null
        }
        return value
    }

    /** `z.enum([...])`. */
    fun enum(key: String, values: List<String>, optional: Boolean = true): String? {
        if (obj == null) return null
        val v = raw(key)
        if (v == null) {
            if (!optional) fail(key, "Required")
            return null
        }
        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (s == null || s !in values) {
            val expected = values.joinToString(" | ") { "'$it'" }
            val received = if (s != null) "'$s'" else typeName(v)
            fail(key, "Invalid enum value. Expected $expected, received $received")
            return null
        }
        return s
    }

    /** `z.union([z.literal(1), z.literal(2), ...])` over numbers. zod reports "Invalid input". */
    fun numberLiteral(key: String, values: Set<Int>, optional: Boolean = false): Int? {
        if (obj == null) return null
        val v = raw(key)
        if (v == null) {
            if (!optional) fail(key, "Required")
            return null
        }
        val n = (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        val match = values.firstOrNull { n != null && it.toDouble() == n }
        if (match == null) {
            fail(key, "Invalid input")
            return null
        }
        return match
    }

    /** `z.number()` with optional `.int()`, `.min()`, `.max()` (inclusive). */
    fun number(
        key: String,
        optional: Boolean = true,
        int: Boolean = false,
        min: Double? = null,
        max: Double? = null,
    ): Double? {
        if (obj == null) return null
        val v = raw(key)
        if (v == null) {
            if (!optional) fail(key, "Required")
            return null
        }
        val n = (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        if (n == null) {
            fail(key, "Expected number, received ${typeName(v)}")
            return null
        }
        val before = fields[key]?.size ?: 0
        if (int && n != Math.floor(n)) fail(key, "Expected integer, received float")
        if (min != null && n < min) fail(key, "Number must be greater than or equal to ${fmt(min)}")
        if (max != null && n > max) fail(key, "Number must be less than or equal to ${fmt(max)}")
        return if ((fields[key]?.size ?: 0) > before) null else n
    }

    /** `z.boolean()`. */
    fun boolean(key: String, optional: Boolean = true): Boolean? {
        if (obj == null) return null
        val v = raw(key)
        if (v == null) {
            if (!optional) fail(key, "Required")
            return null
        }
        val b = (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (b == null) fail(key, "Expected boolean, received ${typeName(v)}")
        return b
    }

    /** True when the key is present at all (even as null). */
    fun has(key: String): Boolean = obj?.containsKey(key) == true

    private fun fmt(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    companion object {
        /** zod's `getParsedType` names, as they appear in "Expected X, received Y". */
        fun typeName(v: JsonElement?): String = when (v) {
            null -> "undefined"
            is JsonNull -> "null"
            is JsonObject -> "object"
            is JsonArray -> "array"
            is JsonPrimitive -> when {
                v.isString -> "string"
                v.booleanOrNull != null -> "boolean"
                else -> "number"
            }
        }

        // zod v3 `datetimeRegex({ precision: null, offset: false, local: false })`, verbatim:
        // leap-year aware date, seconds and fraction optional, and only a literal "Z" offset.
        private val ZOD_DATETIME = Regex(
            "^((\\d\\d[2468][048]|\\d\\d[13579][26]|\\d\\d0[48]|[02468][048]00|[13579][26]00)-02-29|" +
                "\\d{4}-((0[13578]|1[02])-(0[1-9]|[12]\\d|3[01])|(0[469]|11)-(0[1-9]|[12]\\d|30)|(02)-(0[1-9]|1\\d|2[0-8])))" +
                "T([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d(\\.\\d+)?)?(Z)$",
        )
        private val PARTS = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d+))?)?Z$")

        /**
         * Validates like `z.string().datetime()` and returns the instant `new Date(v)` would give:
         * millisecond precision, extra fraction digits truncated (as V8 does). Null if invalid.
         */
        fun parseZodDatetime(value: String): Instant? {
            if (!ZOD_DATETIME.matches(value)) return null
            val m = PARTS.matchEntire(value) ?: return null
            val (y, mo, d, h, mi) = m.destructured
            val s = m.groupValues[6].ifEmpty { "0" }.toInt()
            val millis = m.groupValues[7].take(3).padEnd(3, '0').toInt()
            val date = LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
            val time = LocalTime.of(h.toInt(), mi.toInt(), s, millis * 1_000_000)
            return date.atTime(time).toInstant(ZoneOffset.UTC)
        }
    }
}
