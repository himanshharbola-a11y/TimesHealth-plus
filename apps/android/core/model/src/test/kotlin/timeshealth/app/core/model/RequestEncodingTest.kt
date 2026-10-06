package timeshealth.app.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Request bodies: encode, then decode. The encoded JSON must use the server's field names (the
 * zod schemas in apps/api/src/routes) and omit null optional fields.
 */
class RequestEncodingTest {

    /** Encodes [value], checks it decodes back to itself, and returns the JSON for assertions. */
    private fun <T> roundTrip(serializer: KSerializer<T>, value: T): JsonObject {
        val text = ApiJson.encodeToString(serializer, value)
        assertThat(ApiJson.decodeFromString(serializer, text)).isEqualTo(value)
        return ApiJson.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `CreateOrderRequest - marathon registration with referral`() {
        val json = roundTrip(
            CreateOrderRequest.serializer(),
            CreateOrderRequest(
                productType = ProductType.MARATHON_REGISTRATION,
                productId = "delhi_half",
                eventId = "delhi_half",
                category = "21K",
                tier = RaceTier.PREMIUM,
                referralCode = "THATHON",
            ),
        )

        assertThat(json.toString()).isEqualTo(
            """{"productType":"MARATHON_REGISTRATION","productId":"delhi_half","eventId":"delhi_half",""" +
                """"category":"21K","tier":"PREMIUM","referralCode":"THATHON"}""",
        )
    }

    @Test
    fun `CreateOrderRequest - yoga subscription omits marathon-only fields`() {
        val json = roundTrip(
            CreateOrderRequest.serializer(),
            CreateOrderRequest(productType = ProductType.YOGA_SUBSCRIPTION, productId = "yoga_annual"),
        )

        assertThat(json.toString()).isEqualTo("""{"productType":"YOGA_SUBSCRIPTION","productId":"yoga_annual"}""")
    }

    @Test
    fun `UpdateProfileRequest - only the edited fields are sent`() {
        val json = roundTrip(
            UpdateProfileRequest.serializer(),
            UpdateProfileRequest(name = "Asha", concern = Concern.LOWER_BACK),
        )

        assertThat(json.toString()).isEqualTo("""{"name":"Asha","concern":"LOWER_BACK"}""")
        assertThat(roundTrip(UpdateProfileRequest.serializer(), UpdateProfileRequest()).toString()).isEqualTo("{}")
    }

    @Test
    fun `UpdateProfileRequest - every field uses the server's name and wire value`() {
        val json = roundTrip(
            UpdateProfileRequest.serializer(),
            UpdateProfileRequest(
                name = "Asha",
                email = "asha@example.com",
                phone = "+919000000009",
                dob = "1990-05-01T00:00:00.000Z",
                gender = Gender.PREFER_NOT_TO_SAY,
                units = Units.IMPERIAL,
                locale = "hi-IN",
                healthGoal = HealthGoal.STRESS_ANXIETY,
                concern = Concern.NONE,
            ),
        )

        // apps/api/src/routes/session.ts profileSchema.
        assertThat(json.keys).containsExactly(
            "name", "email", "phone", "dob", "gender", "units", "locale", "healthGoal", "concern",
        ).inOrder()
        assertThat(json.getValue("gender").jsonPrimitive.content).isEqualTo("PREFER_NOT_TO_SAY")
        assertThat(json.getValue("units").jsonPrimitive.content).isEqualTo("IMPERIAL")
        assertThat(json.getValue("healthGoal").jsonPrimitive.content).isEqualTo("STRESS_ANXIETY")
    }

    @Test
    fun `UploadRunRequest - routePolyline is always sent, as null when there is no route`() {
        val run = UploadRunRequest(
            id = "96aa8ea5-679d-4683-b7e3-d89ce947de76",
            startedAt = "2026-10-06T08:21:32.359Z",
            endedAt = "2026-10-06T08:26:48.460Z",
            distanceKm = 0.29,
            durationSeconds = 309,
            avgPaceSecPerKm = 1066,
            caloriesBurned = 18,
            routePolyline = null,
            hasAccuracyWarning = false,
        )
        val json = roundTrip(UploadRunRequest.serializer(), run)

        // apps/api/src/routes/runs.ts uploadSchema: every key required; routePolyline is nullable, not optional.
        assertThat(json.keys).containsExactly(
            "id", "startedAt", "endedAt", "distanceKm", "durationSeconds", "avgPaceSecPerKm",
            "caloriesBurned", "routePolyline", "hasAccuracyWarning",
        ).inOrder()
        assertThat(json.getValue("routePolyline")).isEqualTo(JsonNull)
        assertThat(json.getValue("hasAccuracyWarning").jsonPrimitive.content).isEqualTo("false")
        assertThat(json.getValue("distanceKm").jsonPrimitive.content).isEqualTo("0.29")

        val withRoute = roundTrip(UploadRunRequest.serializer(), run.copy(routePolyline = "_p~iF~ps|U_ulLnnqC"))
        assertThat(withRoute.getValue("routePolyline").jsonPrimitive.content).isEqualTo("_p~iF~ps|U_ulLnnqC")
    }

    @Test
    fun `DietLeadRequest - null optional fields are omitted`() {
        val minimal = roundTrip(DietLeadRequest.serializer(), DietLeadRequest(name = "Ravi", phone = "9876543210"))
        assertThat(minimal.toString()).isEqualTo("""{"name":"Ravi","phone":"9876543210"}""")

        val full = roundTrip(
            DietLeadRequest.serializer(),
            DietLeadRequest(
                name = "Ravi",
                phone = "9876543210",
                condition = "Diabetes",
                cuisinePreference = "South Indian",
                bestTimeToCall = "Evening",
            ),
        )
        // apps/api/src/routes/diet.ts leadSchema.
        assertThat(full.keys).containsExactly("name", "phone", "condition", "cuisinePreference", "bestTimeToCall").inOrder()
    }

    @Test
    fun `OnboardingStepRequest - step is a number, 1 to 4`() {
        val json = roundTrip(
            OnboardingStepRequest.serializer(),
            OnboardingStepRequest(step = 3, healthGoal = HealthGoal.MARATHON_TRAINING),
        )
        assertThat(json.toString()).isEqualTo("""{"step":3,"healthGoal":"MARATHON_TRAINING"}""")

        assertThrows(IllegalArgumentException::class.java) { OnboardingStepRequest(step = 5) }
    }

    @Test
    fun `small request bodies`() {
        assertThat(roundTrip(JoinSessionRequest.serializer(), JoinSessionRequest("b5")).toString())
            .isEqualTo("""{"batchId":"b5"}""")
        assertThat(roundTrip(SetReminderSlotRequest.serializer(), SetReminderSlotRequest("b2")).toString())
            .isEqualTo("""{"batchId":"b2"}""")
        assertThat(
            roundTrip(
                UpdateParticipantRequest.serializer(),
                UpdateParticipantRequest(eventId = "delhi_half", tshirtSize = "L"),
            ).toString(),
        ).isEqualTo("""{"eventId":"delhi_half","tshirtSize":"L"}""")
    }
}
