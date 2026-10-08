package timeshealth.server.runs

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import timeshealth.app.core.model.RunRecord
import timeshealth.server.db.entity.UserEntity
import timeshealth.server.db.requireInstant
import timeshealth.server.error.ApiException
import timeshealth.server.json.toIsoString
import timeshealth.server.security.CurrentUser

/**
 * One past run WITH its route, for the app's run map. The history list
 * (GET /runs, still on Node) leaves routes out because each can be ~200 KB.
 * A route is private: only its owner gets it (docs/04 T7); anyone else gets
 * the same 404 as a run that doesn't exist.
 */
@RestController
@RequestMapping("/v1")
class RunsController(private val jdbc: JdbcClient) {

    @GetMapping("/runs/{id}")
    fun run(@PathVariable("id") id: String, @CurrentUser user: UserEntity): RunRecord =
        jdbc.sql("""SELECT * FROM "RunRecord" WHERE "id" = :id AND "userId" = :u""")
            .param("id", id).param("u", user.id)
            .query { rs, _ ->
                RunRecord(
                    id = rs.getString("id"),
                    startedAt = rs.requireInstant("startedAt").toIsoString(),
                    endedAt = rs.requireInstant("endedAt").toIsoString(),
                    distanceKm = rs.getDouble("distanceKm"),
                    durationSeconds = rs.getInt("durationSeconds"),
                    avgPaceSecPerKm = rs.getInt("avgPaceSecPerKm"),
                    caloriesBurned = rs.getInt("caloriesBurned"),
                    routePolyline = rs.getString("routePolyline"),
                    hasAccuracyWarning = rs.getBoolean("hasAccuracyWarning"),
                    synced = true,
                )
            }
            .optional().orElseThrow { ApiException(404, "NOT_FOUND", "Run not found") }
}
