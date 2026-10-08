package timeshealth.server

import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The session/profile/account slice, against a real Postgres built by the Flyway
 * baseline — the same rules apps/api/test/api.test.ts checks on the Node server.
 * (scripts/parity*.py additionally diff the two servers response-for-response.)
 */
@Testcontainers
@SpringBootTest(
    properties = [
        "th.env.node-env=test",
        "th.env.allow-dev-tokens=true",
    ],
)
@AutoConfigureMockMvc
class SessionApiTest {

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

    /** A brand-new QA user, unique to the test (persona tokens are QA-only). */
    private fun freshUser(phone: String = ""): String {
        val id = UUID.randomUUID().toString().take(8)
        return "qa_test_$id|t$id@th.test|$phone"
    }

    private fun get(path: String, token: String? = null): ResultActionsDsl =
        mvc.get(path) { token?.let { header("Authorization", "Bearer $it") } }

    private fun patch(path: String, token: String, json: String): ResultActionsDsl =
        mvc.patch(path) {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = json
        }

    private fun userId(token: String): String =
        jdbc.sql("""SELECT "id" FROM "User" WHERE "firebaseUid" = :uid""")
            .param("uid", token.substringBefore('|'))
            .query(String::class.java)
            .single()

    @Test
    fun `health reports the database`() {
        get("/health").andExpect {
            status { isOk() }
            jsonPath("$.ok", equalTo(true))
        }
    }

    @Test
    fun `no token is a 401 in the app error shape`() {
        get("/v1/session").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code", equalTo("UNAUTHORIZED"))
        }
    }

    @Test
    fun `a persona token can never claim a real person's email or number`() {
        get("/v1/session", "qa_test_x|someone@gmail.com|").andExpect { status { isUnauthorized() } }
        get("/v1/session", "qa_test_y||+919876543210").andExpect { status { isUnauthorized() } }
        get("/v1/session", "admin|a@th.test|").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `launch config is public`() {
        get("/v1/config").andExpect {
            status { isOk() }
            jsonPath("$.maintenance.active", equalTo(false))
        }
    }

    @Test
    fun `a new user is free and goes through onboarding`() {
        get("/v1/session", freshUser()).andExpect {
            status { isOk() }
            jsonPath("$.persona.persona", equalTo("FREE"))
            jsonPath("$.needsOnboarding", equalTo(true))
            jsonPath("$.profile.emailIsLogin", equalTo(true))
        }
    }

    @Test
    fun `a lapsed member is not sent through onboarding again`() {
        val u = freshUser()
        get("/v1/session", u).andExpect { status { isOk() } }
        val now = Instant.now()
        jdbc.sql(
            """INSERT INTO "YogaSubscription"
               ("id","userId","planId","planLabel","status","startedAt","expiresAt","autoRenews","updatedAt")
               VALUES (:id,:uid,'yoga_annual','Annual Membership','EXPIRED',:start,:end,false,:now)""",
        )
            .param("id", "sub_${UUID.randomUUID()}")
            .param("uid", userId(u))
            .param("start", Timestamp.from(now.minus(400, ChronoUnit.DAYS)))
            .param("end", Timestamp.from(now.minus(35, ChronoUnit.DAYS)))
            .param("now", Timestamp.from(now))
            .update()
        get("/v1/session", u).andExpect {
            status { isOk() }
            jsonPath("$.needsOnboarding", equalTo(false))
            jsonPath("$.persona.persona", equalTo("YOGA_EXPIRED"))
        }
    }

    @Test
    fun `a typed mobile is stored in one format and an uncallable one is refused`() {
        val u = freshUser()
        patch("/v1/profile", u, """{"phone":"098765 43210"}""").andExpect {
            status { isOk() }
            jsonPath("$.profile.phone", equalTo("+919876543210"))
            jsonPath("$.profile.phoneIsLogin", equalTo(false))
        }
        patch("/v1/profile", u, """{"phone":"1234567890"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code", equalTo("INVALID_PHONE"))
        }
        patch("/v1/profile", u, """{"phone":"+91 58765 43210"}""").andExpect {
            jsonPath("$.code", equalTo("INVALID_PHONE"))
        }
    }

    @Test
    fun `the sign-in email is not editable from the profile`() {
        val u = freshUser()
        patch("/v1/profile", u, """{"email":"someone.else@example.com"}""").andExpect {
            status { isConflict() }
            jsonPath("$.code", equalTo("LOGIN_IDENTIFIER"))
        }
    }

    @Test
    fun `an impossible date of birth is refused`() {
        val u = freshUser()
        patch("/v1/profile", u, """{"dob":"2099-01-01T00:00:00.000Z"}""").andExpect { status { isBadRequest() } }
        patch("/v1/profile", u, """{"dob":"1990-06-15T00:00:00.000Z"}""").andExpect { status { isOk() } }
    }

    @Test
    fun `an unknown route answers in the standard error shape`() {
        get("/v1/no-such-route", freshUser()).andExpect {
            status { isNotFound() }
            jsonPath("$.code", equalTo("NOT_FOUND"))
        }
    }

    @Test
    fun `deleting an account keeps its payment records, detached from the person`() {
        val u = freshUser()
        get("/v1/session", u).andExpect { status { isOk() } }
        val orderId = "ord_${UUID.randomUUID()}"
        jdbc.sql(
            """INSERT INTO "Order" ("id","userId","productType","productId","amountPaise","status","updatedAt")
               VALUES (:id,:uid,'YOGA_SUBSCRIPTION','yoga_annual',499900,'PAID',:now)""",
        )
            .param("id", orderId)
            .param("uid", userId(u))
            .param("now", Timestamp.from(Instant.now()))
            .update()

        mvc.delete("/v1/account") { header("Authorization", "Bearer $u") }.andExpect { status { isOk() } }

        val owner = jdbc.sql("""SELECT "userId" FROM "Order" WHERE "id" = :id""")
            .param("id", orderId)
            .query { rs, _ -> rs.getString(1) as String? }
            .list()
        assert(owner.size == 1 && owner[0] == null) { "order should be kept, detached from the deleted user" }
    }
}
