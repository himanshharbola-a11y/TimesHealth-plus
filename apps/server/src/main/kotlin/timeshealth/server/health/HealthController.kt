package timeshealth.server.health

import java.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import timeshealth.server.json.ServerJson
import timeshealth.server.json.toIsoString

/** Not part of the shared app contract: only load balancers read it. */
@Serializable
data class HealthOk(val ok: Boolean = true, val service: String = SERVICE, val time: String)

@Serializable
data class HealthDown(val ok: Boolean = false, val service: String = SERVICE, val db: String = "unreachable")

/** The same name as the Node server, so existing health checks keep matching. */
const val SERVICE = "timeshealth-api"

/**
 * GET /health (app.ts). Reports the database too, so a load balancer stops routing traffic to an
 * instance that cannot reach it. Answers 503 rather than throwing. Public; rate-limited like
 * every route.
 */
@RestController
class HealthController(private val jdbc: JdbcClient, private val clock: Clock) {
    @GetMapping("/health")
    fun health(): ResponseEntity<JsonElement> = try {
        jdbc.sql("SELECT 1").query(Int::class.java).single()
        ResponseEntity.ok(ServerJson.encodeToJsonElement(HealthOk.serializer(), HealthOk(time = clock.instant().toIsoString())))
    } catch (e: Exception) {
        ResponseEntity.status(503).body(ServerJson.encodeToJsonElement(HealthDown.serializer(), HealthDown()))
    }
}
