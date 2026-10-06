package timeshealth.server.admin

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import timeshealth.server.cms.ContentCache
import timeshealth.server.db.Cuid
import timeshealth.server.db.PgSql.Companion.quote
import timeshealth.server.db.toDbTime
import timeshealth.server.error.ApiException
import timeshealth.server.json.ServerJson
import timeshealth.server.json.nowMillis
import timeshealth.server.json.toIsoString

/**
 * Generic create / read / update / delete / reorder for every [AdminResource]. Table and column
 * names come only from [AdminResources]; values are always bound parameters.
 *
 * Every write: validated field by field (all problems reported at once, keyed by field, for the
 * form), recorded in the audit log, and followed by [ContentCache.invalidateAll] so the app shows
 * it on the next request.
 */
@Service
class AdminCrud(
    private val jdbc: JdbcClient,
    private val cache: ContentCache,
    private val audit: AdminAudit,
) {

    // ── Reads ─────────────────────────────────────────────────────────────────

    fun list(res: AdminResource, q: String?, filters: Map<String, String>, limit: Int, offset: Int): JsonObject {
        val where = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>()
        q?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where += "CAST(${quote(res.titleField)} AS TEXT) ILIKE :q"
            params["q"] = "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        filters.forEach { (name, value) ->
            val field = res.field(name)?.takeIf { it.filter } ?: return@forEach
            where += "${quote(field.name)} = :f_${field.name}"
            params["f_${field.name}"] = value
        }
        val whereSql = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        val table = from(res)
        val total = jdbc.sql("SELECT count(*) FROM $table $whereSql").params(params).query(Long::class.java).single()
        val rows = jdbc.sql("SELECT * FROM $table $whereSql ORDER BY ${res.orderBy} LIMIT :limit OFFSET :offset")
            .params(params).param("limit", limit.coerceIn(1, 500)).param("offset", offset.coerceAtLeast(0))
            .query().listOfRows()
        return buildJsonObject {
            put("total", total)
            putJsonArray("items") { rows.forEach { add(rowJson(res, it)) } }
            put("refs", refLabels(res, rows))
        }
    }

    fun get(res: AdminResource, id: String): JsonObject {
        val row = findRow(res, id) ?: throw ApiException(404, "NOT_FOUND", "${res.label}: not found")
        return buildJsonObject {
            put("item", rowJson(res, row))
            put("refs", refLabels(res, listOf(row)))
        }
    }

    /** `{id, label}` pairs for a REF picker. */
    fun lookup(res: AdminResource, q: String?, limit: Int): JsonArray {
        val label = quote(res.titleField)
        val filter = q?.trim()?.takeIf { it.isNotEmpty() }
        val rows = jdbc.sql(
            "SELECT \"id\", CAST($label AS TEXT) AS \"label\" FROM ${quote(res.table)} " +
                (if (filter != null) "WHERE CAST($label AS TEXT) ILIKE :q " else "") +
                "ORDER BY ${res.orderBy} LIMIT :limit",
        ).apply { if (filter != null) param("q", "%$filter%") }
            .param("limit", limit.coerceIn(1, 200))
            .query().listOfRows()
        return JsonArray(rows.map { r -> buildJsonObject { put("id", r["id"].toString()); put("label", r["label"]?.toString() ?: "") } })
    }

    // ── Writes ────────────────────────────────────────────────────────────────

    @Transactional
    fun create(res: AdminResource, body: JsonObject, admin: AdminPrincipal): JsonObject {
        if (!res.canCreate) throw ApiException(400, "BAD_REQUEST", "${res.label} can't be created here.")
        val values = validate(res, body, creating = true).toMutableMap()
        // A new row goes to the end unless an order was typed in.
        res.sortField?.let { sort ->
            val given = body[sort]
            if (given == null || given is JsonNull || (given as? JsonPrimitive)?.content?.isBlank() == true) {
                values[sort] = jdbc.sql("SELECT COALESCE(max(${quote(sort)}), 0) + 10 FROM ${quote(res.table)}")
                    .query(Int::class.java).single()
            }
        }
        val now = nowMillis().toDbTime()
        val id = Cuid.next()
        val columns = linkedMapOf<String, Any?>("id" to id)
        columns.putAll(values)
        if (res.hasCreatedAt) columns["createdAt"] = now
        if (res.hasUpdatedAt) columns["updatedAt"] = now
        guarded {
            jdbc.sql(
                "INSERT INTO ${quote(res.table)} (${columns.keys.joinToString { quote(it) }}) VALUES (" +
                    columns.keys.joinToString { placeholder(res, it) } + ")",
            ).params(columns.mapKeys { "p_${it.key}" }).update()
        }
        audit.record(admin, "CREATE", res.key, id, values.keys)
        cache.invalidateAll()
        return get(res, id)
    }

    @Transactional
    fun update(res: AdminResource, id: String, body: JsonObject, admin: AdminPrincipal): JsonObject {
        if (findRow(res, id) == null) throw ApiException(404, "NOT_FOUND", "${res.label}: not found")
        val values = validate(res, body, creating = false)
        if (values.isEmpty()) return get(res, id)
        if (res == AdminResources.REGISTRATIONS) (values["bibNumber"] as? String)?.let { requireUniqueBib(id, it) }
        val columns = LinkedHashMap(values)
        if (res.hasUpdatedAt) columns["updatedAt"] = nowMillis().toDbTime()
        guarded {
            jdbc.sql(
                "UPDATE ${quote(res.table)} SET " +
                    columns.keys.joinToString { "${quote(it)} = ${placeholder(res, it)}" } +
                    " WHERE \"id\" = :id",
            ).params(columns.mapKeys { "p_${it.key}" }).param("id", idParam(res, id)).update()
        }
        audit.record(admin, "UPDATE", res.key, id, values.keys)
        cache.invalidateAll()
        return get(res, id)
    }

    @Transactional
    fun delete(res: AdminResource, id: String, admin: AdminPrincipal) {
        if (!res.canDelete) throw ApiException(400, "BAD_REQUEST", "${res.label} can't be deleted.")
        val deleted = guarded {
            jdbc.sql("DELETE FROM ${quote(res.table)} WHERE \"id\" = :id").param("id", idParam(res, id)).update()
        }
        if (deleted == 0) throw ApiException(404, "NOT_FOUND", "${res.label}: not found")
        audit.record(admin, "DELETE", res.key, id, emptySet())
        cache.invalidateAll()
    }

    /** Writes the sort column as 10, 20, 30… in the given order. */
    @Transactional
    fun reorder(res: AdminResource, ids: List<String>, admin: AdminPrincipal) {
        val sortField = res.sortField ?: throw ApiException(400, "BAD_REQUEST", "${res.label} can't be reordered.")
        if (ids.size > 1_000 || ids.toSet().size != ids.size) throw ApiException(400, "BAD_REQUEST", "Invalid order")
        val stamp = if (res.hasUpdatedAt) ", \"updatedAt\" = :now" else ""
        ids.forEachIndexed { i, id ->
            jdbc.sql("UPDATE ${quote(res.table)} SET ${quote(sortField)} = :n$stamp WHERE \"id\" = :id")
                .param("n", (i + 1) * 10).param("id", id)
                .apply { if (res.hasUpdatedAt) param("now", nowMillis().toDbTime()) }
                .update()
        }
        audit.record(admin, "REORDER", res.key, null, setOf(sortField))
        cache.invalidateAll()
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * The column values to write. Creating: every required field must be present, and absent
     * fields with a default get it. Updating: only the fields sent are written. Unknown keys are
     * ignored. Throws INVALID_BODY with every problem, keyed by field.
     */
    fun validate(res: AdminResource, body: JsonObject, creating: Boolean): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        val problems = linkedMapOf<String, MutableList<String>>()
        fun fail(field: String, message: String) {
            problems.getOrPut(field) { mutableListOf() } += message
        }

        for (field in res.fields) {
            if (field.readOnly) continue
            val present = body.containsKey(field.name)
            val raw: JsonElement? = when {
                present -> body[field.name]
                creating && field.default != null -> toJson(field.default)
                creating && field.type == FieldType.TAGS -> JsonArray(emptyList())
                else -> null
            }
            if (!present && raw == null) {
                if (creating && field.required) fail(field.name, "Required")
                continue
            }
            var value = convert(field, raw ?: JsonNull) { fail(field.name, it) }
            if (value == null && field.required) {
                fail(field.name, "Required")
                continue
            }
            // A cleared optional field with a default (most NOT NULL columns) goes back to it.
            if (value == null && field.default != null && problems[field.name] == null) {
                // An empty-string default (a NOT NULL text column) is written as-is.
                value = if (field.default == "") "" else convert(field, toJson(field.default)) { fail(field.name, it) }
            }
            if (value != null && field.type == FieldType.REF) {
                val target = AdminResources.of(field.ref!!)!!
                val exists = jdbc.sql("SELECT count(*) FROM ${quote(target.table)} WHERE \"id\" = :id")
                    .param("id", value).query(Long::class.java).single() > 0
                if (!exists) fail(field.name, "Not found")
            }
            out[field.name] = value
        }
        crossFieldChecks(res, out, problems)
        if (problems.isNotEmpty()) throw ApiException(400, "INVALID_BODY", "Please fix the highlighted fields.", problems)
        return out
    }

    private fun crossFieldChecks(res: AdminResource, values: Map<String, Any?>, problems: MutableMap<String, MutableList<String>>) {
        val starts = values["startsAt"] as? LocalDateTime
        val ends = values["endsAt"] as? LocalDateTime
        if (starts != null && ends != null && !ends.isAfter(starts)) {
            problems.getOrPut("endsAt") { mutableListOf() } += "Must be after the start"
        }
        if (res == AdminResources.SECTIONS) {
            val kind = values["kind"] as? String
            if (kind == "CATEGORY_RAIL" && values.containsKey("categoryId") && values["categoryId"] == null) {
                problems.getOrPut("categoryId") { mutableListOf() } += "A category rail needs a category"
            }
        }
    }

    /** JSON → the JDBC value for [field]; null for an explicit null / empty string. */
    private fun convert(field: AdminField, raw: JsonElement, fail: (String) -> Unit): Any? {
        if (raw is JsonNull) return null
        val prim = raw as? JsonPrimitive
        fun str(): String? = prim?.takeIf { it.isString }?.content?.trim()
        return when (field.type) {
            FieldType.TEXT, FieldType.LONGTEXT, FieldType.SELECT, FieldType.REF -> {
                val s = str() ?: return null.also { fail("Must be text") }
                if (s.isEmpty()) return null
                if (s.length > field.maxLength) return null.also { fail("At most ${field.maxLength} characters") }
                if (field.type == FieldType.SELECT && field.options.isNotEmpty() && field.options.none { it.value == s }) {
                    return null.also { fail("Pick one of the options") }
                }
                s
            }
            FieldType.URL, FieldType.IMAGE -> {
                val s = str() ?: return null.also { fail("Must be a link") }
                if (s.isEmpty()) return null
                if (!(s.startsWith("https://") || s.startsWith("http://")) || s.length > 2_000 || s.any { it.isWhitespace() }) {
                    return null.also { fail("Must be a full http(s):// link") }
                }
                s
            }
            FieldType.TIME -> {
                val s = str() ?: return null.also { fail("Must be HH:mm") }
                if (s.isEmpty()) return null
                if (!TIME_RE.matches(s)) return null.also { fail("Use 24-hour HH:mm, e.g. 06:30") }
                s
            }
            FieldType.INT, FieldType.PAISE -> {
                if (prim != null && prim.isString && prim.content.isBlank()) return null
                val d = numberOf(prim) ?: return null.also { fail("Must be a whole number") }
                if (d != Math.floor(d) || d.isInfinite()) return null.also { fail("Must be a whole number") }
                if (field.min != null && d < field.min) return null.also { fail("At least ${field.min.toLong()}") }
                if (field.max != null && d > field.max) return null.also { fail("At most ${field.max.toLong()}") }
                if (d > Int.MAX_VALUE) return null.also { fail("Too large") }
                d.toInt()
            }
            FieldType.FLOAT -> {
                if (prim != null && prim.isString && prim.content.isBlank()) return null
                val d = numberOf(prim) ?: return null.also { fail("Must be a number") }
                if (d.isNaN() || d.isInfinite()) return null.also { fail("Must be a number") }
                if (field.min != null && d < field.min) return null.also { fail("At least ${field.min}") }
                if (field.max != null && d > field.max) return null.also { fail("At most ${field.max}") }
                d
            }
            FieldType.BOOL -> prim?.takeIf { !it.isString }?.booleanOrNull ?: null.also { fail("Must be yes or no") }
            FieldType.DATETIME -> {
                val s = str() ?: return null.also { fail("Must be a date and time") }
                if (s.isEmpty()) return null
                val instant = runCatching { Instant.parse(s) }.getOrNull()
                    ?: return null.also { fail("Must be a date and time") }
                LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
            }
            FieldType.TAGS -> {
                val arr = raw as? JsonArray ?: return null.also { fail("Must be a list") }
                val items = arr.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
                if (items.any { it == null }) return null.also { fail("Must be a list of text") }
                val clean = items.filterNotNull().filter { it.isNotEmpty() }
                if (clean.size > 50 || clean.any { it.length > 120 }) return null.also { fail("At most 50 items of 120 characters") }
                clean.toTypedArray()
            }
            FieldType.JSON -> {
                if (raw !is JsonObject) return null.also { fail("Must be a JSON object") }
                val text = ServerJson.encodeToString(JsonElement.serializer(), raw)
                if (text.length > 10_000) return null.also { fail("Too large") }
                text
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** A bib number belongs to one runner per edition. */
    private fun requireUniqueBib(registrationId: String, bib: String) {
        val taken = jdbc.sql(
            """SELECT count(*) FROM "MarathonRegistration" o
               WHERE o."bibNumber" = :bib AND o."id" <> :id
                 AND o."eventId" = (SELECT r."eventId" FROM "MarathonRegistration" r WHERE r."id" = :id)""",
        ).param("bib", bib).param("id", registrationId).query(Long::class.java).single() > 0
        if (taken) throw ApiException(409, "DUPLICATE", "That bib number is already given to another runner in this edition.")
    }

    /** A JSON number, or a string holding one (form inputs send text). */
    private fun numberOf(prim: JsonPrimitive?): Double? = when {
        prim == null -> null
        !prim.isString -> prim.doubleOrNull
        else -> prim.content.trim().toDoubleOrNull()
    }

    private fun placeholder(res: AdminResource, column: String): String =
        if (res.field(column)?.type == FieldType.JSON) "CAST(:p_$column AS jsonb)" else ":p_$column"

    /** AppConfig's id is the integer 1; everything else is a cuid. */
    private fun idParam(res: AdminResource, id: String): Any =
        if (res.singleton) id.toIntOrNull() ?: throw ApiException(404, "NOT_FOUND", "${res.label}: not found") else id

    private fun findRow(res: AdminResource, id: String): Map<String, Any?>? =
        jdbc.sql("SELECT * FROM ${from(res)} WHERE \"id\" = :id").param("id", idParam(res, id))
            .query().listOfRows().firstOrNull()

    /** The table, or the resource's joined [AdminResource.source] as a subquery. */
    private fun from(res: AdminResource): String = res.source?.let { "($it) AS \"src\"" } ?: quote(res.table)

    /** Only declared columns (plus id and timestamps) ever leave the server. */
    private fun rowJson(res: AdminResource, row: Map<String, Any?>): JsonObject = buildJsonObject {
        put("id", row["id"].toString())
        for (f in res.fields) put(f.name, toJson(row[f.name]))
        if (res.hasCreatedAt) put("createdAt", toJson(row["createdAt"]))
        if (res.hasUpdatedAt) put("updatedAt", toJson(row["updatedAt"]))
    }

    /** `{field: {id: label}}` for the REF columns of [rows], so lists show names, not ids. */
    private fun refLabels(res: AdminResource, rows: List<Map<String, Any?>>): JsonObject = buildJsonObject {
        for (f in res.fields.filter { it.type == FieldType.REF }) {
            val ids = rows.mapNotNull { it[f.name]?.toString() }.distinct()
            if (ids.isEmpty()) continue
            val target = AdminResources.of(f.ref!!)!!
            val labels = jdbc.sql(
                "SELECT \"id\", CAST(${quote(target.titleField)} AS TEXT) AS \"label\" FROM ${quote(target.table)} WHERE \"id\" IN (:ids)",
            ).param("ids", ids).query().listOfRows()
            put(f.name, buildJsonObject { labels.forEach { put(it["id"].toString(), it["label"]?.toString() ?: "") } })
        }
    }

    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: DataIntegrityViolationException) {
        val state = (e.mostSpecificCause as? java.sql.SQLException)?.sqlState
        when (state) {
            "23505" -> throw ApiException(409, "DUPLICATE", "That value is already used by another entry.")
            "23502" -> throw ApiException(400, "INVALID_BODY", "A required value is missing.")
            "23503" -> throw ApiException(
                409, "IN_USE",
                "Other content still uses this (or it points to something missing). Remove or reassign that first.",
            )
            else -> throw e
        }
    }

    companion object {
        private val TIME_RE = Regex("([01]\\d|2[0-3]):[0-5]\\d")

        /** A JDBC value (or a declared default) → JSON, with timestamps as ISO strings. */
        fun toJson(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is String -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is BigDecimal -> JsonPrimitive(v.toDouble())
            is Number -> JsonPrimitive(v)
            is java.sql.Timestamp -> JsonPrimitive(v.toLocalDateTime().toInstant(ZoneOffset.UTC).toIsoString())
            is LocalDateTime -> JsonPrimitive(v.toInstant(ZoneOffset.UTC).toIsoString())
            is java.sql.Date -> JsonPrimitive(v.toLocalDate().toString())
            is java.sql.Array -> JsonArray((v.array as Array<*>).map { toJson(it) })
            is Array<*> -> JsonArray(v.map { toJson(it) })
            is List<*> -> JsonArray(v.map { toJson(it) })
            is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) })
            // jsonb arrives as org.postgresql.util.PGobject, whose toString() is the JSON text.
            else -> if (v.javaClass.simpleName == "PGobject") ServerJson.parseToJsonElement(v.toString()) else JsonPrimitive(v.toString())
        }
    }
}
