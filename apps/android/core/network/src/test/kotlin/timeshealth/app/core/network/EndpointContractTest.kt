package timeshealth.app.core.network

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import timeshealth.app.core.model.ApiJson
import timeshealth.app.core.model.ApplyReferralRequest
import timeshealth.app.core.model.SetRegisteredRequest
import timeshealth.app.core.model.Concern
import timeshealth.app.core.model.CreateOrderRequest
import timeshealth.app.core.model.DevicePlatform
import timeshealth.app.core.model.DietLead
import timeshealth.app.core.model.JoinSessionRequest
import timeshealth.app.core.model.OnboardingStepRequest
import timeshealth.app.core.model.ProductType
import timeshealth.app.core.model.PushProvider
import timeshealth.app.core.model.RaceTier
import timeshealth.app.core.model.RegisterPushTokenRequest
import timeshealth.app.core.model.RemovePushTokenRequest
import timeshealth.app.core.model.SetCompletedRequest
import timeshealth.app.core.model.SetReminderSlotRequest
import timeshealth.app.core.model.SetSavedRequest
import timeshealth.app.core.model.UpdateParticipantRequest
import timeshealth.app.core.model.UpdateProfileRequest
import timeshealth.app.core.model.UploadRunRequest
import timeshealth.app.core.model.toBody

/**
 * One case per [TimesHealthApi] method: the method, path and JSON body that reach the server must
 * match the route in apps/api/src/routes/, and the route's response (a recorded staging fixture
 * where one exists) must decode. [ApiSurfaceTest] checks no method is missing from this table.
 */
@RunWith(Parameterized::class)
class EndpointContractTest(private val case: Case) {

    class Case(
        /** The [TimesHealthApi] method name. */
        val name: String,
        val method: String,
        /** Path and query as received by the server. */
        val path: String,
        /** Expected JSON request body, or null when no body may be sent. */
        val requestBody: String?,
        val response: String,
        val call: suspend TimesHealthApi.() -> Any,
    ) {
        override fun toString() = name
    }

