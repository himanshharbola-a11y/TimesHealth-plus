package timeshealth.server.admin

import java.security.SecureRandom
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import timeshealth.app.core.domain.isValidEmail
import timeshealth.app.core.domain.normalizeEmail
import timeshealth.app.core.domain.normalizePhone
import timeshealth.server.cms.ContentCache
import timeshealth.server.db.Cuid
import timeshealth.server.db.toDbTime
import timeshealth.server.error.ApiException
import timeshealth.server.json.nowMillis

/**
 * Race-operations actions for the dashboard: what organisers do around an
 * edition that isn't plain editing.
 *
 * - [allocateBibs]: bib numbers for everyone registered without one, so their
 *   digital pass (QR) appears in the app.
 * - [issuePass]: a complimentary registration (sponsors, pacers, staff) for an
 *   existing app user, exactly like a paid one minus the order.
 * - [uploadResults]: the timing partner's CSV, matched by bib. Publishing makes
 *   results visible in the app; the scheduler then sends "Your result is in".
 */
@Service
class MarathonOps(
    private val jdbc: JdbcClient,
    private val audit: AdminAudit,
    private val cache: ContentCache,
) {
    private val random = SecureRandom()

    data class AllocateResult(val allocated: Int)

    /**
     * Gives every registration of [eventId] without a bib the next free number,
     * `PREFIX-DISTANCE-0001`, in registration order. Existing bibs are never changed.
     */
    @Transactional
    fun allocateBibs(eventId: String, prefix: String?, admin: AdminPrincipal): AllocateResult {
        val event = jdbc.sql("""SELECT "city" FROM "MarathonEvent" WHERE "id" = :id""").param("id", eventId)
            .query(String::class.java).optional().orElse(null) ?: throw ApiException(404, "NOT_FOUND", "Edition not found")
        val code = (prefix?.trim()?.uppercase()?.takeIf { it.matches(Regex("[A-Z0-9]{1,6}")) }
            ?: event.filter { it.isLetter() }.take(3).uppercase().ifEmpty { "TH" })

        // Serialise allocations for this edition (two admins clicking at once).
        jdbc.sql("""SELECT 1 FROM "MarathonEvent" WHERE "id" = :id FOR UPDATE""").param("id", eventId).query().listOfRows()
        val taken = jdbc.sql("""SELECT "bibNumber" FROM "MarathonRegistration" WHERE "eventId" = :id AND "bibNumber" IS NOT NULL""")
            .param("id", eventId).query(String::class.java).list().toMutableSet()
        val pending = jdbc.sql(
            """SELECT "id", "category" FROM "MarathonRegistration"
               WHERE "eventId" = :id AND "bibNumber" IS NULL ORDER BY "registeredAt", "id"""",
        ).param("id", eventId).query { rs, _ -> rs.getString("id") to rs.getString("category") }.list()

        val next = mutableMapOf<String, Int>()
        for ((regId, category) in pending) {
            var n = next[category] ?: 1
            var bib: String
            do {
                bib = "$code-$category-${n.toString().padStart(4, '0')}"
                n++
            } while (bib in taken)
            next[category] = n
            taken += bib
            jdbc.sql("""UPDATE "MarathonRegistration" SET "bibNumber" = :bib WHERE "id" = :id""").param("bib", bib).param("id", regId).update()
        }
        audit.record(admin, "UPDATE", "registrations", eventId, setOf("allocate-bibs:${pending.size}"))
        cache.invalidateAll()
        return AllocateResult(pending.size)
    }

    data class PassResult(val registrationRef: String, val runner: String)

    /**
     * A complimentary registration for the app user with [contact] (their sign-in
     * email or mobile). They must have signed in to the app once, so the pass
     * lands on their account.
     */
    @Transactional
    fun issuePass(eventId: String, contact: String, category: String, tier: String, admin: AdminPrincipal): PassResult {
        val problems = linkedMapOf<String, List<String>>()
        val email = normalizeEmail(contact)?.takeIf { isValidEmail(it) }
        val phone = if (email == null) normalizePhone(contact) else null
        if (email == null && phone == null) problems["contact"] = listOf("An email or a 10-digit mobile number")
        if (tier !in setOf("CLASSIC", "PREMIUM")) problems["tier"] = listOf("Classic or Premium")
        val distance = jdbc.sql("""SELECT count(*) FROM "RaceDistanceOption" WHERE "eventId" = :e AND "code" = :c""")
            .param("e", eventId).param("c", category).query(Long::class.java).single() > 0
        if (!distance) problems["category"] = listOf("Pick one of this edition's distances")
        if (problems.isNotEmpty()) throw ApiException(400, "INVALID_BODY", "Please fix the highlighted fields.", problems)

        data class Runner(val id: String, val name: String?)
        val runner = jdbc.sql(
            """SELECT "id", "name" FROM "User"
               WHERE (CAST(:email AS TEXT) IS NOT NULL AND (lower("email") = :email OR lower("contactEmail") = :email))
                  OR (CAST(:phone AS TEXT) IS NOT NULL AND ("phone" = :phone OR "contactPhone" = :phone))
               ORDER BY "createdAt" LIMIT 1""",
        ).param("email", email).param("phone", phone).query { rs, _ -> Runner(rs.getString("id"), rs.getString("name")) }
            .optional().orElse(null)
            ?: throw ApiException(404, "NO_SUCH_USER", "No app account uses that email or number. Ask them to sign in to the app once, then try again.")

        val already = jdbc.sql("""SELECT count(*) FROM "MarathonRegistration" WHERE "userId" = :u AND "eventId" = :e""")
            .param("u", runner.id).param("e", eventId).query(Long::class.java).single() > 0
        if (already) throw ApiException(409, "ALREADY_REGISTERED", "They're already registered for this edition.")

        val ref = "TH-C" + (1..7).map { REF_ALPHABET[random.nextInt(REF_ALPHABET.length)] }.joinToString("")
        jdbc.sql(
            """INSERT INTO "MarathonRegistration" ("id", "userId", "eventId", "registrationRef", "tier", "category", "bibNumber", "status", "registeredAt")
               VALUES (:id, :u, :e, :ref, :tier, :cat, NULL, 'UPCOMING', :now)""",
        ).param("id", Cuid.next()).param("u", runner.id).param("e", eventId).param("ref", ref)
            .param("tier", tier).param("cat", category).param("now", nowMillis().toDbTime())
            .update()
        audit.record(admin, "CREATE", "registrations", ref, setOf("complimentary", "category", "tier"))
        cache.invalidateAll()
        return PassResult(ref, runner.name ?: contact)
    }

    data class ResultsUpload(val updated: Int, val unknownBibs: List<String>, val problems: List<String>)

    /**
     * The timing partner's CSV. Header row required; `bib` plus any of
     * finishTime, chipTime, avgPace, overallRank, ageGroupRank, medalStatus,
     * certificateUrl (case-insensitive). Each row is upserted onto the
     * registration with that bib; [publish] makes them visible in the app.
     */
    @Transactional
    fun uploadResults(eventId: String, csv: String, publish: Boolean, admin: AdminPrincipal): ResultsUpload {
        val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 2) throw ApiException(400, "INVALID_BODY", "Paste the CSV with a header row and at least one result.")
        if (lines.size > 50_001) throw ApiException(400, "INVALID_BODY", "Up to 50,000 results per upload.")
        val header = splitCsv(lines.first()).map { it.trim().lowercase() }
        val col = { name: String -> header.indexOf(name.lowercase()) }
        if (col("bib") < 0) throw ApiException(400, "INVALID_BODY", "The header needs a \"bib\" column.")

        val byBib = jdbc.sql("""SELECT "id", "bibNumber" FROM "MarathonRegistration" WHERE "eventId" = :e AND "bibNumber" IS NOT NULL""")
            .param("e", eventId).query { rs, _ -> rs.getString("bibNumber") to rs.getString("id") }.list().toMap()
        val unknown = mutableListOf<String>()
        val problems = mutableListOf<String>()
        var updated = 0
        val now = nowMillis().toDbTime()
        for ((i, line) in lines.drop(1).withIndex()) {
            val row = i + 2
            val cells = splitCsv(line)
            fun cell(name: String): String? = col(name).takeIf { it >= 0 }?.let { cells.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() }
            val bib = cell("bib")
            if (bib == null) {
                problems += "Row $row: no bib"
                continue
            }
            val regId = byBib[bib]
            if (regId == null) {
                unknown += bib
                continue
            }
            val overallRaw = cell("overallRank")
            val ageRaw = cell("ageGroupRank")
            val overall = overallRaw?.toIntOrNull()
            val ageGroup = ageRaw?.toIntOrNull()
            val cert = cell("certificateUrl")
            when {
                overallRaw != null && overall == null -> problems += "Row $row: overallRank isn't a number"
                ageRaw != null && ageGroup == null -> problems += "Row $row: ageGroupRank isn't a number"
                cert != null && !cert.startsWith("https://") -> problems += "Row $row: certificateUrl must start with https://"
                else -> {
                    jdbc.sql(
                        """INSERT INTO "RaceResult" ("id", "registrationId", "published", "finishTime", "chipTime", "avgPace", "overallRank",
                               "ageGroupRank", "medalStatus", "certificateUrl", "photoUrls", "updatedAt")
                           VALUES (:id, :reg, :pub, :finish, :chip, :pace, :overall, :age, :medal, :cert, ARRAY[]::text[], :now)
                           ON CONFLICT ("registrationId") DO UPDATE SET
                               "published" = EXCLUDED."published", "finishTime" = EXCLUDED."finishTime", "chipTime" = EXCLUDED."chipTime",
                               "avgPace" = EXCLUDED."avgPace", "overallRank" = EXCLUDED."overallRank", "ageGroupRank" = EXCLUDED."ageGroupRank",
                               "medalStatus" = EXCLUDED."medalStatus", "certificateUrl" = EXCLUDED."certificateUrl", "updatedAt" = EXCLUDED."updatedAt"""",
                    ).param("id", Cuid.next()).param("reg", regId).param("pub", publish)
                        .param("finish", cell("finishTime")).param("chip", cell("chipTime")).param("pace", cell("avgPace"))
                        .param("overall", overall).param("age", ageGroup).param("medal", cell("medalStatus")).param("cert", cert)
                        .param("now", now)
                        .update()
                    updated++
                }
            }
        }
        audit.record(admin, "UPDATE", "race-results", eventId, setOf("rows:$updated", "published:$publish"))
        cache.invalidateAll()
        return ResultsUpload(updated, unknown.take(100), problems.take(100))
    }

    companion object {
        /** No 0/O or 1/I: refs are read out at expo counters. */
        private const val REF_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** One CSV line: commas, with "quoted, values" and "" escapes. */
        fun splitCsv(line: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { cur.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { out += cur.toString(); cur.clear() }
                    else -> cur.append(c)
                }
                i++
            }
            out += cur.toString()
            return out
        }
    }
}
