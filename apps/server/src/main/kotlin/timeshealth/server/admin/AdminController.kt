package timeshealth.server.admin

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import timeshealth.app.core.domain.IST
import timeshealth.server.cms.ContentCache
import timeshealth.server.cms.SectionKind
import timeshealth.server.db.Cuid
import timeshealth.server.db.toDbTime
import timeshealth.server.error.ApiException
import timeshealth.server.json.RawBody
import timeshealth.server.json.nowMillis

/**
 * The dashboard's JSON API (/admin/api). Signed-in admins only (AdminApiInterceptor); writes
 * need an editing role. The dashboard page itself is static (resources/static/admin).
 */
@RestController
@RequestMapping("/admin/api")
class AdminController(
    private val crud: AdminCrud,
    private val authenticator: AdminAuthenticator,
    private val sessions: AdminSessions,
    private val throttle: AdminLoginThrottle,
    private val users: AdminUsers,
    private val audit: AdminAudit,
    private val jdbc: JdbcClient,
    private val cache: ContentCache,
) {

    // ── Session ───────────────────────────────────────────────────────────────

    /** Not behind the interceptor. */
    @PostMapping("/login")
    fun login(body: RawBody, request: HttpServletRequest, response: HttpServletResponse): JsonObject {
        val obj = body.value as? JsonObject ?: throw ApiException(400, "INVALID_BODY", "Email and password required")
        val email = obj.str("email").orEmpty()
        val password = (obj["password"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        if (email.isBlank() || password.isEmpty()) throw ApiException(400, "INVALID_BODY", "Email and password required")
        if (!throttle.allow(request, email)) {
            throw ApiException(429, "TOO_MANY_REQUESTS", "Too many sign-in attempts. Wait a few minutes and try again.")
        }
        val principal = authenticator.authenticate(email, password)
        if (principal == null) {
            throttle.recordFailure(request, email)
            throw ApiException(401, "UNAUTHORIZED", "Wrong email or password.")
        }
        sessions.issue(principal, response)
        users.touchLogin(principal.id)
        audit.record(principal, "LOGIN", "session", principal.id, emptySet())
        return me(principal)
    }

    @PostMapping("/logout")
    fun logout(response: HttpServletResponse): JsonObject {
        sessions.clear(response)
        return buildJsonObject { put("ok", true) }
    }

    @GetMapping("/me")
    fun me(request: HttpServletRequest): JsonObject = me(AdminApiInterceptor.principal(request))

    private fun me(p: AdminPrincipal) = buildJsonObject {
        put("id", p.id)
        put("email", p.email)
        put("name", p.name)
        put("role", p.role.name)
        put("canEdit", p.role.canEditContent)
        put("canManageAdmins", p.role.canManageAdmins)
    }

    // ── Schema for the dashboard ──────────────────────────────────────────────

    @GetMapping("/meta")
    fun meta(request: HttpServletRequest): JsonObject {
        val p = AdminApiInterceptor.principal(request)
        return buildJsonObject {
            putJsonArray("resources") {
                AdminResources.ALL.filter { !it.ownerOnly || p.role.canManageAdmins }.forEach { r ->
                    add(
                        buildJsonObject {
                            put("key", r.key)
                            put("label", r.label)
                            put("group", r.group)
                            put("description", r.description)
                            put("titleField", r.titleField)
                            put("sortable", r.sortField != null)
                            r.sortField?.let { put("sortField", it) }
                            put("singleton", r.singleton)
                            put("canCreate", r.canCreate)
                            put("canDelete", r.canDelete)
                            putJsonArray("fields") {
                                r.fields.forEach { f ->
                                    add(
                                        buildJsonObject {
                                            put("name", f.name)
                                            put("label", f.label)
                                            put("type", f.type.name)
                                            put("required", f.required)
                                            put("list", f.list)
                                            put("filter", f.filter)
                                            f.help?.let { put("help", it) }
                                            f.ref?.let { put("ref", it) }
                                            f.min?.let { put("min", it) }
                                            f.max?.let { put("max", it) }
                                            put("maxLength", f.maxLength)
                                            put("default", AdminCrud.toJson(f.default))
                                            putJsonArray("options") {
                                                f.options.forEach { o -> add(buildJsonObject { put("value", o.value); put("label", o.label) }) }
                                            }
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
            putJsonArray("sectionKinds") {
                SectionKind.entries.forEach { k ->
                    add(
                        buildJsonObject {
                            put("value", k.name)
                            put("label", k.label)
                            put("help", k.help)
                            put("singleton", k.singleton)
                            put("usesCategory", k.usesCategory)
                            k.itemType?.let { put("itemType", it) }
                        },
                    )
                }
            }
        }
    }

    // ── Generic resources ─────────────────────────────────────────────────────

    @GetMapping("/r/{key}")
    fun list(
        @PathVariable("key") key: String,
        @RequestParam(name = "q", required = false) q: String?,
        @RequestParam(name = "limit", defaultValue = "100") limit: Int,
        @RequestParam(name = "offset", defaultValue = "0") offset: Int,
        request: HttpServletRequest,
    ): JsonObject {
        val res = resource(key, request)
        val filters = request.parameterMap.filterKeys { it.startsWith("f.") }
            .mapKeys { it.key.removePrefix("f.") }
            .mapValues { it.value.first() }
        if (res.singleton) ensureSingleton(res)
        return crud.list(res, q, filters, limit, offset)
    }

    @GetMapping("/r/{key}/{id}")
    fun get(@PathVariable("key") key: String, @PathVariable("id") id: String, request: HttpServletRequest): JsonObject =
        crud.get(resource(key, request), id)

    @PostMapping("/r/{key}")
    fun create(@PathVariable("key") key: String, body: RawBody, request: HttpServletRequest): JsonObject =
        crud.create(resource(key, request), body.obj(), AdminApiInterceptor.principal(request))

    @PatchMapping("/r/{key}/{id}")
    fun update(@PathVariable("key") key: String, @PathVariable("id") id: String, body: RawBody, request: HttpServletRequest): JsonObject =
        crud.update(resource(key, request), id, body.obj(), AdminApiInterceptor.principal(request))

    @DeleteMapping("/r/{key}/{id}")
    fun delete(@PathVariable("key") key: String, @PathVariable("id") id: String, request: HttpServletRequest): JsonObject {
        crud.delete(resource(key, request), id, AdminApiInterceptor.principal(request))
        return buildJsonObject { put("ok", true) }
    }

    @PostMapping("/r/{key}/reorder")
    fun reorder(@PathVariable("key") key: String, body: RawBody, request: HttpServletRequest): JsonObject {
        val ids = body.obj()["ids"]?.let { it as? JsonArray }?.map { (it as? JsonPrimitive)?.content ?: "" }
            ?: throw ApiException(400, "INVALID_BODY", "ids required")
        crud.reorder(resource(key, request), ids, AdminApiInterceptor.principal(request))
        return buildJsonObject { put("ok", true) }
    }

    @GetMapping("/lookup/{key}")
    fun lookup(
        @PathVariable("key") key: String,
        @RequestParam(name = "q", required = false) q: String?,
        request: HttpServletRequest,
    ): JsonArray = crud.lookup(resource(key, request), q, 100)

    // ── Hand-picked section items ─────────────────────────────────────────────

    @GetMapping("/sections/{id}/items")
    fun sectionItems(@PathVariable("id") id: String): JsonObject {
        val items = jdbc.sql(
            """SELECT i."refType", i."refId", s."title" AS "label"
               FROM "FeedSectionItem" i LEFT JOIN "YogaSession" s ON i."refType" = 'SESSION' AND s."id" = i."refId"
               WHERE i."sectionId" = :id ORDER BY i."sortOrder", i."id"""",
        ).param("id", id).query().listOfRows()
        return buildJsonObject {
            putJsonArray("items") {
                items.forEach { r ->
                    add(
                        buildJsonObject {
                            put("refType", r["refType"].toString())
                            put("refId", r["refId"].toString())
                            put("label", r["label"]?.toString() ?: "(deleted)")
                        },
                    )
                }
            }
        }
    }

    /** Replaces a section's hand-picked items with `{items: [{refType, refId}]}`, in that order. */
    @PutMapping("/sections/{id}/items")
    @Transactional
    fun setSectionItems(@PathVariable("id") id: String, body: RawBody, request: HttpServletRequest): JsonObject {
        val admin = AdminApiInterceptor.principal(request)
        val kind = jdbc.sql("""SELECT "kind" FROM "FeedSection" WHERE "id" = :id""").param("id", id)
            .query(String::class.java).optional().orElse(null)
            ?: throw ApiException(404, "NOT_FOUND", "Section not found")
        val itemType = SectionKind.of(kind)?.itemType
            ?: throw ApiException(400, "BAD_REQUEST", "This section type has no hand-picked items.")
        val refs = (body.obj()["items"] as? JsonArray ?: throw ApiException(400, "INVALID_BODY", "items required"))
            .map { el ->
                val o = el as? JsonObject ?: throw ApiException(400, "INVALID_BODY", "Invalid item")
                val type = o.str("refType") ?: itemType
                val refId = o.str("refId") ?: throw ApiException(400, "INVALID_BODY", "refId required")
                if (type != itemType) throw ApiException(400, "INVALID_BODY", "This section takes $itemType items only")
                refId
            }
        if (refs.size > 50 || refs.toSet().size != refs.size) throw ApiException(400, "INVALID_BODY", "Up to 50 different items")
        if (refs.isNotEmpty()) {
            val found = jdbc.sql("""SELECT count(*) FROM "YogaSession" WHERE "id" IN (:ids)""").param("ids", refs)
                .query(Long::class.java).single()
            if (found != refs.size.toLong()) throw ApiException(400, "INVALID_BODY", "Some videos no longer exist")
        }
        jdbc.sql("""DELETE FROM "FeedSectionItem" WHERE "sectionId" = :id""").param("id", id).update()
        refs.forEachIndexed { i, refId ->
            jdbc.sql(
                """INSERT INTO "FeedSectionItem" ("id", "sectionId", "refType", "refId", "sortOrder")
                   VALUES (:pk, :sid, :type, :ref, :n)""",
            ).param("pk", Cuid.next()).param("sid", id).param("type", itemType).param("ref", refId).param("n", (i + 1) * 10).update()
        }
        audit.record(admin, "UPDATE", "section-items", id, setOf("items"))
        cache.invalidateAll()
        return sectionItems(id)
    }

    // ── Live classes: schedule a run of days in one go ────────────────────────

    /**
     * Creates one live class per day at the batch's time:
     * `{batchId, fromDate: "2026-10-07", days: 7, title?, videoProvider, videoRef, isFree?, durationMinutes?}`.
     * Days that already have a class for the batch are skipped (so re-running is safe).
     */
    @PostMapping("/live-classes/schedule")
    @Transactional
    fun scheduleLiveClasses(body: RawBody, request: HttpServletRequest): JsonObject {
        val admin = AdminApiInterceptor.principal(request)
        val o = body.obj()
        val problems = linkedMapOf<String, List<String>>()
        val batchId = o.str("batchId")
        val batch = batchId?.let {
            jdbc.sql("""SELECT "title", "time", "instructorId" FROM "YogaBatch" WHERE "id" = :id""").param("id", it)
                .query().listOfRows().firstOrNull()
        }
        if (batch == null) problems["batchId"] = listOf("Pick a batch")
        val from = try {
            o.str("fromDate")?.let(LocalDate::parse)
        } catch (e: DateTimeParseException) {
            null
        }
        if (from == null) problems["fromDate"] = listOf("Pick a start date")
        val days = (o["days"] as? JsonPrimitive)?.intOrNull ?: (o["days"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (days == null || days !in 1..62) problems["days"] = listOf("1 to 62 days")
        val provider = o.str("videoProvider") ?: "url"
        if (provider !in listOf("url", "slike")) problems["videoProvider"] = listOf("Pick a video source")
        val ref = o.str("videoRef")
        if (ref.isNullOrBlank()) problems["videoRef"] = listOf("Required")
        val minutes = (o["durationMinutes"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 60
        if (minutes !in 5..300) problems["durationMinutes"] = listOf("5 to 300 minutes")
        if (problems.isNotEmpty()) throw ApiException(400, "INVALID_BODY", "Please fix the highlighted fields.", problems)

        val time = LocalTime.parse(batch!!["time"].toString())
        val title = o.str("title") ?: batch["title"].toString()
        val isFree = (o["isFree"] as? JsonPrimitive)?.booleanOrNull ?: false
        val now = nowMillis().toDbTime()
        var created = 0
        for (d in 0 until days!!) {
            val startsAt = from!!.plusDays(d.toLong()).atTime(time).atOffset(IST).toInstant()
            val exists = jdbc.sql(
                """SELECT count(*) FROM "LiveClass" WHERE "batchId" = :b AND "startsAt" = :s""",
            ).param("b", batchId).param("s", startsAt.toDbTime()).query(Long::class.java).single() > 0
            if (exists) continue
            jdbc.sql(
                """INSERT INTO "LiveClass" ("id", "title", "description", "startsAt", "durationMinutes", "isFree",
                       "videoProvider", "videoRef", "status", "instructorId", "batchId", "createdAt", "updatedAt")
                   VALUES (:id, :title, '', :startsAt, :minutes, :free, :provider, :ref, 'SCHEDULED', :instructor, :batch, :now, :now)""",
            ).param("id", Cuid.next()).param("title", title).param("startsAt", startsAt.toDbTime())
                .param("minutes", minutes).param("free", isFree).param("provider", provider).param("ref", ref!!.trim())
                .param("instructor", batch["instructorId"]?.toString()).param("batch", batchId).param("now", now)
                .update()
            created++
        }
        audit.record(admin, "CREATE", "live-classes", batchId, setOf("schedule:$created"))
        cache.invalidateAll()
        return buildJsonObject {
            put("created", created)
            put("skipped", days!! - created)
        }
    }

    // ── Dashboard users (owners only) ─────────────────────────────────────────

    @GetMapping("/admins")
    fun admins(request: HttpServletRequest): JsonArray {
        requireOwner(request)
        val rows = jdbc.sql("""SELECT "id", "email", "name", "role", "active", "lastLoginAt", "createdAt" FROM "AdminUser" ORDER BY "createdAt"""")
            .query().listOfRows()
        return buildJsonArray { rows.forEach { r -> add(JsonObject(r.mapValues { AdminCrud.toJson(it.value) })) } }
    }

    @PostMapping("/admins")
    fun createAdmin(body: RawBody, request: HttpServletRequest): JsonObject {
        val owner = requireOwner(request)
        val o = body.obj()
        val email = o.str("email")?.lowercase()
        val name = o.str("name")
        val role = AdminRole.of(o.str("role"))
        val password = (o["password"] as? JsonPrimitive)?.content.orEmpty()
        val problems = linkedMapOf<String, List<String>>()
        if (email == null || !email.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) || email.length > 160) problems["email"] = listOf("A valid email")
        if (name.isNullOrBlank() || name.length > 80) problems["name"] = listOf("Required, up to 80 characters")
        if (role == null) problems["role"] = listOf("Pick a role")
        AdminUsers.passwordProblem(password)?.let { problems["password"] = listOf(it) }
        if (problems.isNotEmpty()) throw ApiException(400, "INVALID_BODY", "Please fix the highlighted fields.", problems)
        val taken = jdbc.sql("""SELECT count(*) FROM "AdminUser" WHERE lower("email") = :e""").param("e", email)
            .query(Long::class.java).single() > 0
        if (taken) throw ApiException(409, "DUPLICATE", "An admin with that email already exists.")
        val id = users.create(email!!, name!!, role!!, password)
        audit.record(owner, "CREATE", "admins", id, setOf("email", "name", "role"))
        return buildJsonObject { put("id", id) }
    }

    /** `{role?, active?, password?}`. An owner can't demote or deactivate themselves (no lock-out). */
    @PatchMapping("/admins/{id}")
    fun updateAdmin(@PathVariable("id") id: String, body: RawBody, request: HttpServletRequest): JsonObject {
        val owner = requireOwner(request)
        val o = body.obj()
        val changed = mutableSetOf<String>()
        o["role"]?.let { el ->
            val role = AdminRole.of((el as? JsonPrimitive)?.content) ?: throw ApiException(400, "INVALID_BODY", "Unknown role")
            if (id == owner.id && role != AdminRole.OWNER) throw ApiException(400, "BAD_REQUEST", "You can't remove your own owner role.")
            jdbc.sql("""UPDATE "AdminUser" SET "role" = :r, "updatedAt" = :now WHERE "id" = :id""")
                .param("r", role.name).param("now", nowMillis().toDbTime()).param("id", id).update()
            changed += "role"
        }
        o["active"]?.let { el ->
            val active = (el as? JsonPrimitive)?.booleanOrNull ?: throw ApiException(400, "INVALID_BODY", "active must be true or false")
            if (id == owner.id && !active) throw ApiException(400, "BAD_REQUEST", "You can't deactivate yourself.")
            jdbc.sql("""UPDATE "AdminUser" SET "active" = :a, "updatedAt" = :now WHERE "id" = :id""")
                .param("a", active).param("now", nowMillis().toDbTime()).param("id", id).update()
            changed += "active"
        }
        o["password"]?.let { el ->
            val pw = (el as? JsonPrimitive)?.content.orEmpty()
            AdminUsers.passwordProblem(pw)?.let { throw ApiException(400, "INVALID_BODY", it, mapOf("password" to listOf(it))) }
            jdbc.sql("""UPDATE "AdminUser" SET "passwordHash" = :h, "updatedAt" = :now WHERE "id" = :id""")
                .param("h", users.hash(pw)).param("now", nowMillis().toDbTime()).param("id", id).update()
            changed += "password"
        }
        audit.record(owner, "UPDATE", "admins", id, changed)
        return buildJsonObject { put("ok", true) }
    }

    @GetMapping("/audit")
    fun auditLog(
        @RequestParam(name = "limit", defaultValue = "100") limit: Int,
        @RequestParam(name = "resource", required = false) resource: String?,
    ): JsonArray = audit.recent(limit, resource)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resource(key: String, request: HttpServletRequest): AdminResource {
        val res = AdminResources.of(key) ?: throw ApiException(404, "NOT_FOUND", "Unknown resource")
        if (res.ownerOnly) requireOwner(request)
        return res
    }

    private fun requireOwner(request: HttpServletRequest): AdminPrincipal {
        val p = AdminApiInterceptor.principal(request)
        if (!p.role.canManageAdmins) throw ApiException(403, "FORBIDDEN", "Only owners can do that.")
        return p
    }

    /** AppConfig is a single row that may not exist yet. */
    private fun ensureSingleton(res: AdminResource) {
        if (res.table == "AppConfig") {
            jdbc.sql(
                """INSERT INTO "AppConfig" ("id", "minSupportedAppVersion", "maintenanceActive", "updatedAt")
                   VALUES (1, '1.0.0', false, :now) ON CONFLICT ("id") DO NOTHING""",
            ).param("now", nowMillis().toDbTime()).update()
        }
    }

    private fun RawBody.obj(): JsonObject = value as? JsonObject ?: throw ApiException(400, "INVALID_BODY", "Expected a JSON object")

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

}
