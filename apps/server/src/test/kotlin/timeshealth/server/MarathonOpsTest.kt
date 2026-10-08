package timeshealth.server

import jakarta.servlet.http.Cookie
import java.time.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import timeshealth.server.admin.MarathonOps

/** Race operations from the dashboard: bibs, complimentary passes, results. */
@Testcontainers
@SpringBootTest(
    properties = [
        "th.env.node-env=test",
        "th.env.allow-dev-tokens=true",
        "th.env.admin-bootstrap-email=owner@th.test",
        "th.env.admin-bootstrap-password=correct-horse-battery",
    ],
)
@AutoConfigureMockMvc
class MarathonOpsTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient

    private lateinit var owner: Cookie
    private val future: LocalDateTime = LocalDateTime.now().plusDays(30)

    @BeforeEach
    fun setUp() {
        jdbc.sql(
            """INSERT INTO "MarathonEvent" ("id","name","city","venue","imageUrl","startsAt","flagOffTime")
               VALUES ('ev_ops','Ops Half','Delhi','JLN','https://img.test/e.jpg',:at,'5:30 AM') ON CONFLICT DO NOTHING""",
        ).param("at", future).update()
        for (code in listOf("5K", "21K")) {
            jdbc.sql(
                """INSERT INTO "RaceDistanceOption" ("id","eventId","code","label","priceClassicPaise","pricePremiumPaise")
                   VALUES (:id,'ev_ops',:code,:code,100000,200000) ON CONFLICT DO NOTHING""",
            ).param("id", "rd_$code").param("code", code).update()
        }
        for (i in 1..3) {
            jdbc.sql(
                """INSERT INTO "User" ("id","firebaseUid","email","phone","name","updatedAt")
                   VALUES (:id,:uid,:email,:phone,:name,now()) ON CONFLICT DO NOTHING""",
            ).param("id", "u_ops_$i").param("uid", "qa_ops_$i").param("email", "runner$i@th.test")
                .param("phone", "+9190000100$i").param("name", "Runner $i").update()
        }
        // Two registered already (in this order), no bibs.
        for (i in 1..2) {
            jdbc.sql(
                """INSERT INTO "MarathonRegistration" ("id","userId","eventId","registrationRef","tier","category","registeredAt")
                   VALUES (:id,:u,'ev_ops',:ref,'CLASSIC',:cat,:at) ON CONFLICT DO NOTHING""",
            ).param("id", "mr_$i").param("u", "u_ops_$i").param("ref", "TH-OPS$i").param("cat", if (i == 1) "21K" else "5K")
                .param("at", LocalDateTime.now().minusDays(10L - i)).update()
        }
        // Each test starts from the same edition: no bibs, no results, no extra passes.
        jdbc.sql("""DELETE FROM "RaceResult" WHERE "registrationId" IN (SELECT "id" FROM "MarathonRegistration" WHERE "eventId" = 'ev_ops')""").update()
        jdbc.sql("""DELETE FROM "MarathonRegistration" WHERE "eventId" = 'ev_ops' AND "id" NOT IN ('mr_1', 'mr_2')""").update()
        jdbc.sql("""UPDATE "MarathonRegistration" SET "bibNumber" = NULL, "tier" = 'CLASSIC' WHERE "eventId" = 'ev_ops'""").update()
        owner = mvc.post("/admin/api/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"owner@th.test","password":"correct-horse-battery"}"""
        }.andReturn().response.getCookie("th_admin")!!
    }

    private fun post(path: String, body: String) = mvc.post("/admin/api$path") {
        cookie(owner)
        header("X-Requested-With", "th-admin")
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun bib(regId: String): String? =
        jdbc.sql("""SELECT "bibNumber" FROM "MarathonRegistration" WHERE "id" = :id""").param("id", regId).query(String::class.java).optional().orElse(null)

    @Test
    fun `allocating bibs numbers each distance in registration order, never twice`() {
        post("/marathons/ev_ops/allocate-bibs", """{"prefix":"del"}""").andExpect { jsonPath("$.allocated") { value(2) } }
        assertThat(bib("mr_1")).isEqualTo("DEL-21K-0001")
        assertThat(bib("mr_2")).isEqualTo("DEL-5K-0001")
        post("/marathons/ev_ops/allocate-bibs", "{}").andExpect { jsonPath("$.allocated") { value(0) } }
    }

    @Test
    fun `a bib number is unique within the edition`() {
        post("/marathons/ev_ops/allocate-bibs", "{}")
        mvc.patch("/admin/api/r/registrations/mr_2") {
            cookie(owner)
            header("X-Requested-With", "th-admin")
            contentType = MediaType.APPLICATION_JSON
            content = """{"bibNumber":"${bib("mr_1")}"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("DUPLICATE") }
        }
        // Read-only columns are ignored on edit.
        mvc.patch("/admin/api/r/registrations/mr_2") {
            cookie(owner)
            header("X-Requested-With", "th-admin")
            contentType = MediaType.APPLICATION_JSON
            content = """{"bibNumber":"VIP-7","tier":"PREMIUM","registrationRef":"HACK"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.item.bibNumber") { value("VIP-7") }
            jsonPath("$.item.tier") { value("CLASSIC") }
            jsonPath("$.item.registrationRef") { value("TH-OPS2") }
            jsonPath("$.item.runnerName") { value("Runner 2") }
        }
    }

    @Test
    fun `a complimentary pass registers an existing user once`() {
        post("/marathons/ev_ops/passes", """{"contact":"12345","category":"21K","tier":"PREMIUM"}""").andExpect { status { isBadRequest() } }
        post("/marathons/ev_ops/passes", """{"contact":"Runner3@TH.test","category":"21K","tier":"PREMIUM"}""").andExpect {
            status { isOk() }
            jsonPath("$.runner") { value("Runner 3") }
        }
        val tier = jdbc.sql("""SELECT "tier" FROM "MarathonRegistration" WHERE "userId" = 'u_ops_3' AND "eventId" = 'ev_ops'""")
            .query(String::class.java).single()
        assertThat(tier).isEqualTo("PREMIUM")
        post("/marathons/ev_ops/passes", """{"contact":"runner3@th.test","category":"21K","tier":"CLASSIC"}""").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("ALREADY_REGISTERED") }
        }
        post("/marathons/ev_ops/passes", """{"contact":"nobody@th.test","category":"21K","tier":"CLASSIC"}""").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NO_SUCH_USER") }
        }
    }

    @Test
    fun `results upload by bib, published to the runner's race page`() {
        post("/marathons/ev_ops/allocate-bibs", """{"prefix":"RES"}""")
        val csv = "bib,chipTime,finishTime,avgPace,overallRank,ageGroupRank,medalStatus,certificateUrl\\n" +
            "RES-21K-0001,01:52:10,01:53:02,5:19,412,38,Finisher,https://cert.test/1.pdf\\n" +
            "NOPE-1,01:00:00,,,,,,\\n" +
            "RES-5K-0001,00:28:30,,,,abc,,"
        post("/marathons/ev_ops/results", """{"csv":"$csv","publish":true}""").andExpect {
            status { isOk() }
            jsonPath("$.updated") { value(1) }
            jsonPath("$.unknownBibs[0]") { value("NOPE-1") }
            jsonPath("$.problems[0]") { value("Row 4: ageGroupRank isn't a number") }
        }
        // The runner sees it (the app's race page, through the Node-shaped API is not ported; check the row).
        val chip = jdbc.sql("""SELECT "chipTime" FROM "RaceResult" WHERE "registrationId" = 'mr_1' AND "published"""")
            .query(String::class.java).single()
        assertThat(chip).isEqualTo("01:52:10")
        // Re-uploading updates in place.
        post("/marathons/ev_ops/results", """{"csv":"bib,chipTime\nRES-21K-0001,01:51:59","publish":true}""").andExpect { jsonPath("$.updated") { value(1) } }
        assertThat(jdbc.sql("""SELECT count(*) FROM "RaceResult" WHERE "registrationId" = 'mr_1'""").query(Long::class.java).single()).isEqualTo(1)
    }

    @Test
    fun `registrations list shows the runner`() {
        mvc.get("/admin/api/r/registrations?f.eventId=ev_ops") { cookie(owner) }.andExpect {
            status { isOk() }
            jsonPath("$.items[?(@.registrationRef == 'TH-OPS1')].runnerName") { value("Runner 1") }
            jsonPath("$.items[?(@.registrationRef == 'TH-OPS1')].runnerContact") { value("runner1@th.test") }
        }
    }

    @Test
    fun `csv splitting handles quotes`() {
        assertThat(MarathonOps.splitCsv("a,\"b, c\",\"d \"\"e\"\"\",")).containsExactly("a", "b, c", "d \"e\"", "")
    }
}
