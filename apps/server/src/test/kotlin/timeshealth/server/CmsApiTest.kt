package timeshealth.server

import jakarta.servlet.http.Cookie
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
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
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import timeshealth.server.cms.ContentCache
import timeshealth.server.config.PinnableClock
import timeshealth.server.json.ServerJson

/**
 * The admin CMS end to end: dashboard sign-in and roles, generic editing with validation, the
 * Home feed built from sections, hand-picked rails, live classes (premieres) and the audit log.
 */
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
class CmsApiTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16")

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }

        /** 06:00 UTC = 11:30 IST: a fixed "now" so class states are exact. */
        val NOW: Instant = Instant.parse("2026-10-07T06:00:00Z")
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var cache: ContentCache
    @Autowired lateinit var clock: PinnableClock

    private lateinit var owner: Cookie

    @BeforeEach
    fun setUp() {
        clock.pin(NOW)
        seedContent()
        owner = login("owner@th.test", "correct-horse-battery")
    }

    @AfterEach
    fun tearDown() = clock.pin(null)

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun seedContent() {
        jdbc.sql(
            """INSERT INTO "Instructor" ("id","name","title","specialty","bio","experience","avatarUrl")
               VALUES ('ins_t','Asha','Guru','Hatha','Bio','10 years','https://img.test/a.jpg') ON CONFLICT DO NOTHING""",
        ).update()
        jdbc.sql(
            """INSERT INTO "YogaCategory" ("id","name","tagline","bodyTargetSummary","imageUrl","sortOrder")
               VALUES ('cat_morning','Morning','t','b','https://img.test/c.jpg',1) ON CONFLICT DO NOTHING""",
        ).update()
        for (i in 1..3) {
            jdbc.sql(
                """INSERT INTO "YogaSession" ("id","categoryId","title","description","imageUrl","durationMinutes","level",
                       "intensity","isFree","bodyFocusTitle","targetBodyParts","keyPoses","lifestyleImpact","instructorId","sortOrder")
                   VALUES (:id,'cat_morning',:title,'d','https://img.test/s.jpg',20,'Beginner','Gentle',:free,'Focus',
                       ARRAY['spine'],ARRAY['cat'],'impact','ins_t',:sort) ON CONFLICT DO NOTHING""",
            ).param("id", "ses_$i").param("title", "Session $i").param("free", i != 3).param("sort", i * 10).update()
        }
        cache.invalidateAll()
    }

    private fun freshUserToken(): String {
        val id = UUID.randomUUID().toString().take(8)
        return "qa_cms_$id|c$id@th.test|"
    }

    /** Signs the token in (creating the user) and makes them an active yoga member. */
    private fun memberToken(): String {
        val token = freshUserToken()
        home(token)
        val userId = jdbc.sql("""SELECT "id" FROM "User" WHERE "firebaseUid" = :u""")
            .param("u", token.substringBefore('|')).query(String::class.java).single()
        jdbc.sql(
            """INSERT INTO "YogaSubscription" ("id","userId","planId","planLabel","status","startedAt","expiresAt","updatedAt")
               VALUES (:id, :u, 'yoga_annual', 'Annual', 'ACTIVE', :from, :to, :from)""",
        ).param("id", "sub_" + UUID.randomUUID().toString().take(8)).param("u", userId)
            .param("from", LocalDateTime.ofInstant(NOW.minus(Duration.ofDays(10)), ZoneOffset.UTC))
            .param("to", LocalDateTime.ofInstant(NOW.plus(Duration.ofDays(300)), ZoneOffset.UTC))
            .update()
        return token
    }

    private fun json(result: MvcResult): JsonObject =
        ServerJson.parseToJsonElement(result.response.contentAsString).jsonObject

    private fun home(token: String): JsonObject =
        json(mvc.get("/v1/home") { header("Authorization", "Bearer $token") }.andExpect { status { isOk() } }.andReturn())

    private fun componentIds(feed: JsonObject): List<String> =
        feed["components"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private fun login(email: String, password: String): Cookie {
        val result = mvc.post("/admin/api/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andExpect { status { isOk() } }.andReturn()
        return result.response.getCookie("th_admin")!!
    }

    private fun adminPost(path: String, body: String, cookie: Cookie = owner, csrf: Boolean = true) =
        mvc.post("/admin/api$path") {
            cookie(cookie)
            if (csrf) header("X-Requested-With", "th-admin")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun adminPatch(path: String, body: String) = mvc.patch("/admin/api$path") {
        cookie(owner)
        header("X-Requested-With", "th-admin")
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun isoAt(minutesFromNow: Long) = NOW.plus(Duration.ofMinutes(minutesFromNow)).toString()

    // ── Dashboard sign-in, roles, CSRF ────────────────────────────────────────

    @Test
    fun `dashboard API needs a session`() {
        mvc.get("/admin/api/me").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHORIZED") }
        }
        mvc.get("/admin/api/me") { cookie(owner) }.andExpect {
            status { isOk() }
            jsonPath("$.role") { value("OWNER") }
        }
    }

    @Test
    fun `wrong password is refused and a forged cookie is ignored`() {
        mvc.post("/admin/api/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"owner@th.test","password":"wrong-password-here"}"""
        }.andExpect { status { isUnauthorized() } }
        val forged = Cookie("th_admin", owner.value.dropLast(2) + "00")
        mvc.get("/admin/api/me") { cookie(forged) }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `writes need the dashboard header`() {
        adminPost("/r/testimonials", """{"quote":"q","author":"a","role":"r"}""", csrf = false)
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `viewers are read-only`() {
        adminPost("/admins", """{"email":"viewer@th.test","name":"View","role":"VIEWER","password":"viewer-password-123"}""")
            .andExpect { status { isOk() } }
        val viewer = login("viewer@th.test", "viewer-password-123")
        mvc.get("/admin/api/r/sections") { cookie(viewer) }.andExpect { status { isOk() } }
        adminPost("/r/testimonials", """{"quote":"q","author":"a","role":"r"}""", cookie = viewer)
            .andExpect { status { isForbidden() } }
        mvc.get("/admin/api/admins") { cookie(viewer) }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin API is never offered to other origins`() {
        mvc.get("/admin/api/me") {
            cookie(owner)
            header("Origin", "https://evil.test")
        }.andExpect { header { doesNotExist("Access-Control-Allow-Origin") } }
    }

    // ── Generic editing ───────────────────────────────────────────────────────

    @Test
    fun `validation reports every field problem at once`() {
        adminPost("/r/sessions", """{"title":"","durationMinutes":"abc","categoryId":"nope","videoProvider":"vimeo"}""")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_BODY") }
                jsonPath("$.fields.title") { exists() }
                jsonPath("$.fields.durationMinutes") { exists() }
                jsonPath("$.fields.categoryId") { exists() }
                jsonPath("$.fields.videoProvider") { exists() }
                jsonPath("$.fields.instructorId") { exists() }
            }
    }

    @Test
    fun `create, edit and delete a testimonial, with an audit trail`() {
        val created = json(
            adminPost("/r/testimonials", """{"quote":"Changed my mornings","author":"Ritu","role":"Member since 2024","sortOrder":5}""")
                .andExpect { status { isOk() } }.andReturn(),
        )
        val id = created["item"]!!.jsonObject["id"]!!.jsonPrimitive.content
        adminPatch("/r/testimonials/$id", """{"author":"Ritu S."}""").andExpect {
            status { isOk() }
            jsonPath("$.item.author") { value("Ritu S.") }
            jsonPath("$.item.quote") { value("Changed my mornings") }
        }
        mvc.delete("/admin/api/r/testimonials/$id") {
            cookie(owner)
            header("X-Requested-With", "th-admin")
        }.andExpect { status { isOk() } }

        val actions = ServerJson.parseToJsonElement(
            mvc.get("/admin/api/audit?resource=testimonials") { cookie(owner) }.andReturn().response.contentAsString,
        ).jsonArray.filter { it.jsonObject["resourceId"]?.jsonPrimitive?.content == id }
            .map { it.jsonObject["action"]!!.jsonPrimitive.content }
        assertThat(actions).containsExactly("DELETE", "UPDATE", "CREATE")
    }

    @Test
    fun `deleting content that is still used is refused clearly`() {
        mvc.delete("/admin/api/r/instructors/ins_t") {
            cookie(owner)
            header("X-Requested-With", "th-admin")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("IN_USE") }
        }
    }

    // ── Home from sections ────────────────────────────────────────────────────

    @Test
    fun `home follows the admin's sections, order and visibility`() {
        val token = freshUserToken()
        val before = componentIds(home(token))
        assertThat(before).first().isEqualTo("sec_home_hero")
        assertThat(before).contains("sec_home_free", "sec_home_run")
        // Free sessions come before the run tile by default.
        assertThat(before.indexOf("sec_home_free")).isLessThan(before.indexOf("sec_home_run"))

        // Hide the free rail; move the run tile to the top.
        adminPatch("/r/sections/sec_home_free", """{"visible":false}""").andExpect { status { isOk() } }
        val order = listOf("sec_home_run", "sec_home_hero", "sec_home_for_you", "sec_home_promo", "sec_home_free",
            "sec_home_live", "sec_home_workshops", "sec_home_instructors", "sec_home_articles", "sec_home_testimonials")
        adminPost("/r/sections/reorder", """{"ids":${order.joinToString(",", "[", "]") { "\"$it\"" }}}""")
            .andExpect { status { isOk() } }

        val after = componentIds(home(token))
        assertThat(after).first().isEqualTo("sec_home_run")
        assertThat(after).doesNotContain("sec_home_free")

        // Restore the default layout for the other tests.
        adminPatch("/r/sections/sec_home_free", """{"visible":true}""")
        val defaults = listOf("sec_home_hero", "sec_home_for_you", "sec_home_promo", "sec_home_free", "sec_home_live",
            "sec_home_run", "sec_home_workshops", "sec_home_instructors", "sec_home_articles", "sec_home_testimonials")
        adminPost("/r/sections/reorder", """{"ids":${defaults.joinToString(",", "[", "]") { "\"$it\"" }}}""")
    }

    @Test
    fun `a scheduled section shows only inside its window, and audiences are respected`() {
        val section = json(
            adminPost(
                "/r/sections",
                """{"page":"HOME","kind":"FREE_SESSIONS","title":"Weekend picks","startsAt":"${isoAt(60)}","sortOrder":15}""",
            ).andExpect { status { isOk() } }.andReturn(),
        )["item"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val token = freshUserToken()
        assertThat(componentIds(home(token))).doesNotContain(section)
        clock.pin(NOW.plus(Duration.ofMinutes(61)))
        assertThat(componentIds(home(token))).contains(section)

        adminPatch("/r/sections/$section", """{"audience":"MEMBERS"}""")
        assertThat(componentIds(home(token))).doesNotContain(section)
        assertThat(componentIds(home(memberToken()))).contains(section)
    }

    @Test
    fun `hand-picked rail shows exactly the chosen videos in order`() {
        val section = json(
            adminPost("/r/sections", """{"page":"HOME","kind":"CURATED_VIDEOS","title":"Editor's picks"}""")
                .andExpect { status { isOk() } }.andReturn(),
        )["item"]!!.jsonObject["id"]!!.jsonPrimitive.content
        // Created without an order: it goes to the end of the page, not the top.
        val order = jdbc.sql("""SELECT "sortOrder" FROM "FeedSection" WHERE "id" = :id""").param("id", section)
            .query(Int::class.java).single()
        val maxOther = jdbc.sql("""SELECT max("sortOrder") FROM "FeedSection" WHERE "id" <> :id""").param("id", section)
            .query(Int::class.java).single()
        assertThat(order).isGreaterThan(maxOther)

        mvc.put("/admin/api/sections/$section/items") {
            cookie(owner)
            header("X-Requested-With", "th-admin")
            contentType = MediaType.APPLICATION_JSON
            content = """{"items":[{"refId":"ses_3"},{"refId":"ses_1"}]}"""
        }.andExpect { status { isOk() } }

        val rail = home(freshUserToken())["components"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == section }
        assertThat(rail["type"]!!.jsonPrimitive.content).isEqualTo("VIDEO_RAIL")
        val items = rail["items"]!!.jsonArray.map { it.jsonObject }
        assertThat(items.map { it["id"]!!.jsonPrimitive.content }).containsExactly("ses_3", "ses_1")
        // ses_3 is members-only: a non-member gets no playable video for it.
        assertThat(items[0]["playbackUrl"].toString()).isEqualTo("null")
    }

    @Test
    fun `a session's dashboard video is offered only to those who may play it`() {
        adminPatch("/r/sessions/ses_1", """{"videoProvider":"slike","videoRef":"sl_123"}""").andExpect { status { isOk() } }
        adminPatch("/r/sessions/ses_3", """{"videoProvider":"url","videoRef":"https://cdn.test/v/3.m3u8"}""").andExpect { status { isOk() } }

        fun free(token: String) = home(token)["components"]!!.jsonArray.map { it.jsonObject }
            .first { it["id"]!!.jsonPrimitive.content == "sec_home_free" }["items"]!!.jsonArray.map { it.jsonObject }
        val s1 = free(freshUserToken()).first { it["id"]!!.jsonPrimitive.content == "ses_1" }
        assertThat(s1["video"]!!.jsonObject["provider"]!!.jsonPrimitive.content).isEqualTo("slike")
        assertThat(s1["video"]!!.jsonObject["ref"]!!.jsonPrimitive.content).isEqualTo("sl_123")
    }

    @Test
    fun `playback hands the dashboard video or a signed URL, members only for paid sessions`() {
        fun playback(id: String, token: String) = mvc.get("/v1/yoga/sessions/$id/playback") { header("Authorization", "Bearer $token") }

        // Paid ses_3 with a Slike video: refused to a free user, the Slike ref to a member.
        adminPatch("/r/sessions/ses_3", """{"videoProvider":"slike","videoRef":"sl_999"}""").andExpect { status { isOk() } }
        playback("ses_3", freshUserToken()).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("NOT_ENTITLED") }
        }
        playback("ses_3", memberToken()).andExpect {
            status { isOk() }
            jsonPath("$.video.provider") { value("slike") }
            jsonPath("$.video.ref") { value("sl_999") }
            jsonPath("$.playbackUrl") { value("") }
        }

        // Free ses_2: no video at all is a 404, uploaded media is a fresh signed URL for anyone.
        jdbc.sql("""UPDATE "YogaSession" SET "mediaKey" = NULL, "videoProvider" = NULL, "videoRef" = NULL WHERE "id" = 'ses_2'""").update()
        playback("ses_2", freshUserToken()).andExpect { status { isNotFound() } }
        jdbc.sql("""UPDATE "YogaSession" SET "mediaKey" = 'sessions/ses_2/master.m3u8' WHERE "id" = 'ses_2'""").update()
        playback("ses_2", freshUserToken()).andExpect {
            status { isOk() }
            jsonPath("$.playbackUrl") { value(org.hamcrest.Matchers.containsString("/sessions/ses_2/master.m3u8?u=")) }
            jsonPath("$.video") { doesNotExist() }
        }
        playback("nope", freshUserToken()).andExpect { status { isNotFound() } }
    }

    // ── Live classes ──────────────────────────────────────────────────────────

    private fun createLiveClass(minutesFromNow: Long, free: Boolean): String = json(
        adminPost(
            "/r/live-classes",
            """{"title":"Sunrise flow","startsAt":"${isoAt(minutesFromNow)}","durationMinutes":60,"isFree":$free,
               "videoProvider":"url","videoRef":"https://cdn.test/live.m3u8","instructorId":"ins_t"}""",
        ).andExpect { status { isOk() } }.andReturn(),
    )["item"]!!.jsonObject["id"]!!.jsonPrimitive.content

    @Test
    fun `live class states follow the clock and the stream is only given on join`() {
        val id = createLiveClass(30, free = true)
        val token = freshUserToken()
        val list = json(mvc.get("/v1/yoga/live") { header("Authorization", "Bearer $token") }.andReturn())
        val card = list["items"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == id }
        assertThat(card["state"]!!.jsonPrimitive.content).isEqualTo("STARTING_SOON")
        assertThat(card["canJoin"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(card.containsKey("video")).isFalse()

        // Wait room: joinable, position 0. Live: position = time since start.
        mvc.post("/v1/yoga/live/$id/join") { header("Authorization", "Bearer $token") }.andExpect {
            status { isOk() }
            jsonPath("$.positionMs") { value(0) }
            jsonPath("$.video.ref") { value("https://cdn.test/live.m3u8") }
            // A non-member watching a free class isn't on the attendance ledger.
            jsonPath("$.attendanceRecorded") { value(false) }
        }
        clock.pin(NOW.plus(Duration.ofMinutes(40)))
        mvc.post("/v1/yoga/live/$id/join") { header("Authorization", "Bearer $token") }.andExpect {
            status { isOk() }
            jsonPath("$.positionMs") { value(10 * 60_000) }
        }
        clock.pin(NOW.plus(Duration.ofMinutes(91)))
        mvc.post("/v1/yoga/live/$id/join") { header("Authorization", "Bearer $token") }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CLASS_ENDED") }
        }
    }

    @Test
    fun `members-only classes need the membership, and a member join counts attendance`() {
        val id = createLiveClass(5, free = false)
        mvc.post("/v1/yoga/live/$id/join") { header("Authorization", "Bearer ${freshUserToken()}") }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("NOT_ENTITLED") }
        }
        val member = memberToken()
        mvc.post("/v1/yoga/live/$id/join") { header("Authorization", "Bearer $member") }.andExpect {
            status { isOk() }
            jsonPath("$.attendanceRecorded") { value(true) }
        }
        // Same IST day again: one mark only.
        mvc.post("/v1/yoga/live/$id/join") { header("Authorization", "Bearer $member") }.andExpect {
            jsonPath("$.attendanceRecorded") { value(false) }
        }
    }

    @Test
    fun `too early and cancelled classes can't be joined`() {
        val early = createLiveClass(120, free = true)
        mvc.post("/v1/yoga/live/$early/join") { header("Authorization", "Bearer ${freshUserToken()}") }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CLASS_NOT_OPEN") }
        }
        val cancelled = createLiveClass(10, free = true)
        adminPatch("/r/live-classes/$cancelled", """{"status":"CANCELLED"}""").andExpect { status { isOk() } }
        mvc.post("/v1/yoga/live/$cancelled/join") { header("Authorization", "Bearer ${freshUserToken()}") }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CLASS_CANCELLED") }
        }
    }

    @Test
    fun `the live classes rail appears on Home once classes are scheduled`() {
        val id = createLiveClass(45, free = false)
        val rail = home(freshUserToken())["components"]!!.jsonArray.map { it.jsonObject }
            .first { it["id"]!!.jsonPrimitive.content == "sec_home_live" }
        assertThat(rail["type"]!!.jsonPrimitive.content).isEqualTo("LIVE_CLASS_RAIL")
        val card = rail["items"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == id }
        assertThat(card["canJoin"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    fun `schedule a week of classes for a batch, safely re-runnable`() {
        jdbc.sql(
            """INSERT INTO "YogaBatch" ("id","title","time","period","instructorId") VALUES ('bat_t','Morning 6:30','06:30','MORNING','ins_t')
               ON CONFLICT DO NOTHING""",
        ).update()
        val body = """{"batchId":"bat_t","fromDate":"2026-11-02","days":7,"videoProvider":"slike","videoRef":"sl_week"}"""
        adminPost("/live-classes/schedule", body).andExpect {
            status { isOk() }
            jsonPath("$.created") { value(7) }
        }
        adminPost("/live-classes/schedule", body).andExpect {
            jsonPath("$.created") { value(0) }
            jsonPath("$.skipped") { value(7) }
        }
        // 06:30 IST is 01:00 UTC.
        val first = jdbc.sql("""SELECT min("startsAt") FROM "LiveClass" WHERE "batchId" = 'bat_t'""")
            .query(LocalDateTime::class.java).single()
        assertThat(first).isEqualTo(LocalDateTime.parse("2026-11-02T01:00:00"))
    }

    // ── Pass-through ──────────────────────────────────────────────────────────

    @Test
    fun `routes not ported yet are 404 when no Node upstream is configured`() {
        mvc.get("/v1/marathon/events") { header("Authorization", "Bearer ${freshUserToken()}") }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    @Suppress("unused")
    private fun JsonArray.ids() = map { it.jsonObject["id"]!!.jsonPrimitive.content }
}