    companion object {
        private val profile = """{"profile":${Fixtures.tree("session.free.json")["profile"]}}"""
        private const val RUN_ID = "3f1c8c3e-7f4a-4d47-9a43-2f6a3c1b9e10"

        val cases: List<Case> = listOf(
            // Session / bootstrap
            Case("config", "GET", "/v1/config", null, Fixtures.text("config.json")) { config() },
            Case("session", "GET", "/v1/session", null, Fixtures.text("session.both.json")) { session() },
            Case(
                "onboardingStep", "POST", "/v1/onboarding/step",
                """{"step":3,"concern":"LOWER_BACK"}""", profile,
            ) { onboardingStep(OnboardingStepRequest(step = 3, concern = Concern.LOWER_BACK)) },
            Case("skipOnboarding", "POST", "/v1/onboarding/skip", null, profile) { skipOnboarding() },
            Case(
                "updateProfile", "PATCH", "/v1/profile", """{"name":"Asha"}""", profile,
            ) { updateProfile(UpdateProfileRequest(name = "Asha")) },
            Case(
                "deleteAccount", "DELETE", "/v1/account", null,
                """{"deleted":true,"authRecordDeleted":true}""",
            ) { deleteAccount() },
            Case("home", "GET", "/v1/home", null, Fixtures.text("home.yoga.json")) { home() },

            // Yoga
            Case("yogaToday", "GET", "/v1/yoga/today", null, Fixtures.text("yoga-today.json")) { yogaToday() },
            Case(
                "liveClasses", "GET", "/v1/yoga/live", null,
                """{"items":[{"id":"lc1","title":"Sunrise flow","description":"","imageUrl":null,
                   "startsAt":"2026-10-08T00:30:00.000Z","endsAt":"2026-10-08T01:30:00.000Z","durationMinutes":60,
                   "isFree":true,"state":"STARTING_SOON","instructorName":"Asha","instructorAvatarUrl":null,
                   "batchId":"b1","canJoin":true}],"serverTime":"2026-10-08T00:00:00.000Z"}""",
            ) { liveClasses() },
            Case(
                "joinLiveClass", "POST", "/v1/yoga/live/lc1/join", null,
                """{"liveClassId":"lc1","video":{"provider":"slike","ref":"sl_1"},"startsAt":"2026-10-08T00:30:00.000Z",
                   "endsAt":"2026-10-08T01:30:00.000Z","serverTime":"2026-10-08T00:40:00.000Z","positionMs":600000,
                   "attendanceRecorded":true}""",
            ) { joinLiveClass("lc1") },
            Case(
                "joinSession", "POST", "/v1/yoga/join", """{"batchId":"b_0600"}""",
                """{"joinUrl":"https://meet.invalid/live","mode":"EXTERNAL_APP","attendanceRecorded":true,"source":"APP"}""",
            ) { joinSession(JoinSessionRequest("b_0600")) },
            Case(
                "yogaAttendance", "GET", "/v1/yoga/attendance", null, Fixtures.text("yoga-attendance.json"),
            ) { yogaAttendance() },
            Case(
                "setReminderSlot", "PUT", "/v1/yoga/reminder-slot", """{"batchId":"b_0700"}""", """{"ok":true}""",
            ) { setReminderSlot(SetReminderSlotRequest("b_0700")) },
            Case("yogaCatalog", "GET", "/v1/yoga/catalog", null, Fixtures.text("yoga-catalog.yoga.json")) { yogaCatalog() },
            Case(
                "playback", "GET", "/v1/yoga/sessions/s_42/playback", null, Fixtures.text("playback.json"),
            ) { playback("s_42") },
            Case(
                "setSaved", "POST", "/v1/yoga/sessions/s_42/save", """{"saved":false}""", """{"saved":false}""",
            ) { setSaved("s_42", SetSavedRequest(saved = false)) },
            Case(
                "setCompleted", "POST", "/v1/yoga/sessions/s_42/complete", """{"completed":true}""", """{"completed":true}""",
            ) { setCompleted("s_42", SetCompletedRequest(completed = true)) },
            Case("mySessions", "GET", "/v1/yoga/me/sessions", null, Fixtures.text("yoga-mine.json")) { mySessions() },

            // Marathon
            Case(
                "marathonEvents", "GET", "/v1/marathon/events?lat=28.6139&lng=77.209", null,
                Fixtures.text("marathon-events.marathon.json"),
            ) { marathonEvents(lat = 28.6139, lng = 77.209) },
            Case(
                "raceDetail", "GET", "/v1/marathon/events/delhi_half", null, Fixtures.text("race-detail.registered.json"),
            ) { raceDetail("delhi_half") },
            Case(
                "bibToken", "GET", "/v1/marathon/events/delhi_half/bib-token", null, Fixtures.text("bib-token.json"),
            ) { bibToken("delhi_half") },
            Case(
                "updateParticipant", "PATCH", "/v1/marathon/events/delhi_half/participant",
                // eventId travels in the path only; untouched fields are omitted, not nulled.
                """{"tshirtSize":"M"}""", """{"ok":true}""",
            ) { updateParticipant("delhi_half", UpdateParticipantRequest(eventId = "delhi_half", tshirtSize = "M").toBody()) },
            Case("referral", "GET", "/v1/marathon/referral", null, Fixtures.text("referral.json")) { referral() },
            Case(
                "applyReferralCode", "POST", "/v1/marathon/referral/apply", """{"code":"THFRIEND"}""", """{"applied":true}""",
            ) { applyReferralCode(ApplyReferralRequest("THFRIEND")) },
            Case(
                "claimUpgrade", "POST", "/v1/marathon/events/delhi_half/claim-upgrade", null,
                """{"ok":true,"tier":"PREMIUM"}""",
            ) { claimUpgrade("delhi_half") },

            // Orders
            Case(
                "createOrder", "POST", "/v1/orders",
                """{"productType":"MARATHON_REGISTRATION","productId":"delhi_half_21k","eventId":"delhi_half","category":"21K","tier":"CLASSIC"}""",
                """{"orderId":"o_1","gateway":"STUB","gatewayOrderId":"stub_o_1","amountPaise":149900,"currency":"INR","gatewayKeyId":null}""",
            ) {
                createOrder(
                    CreateOrderRequest(
                        productType = ProductType.MARATHON_REGISTRATION,
                        productId = "delhi_half_21k",
                        eventId = "delhi_half",
                        category = "21K",
                        tier = RaceTier.CLASSIC,
                    ),
                )
            },
            Case(
                "orderStatus", "GET", "/v1/orders/o_1", null,
                """{"orderId":"o_1","status":"PAID","entitlementGranted":true}""",
            ) { orderStatus("o_1") },
            Case(
                "simulatePayment", "POST", "/v1/orders/o_1/simulate-payment", null, """{"ok":true,"result":"GRANTED"}""",
            ) { simulatePayment("o_1") },

            // Runs
            Case(
                "uploadRun", "POST", "/v1/runs",
                // routePolyline is required by the server even when null (zod .nullable()).
                """{"id":"$RUN_ID","startedAt":"2026-10-06T00:30:00.000Z","endedAt":"2026-10-06T01:00:00.000Z",""" +
                    """"distanceKm":5.2,"durationSeconds":1800,"avgPaceSecPerKm":346,"caloriesBurned":320,""" +
                    """"routePolyline":null,"hasAccuracyWarning":false}""",
                """{"id":"$RUN_ID","synced":true}""",
            ) {
                uploadRun(
                    UploadRunRequest(
                        id = RUN_ID,
                        startedAt = "2026-10-06T00:30:00.000Z",
                        endedAt = "2026-10-06T01:00:00.000Z",
                        distanceKm = 5.2,
                        durationSeconds = 1800,
                        avgPaceSecPerKm = 346,
                        caloriesBurned = 320,
                        routePolyline = null,
                        hasAccuracyWarning = false,
                    ),
                )
            },
            Case("runHistory", "GET", "/v1/runs", null, Fixtures.text("runs.json")) { runHistory() },
            Case(
                "run", "GET", "/v1/runs/run_1", null,
                """{"id":"run_1","startedAt":"2026-10-06T00:30:00.000Z","endedAt":"2026-10-06T01:00:00.000Z","distanceKm":5.02,"durationSeconds":1800,"avgPaceSecPerKm":358,"caloriesBurned":320,"routePolyline":"_p~iF~ps|U","hasAccuracyWarning":false,"synced":true}""",
            ) { run("run_1") },

            // Diet, workshops, content
            Case(
                "submitDietLead", "POST", "/v1/diet/leads", """{"name":"Asha","phone":"9876543210"}""",
                """{"leadId":"l_1","message":"A dietitian will call you."}""",
            ) { submitDietLead(DietLead(name = "Asha", phone = "9876543210")) },
            Case("workshops", "GET", "/v1/workshops", null, Fixtures.text("workshops.json")) { workshops() },
            Case(
                "setWorkshopRegistration", "POST", "/v1/workshops/w_1/register", """{"registered":true}""",
                """{"registered":true}""",
            ) { setWorkshopRegistration("w_1", SetRegisteredRequest(registered = true)) },
            Case("content", "GET", "/v1/content", null, Fixtures.text("content.json")) { content() },

            // Devices and notifications
            Case(
                "registerPushToken", "POST", "/v1/devices/push-token",
                """{"token":"fcm-token-0123456789","platform":"ANDROID","provider":"FCM"}""", """{"registered":true}""",
            ) { registerPushToken(RegisterPushTokenRequest("fcm-token-0123456789", DevicePlatform.ANDROID, PushProvider.FCM)) },
            Case(
                "removePushToken", "POST", "/v1/devices/push-token/remove",
                """{"token":"fcm-token-0123456789"}""", """{"removed":true}""",
            ) { removePushToken(RemovePushTokenRequest("fcm-token-0123456789")) },
            Case(
                "notifications", "GET", "/v1/notifications", null, Fixtures.text("notifications.json"),
            ) { notifications() },
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): List<Case> = cases
    }

    private val rig = ApiTestRig()

    @After
    fun tearDown() = rig.close()

    @Test
    fun `sends the route's method, path and body, and decodes its response`() = runTest {
        rig.enqueue(body = case.response)

        val decoded = case.call(rig.api)

        val request = rig.takeRequest()
        assertWithMessage("method").that(request.method).isEqualTo(case.method)
        assertWithMessage("path").that(request.path).isEqualTo(case.path)
        val sent = request.body.readUtf8()
        if (case.requestBody == null) {
            assertWithMessage("request body").that(sent).isEmpty()
        } else {
            assertWithMessage("request body").that(json(sent)).isEqualTo(json(case.requestBody))
            assertWithMessage("content type").that(request.getHeader("Content-Type")).startsWith("application/json")
        }
        // The decoded value is what ApiJson makes of the same response: nothing lost or altered.
        val expected = ApiJson.decodeFromString(
            ApiJson.serializersModule.serializer(decoded.javaClass),
            case.response,
        )
        assertThat(decoded).isEqualTo(expected)
    }

    private fun json(text: String): JsonElement = ApiJson.parseToJsonElement(text)
}
