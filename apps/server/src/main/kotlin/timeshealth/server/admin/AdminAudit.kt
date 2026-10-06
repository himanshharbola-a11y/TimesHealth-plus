package timeshealth.server.admin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import timeshealth.server.db.Cuid
import timeshealth.server.db.toDbTime
import timeshealth.server.json.ServerJson
import timeshealth.server.json.nowMillis

/** Who changed what in the dashboard, and when (the AdminAuditLog table). */
@Component
class AdminAudit(private val jdbc: JdbcClient) {

    /** [fields]: the names of the fields written (never their values: no PII in the log). */
    fun record(admin: AdminPrincipal, action: String, resource: String, resourceId: String?, fields: Collection<String>) {
        val summary = buildJsonObject { put("fields", JsonArray(fields.map { JsonPrimitive(it) })) }
        jdbc.sql(
            """INSERT INTO "AdminAuditLog" ("id", "adminId", "adminEmail", "action", "resource", "resourceId", "summary", "createdAt")
               VALUES (:id, :adminId, :email, :action, :resource, :resourceId, CAST(:summary AS jsonb), :now)""",
        ).param("id", Cuid.next())
            .param("adminId", admin.id)
            .param("email", admin.email)
            .param("action", action)
            .param("resource", resource)
            .param("resourceId", resourceId)
            .param("summary", ServerJson.encodeToString(JsonElement.serializer(), summary))
            .param("now", nowMillis().toDbTime())
            .update()
    }

    fun recent(limit: Int, resource: String?): JsonArray {
        val rows = jdbc.sql(
            """SELECT * FROM "AdminAuditLog" ${if (resource != null) """WHERE "resource" = :r""" else ""}
               ORDER BY "createdAt" DESC LIMIT :limit""",
        ).apply { if (resource != null) param("r", resource) }
            .param("limit", limit.coerceIn(1, 500))
            .query().listOfRows()
        return JsonArray(
            rows.map { r ->
                buildJsonObject {
                    listOf("id", "adminEmail", "action", "resource", "resourceId", "summary", "createdAt").forEach {
                        put(it, AdminCrud.toJson(r[it]))
                    }
                }
            },
        )
    }
}
