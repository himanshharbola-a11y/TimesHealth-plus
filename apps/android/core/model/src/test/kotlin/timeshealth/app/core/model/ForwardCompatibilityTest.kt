package timeshealth.app.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test
import timeshealth.app.core.model.HeroSlotKind.MY_RACE
import timeshealth.app.core.model.HeroSlotKind.YOGA_SESSION

/**
 * The server ships new values before apps update. These tests take copies of real staging
 * responses, edit them the way a newer server would, and check that decoding still succeeds:
 * unknown union members become `Unknown` and are skipped, and unknown enum values become
 * `UNKNOWN`.
 */
class ForwardCompatibilityTest {

    // ── JSON editing helpers ────────────────────────────────────────────────

    private fun JsonObject.with(vararg entries: Pair<String, JsonElement>) = JsonObject(this + entries)
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.arr(key: String) = getValue(key).jsonArray
    private fun str(value: String) = JsonPrimitive(value)

    private fun MutableList<JsonElement>.editFirst(type: String, edit: (JsonObject) -> JsonObject) {
        val i = indexOfFirst { (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == type }
        this[i] = edit(this[i].jsonObject)
    }

    /** home.both.json as a future server might send it. */
    private fun newerHomeFeed(): JsonObject {
        val home = Fixtures.tree("home.both.json")
        val components = home.arr("components").toMutableList()

        // Hero: a new slot kind between the two known ones, and a new yoga session state.
        components.editFirst("HERO_STACK") { hero ->
            val (session, race) = hero.arr("slots").map { it.jsonObject }
            hero.with(
                "slots" to JsonArray(
                    listOf(
                        session.with("state" to str("ON_BREAK")),
                        buildJsonObject {
                            put("kind", "WEATHER_ALERT")
                            put("title", "Heavy rain in Delhi")
                            put("ctaLabel", "See the indoor plan")
                        },
                        race,
                    ),
                ),
            )
        }
        // A new card format and an extra field on a known rail.
        components.editFirst("VIDEO_RAIL") { it.with("cardFormat" to str("VIDEO_CIRCLE"), "layout" to str("GRID")) }
        // A new workshop category inside a known rail.
        components.editFirst("WORKSHOP_RAIL") { rail ->
            val items = rail.arr("items").map { it.jsonObject }
            rail.with("items" to JsonArray(listOf(items[0].with("category" to str("MEDITATION"))) + items.drop(1)))
        }
        // A new action type on the promo strip.
        components.editFirst("PROMO_STRIP") {
            it.with("action" to buildJsonObject { put("type", "OPEN_CHAT"); put("threadId", "dietitian") })
        }
        // A known action whose discriminator comes last, with a field this build doesn't know.
        components.editFirst("ENTRY_TILE") {
            it.with(
                "action" to buildJsonObject {
                    put("url", "https://timeshealthplus.invalid/run")
                    put("utm", "home")
                    put("type", "OPEN_EXTERNAL")
                },
            )
        }
        // New component types: one right after the hero, one with no type, and a non-object.
        components.add(
            1,
            buildJsonObject {
                put("type", "STORY_CAROUSEL")
                put("id", "stories")
                putJsonArray("items") { addJsonObject { put("id", "s1") } }
            },
        )
        components.add(buildJsonObject { put("id", "typeless") })
        components.add(JsonPrimitive(42))

        return home.with("components" to JsonArray(components), "experiments" to JsonArray(listOf(str("feed_v2"))))
    }

    // ── Feed: unknown component types, hero kinds and action types ──────────

    @Test
    fun `newer home feed decodes - unknown components and hero slots are skipped`() {
        val original = Fixtures.decode<HomeFeedResponse>("home.both.json")
        val feed = ApiJson.decodeFromJsonElement<HomeFeedResponse>(newerHomeFeed())

        assertThat(feed.components[1]).isEqualTo(FeedComponent.Unknown("STORY_CAROUSEL"))
        assertThat(feed.components.takeLast(2)).containsExactly(FeedComponent.Unknown(null), FeedComponent.Unknown(null))
        assertThat(feed.components).hasSize(original.components.size + 3)
        // What the renderer sees is exactly today's feed, in order.
        assertThat(feed.knownComponents.map { it.id }).containsExactlyElementsIn(original.knownComponents.map { it.id }).inOrder()

        val hero = feed.knownComponents.filterIsInstance<HeroStackComponent>().single()
        assertThat(hero.slots).hasSize(3)
        assertThat(hero.slots[1]).isEqualTo(HeroSlot.Unknown("WEATHER_ALERT"))
        assertThat(hero.knownSlots.map { it.kind }).containsExactly(YOGA_SESSION, MY_RACE).inOrder()
    }

    @Test
    fun `newer home feed decodes - unknown action types and enum values fall back`() {
        val feed = ApiJson.decodeFromJsonElement<HomeFeedResponse>(newerHomeFeed())

        val promo = feed.knownComponents.filterIsInstance<PromoStripComponent>().single()
        assertThat(promo.action).isEqualTo(FeedAction.Unknown("OPEN_CHAT"))

        val tile = feed.knownComponents.filterIsInstance<EntryTileComponent>().single()
        assertThat(tile.action).isEqualTo(FeedAction.OpenExternal("https://timeshealthplus.invalid/run"))

        val session = feed.knownComponents.filterIsInstance<HeroStackComponent>().single().knownSlots[0] as HeroYogaSession
        assertThat(session.state).isEqualTo(HeroSessionState.UNKNOWN)
        assertThat(session.batchId).isEqualTo("b5")

        val rail = feed.knownComponents.filterIsInstance<VideoRailComponent>().first()
        assertThat(rail.cardFormat).isEqualTo(CardFormat.UNKNOWN)
        assertThat(rail.items).isNotEmpty()

        val workshops = feed.knownComponents.filterIsInstance<WorkshopRailComponent>().single().items
        assertThat(workshops.map { it.category }.first()).isEqualTo(WorkshopCategory.UNKNOWN)
        assertThat(workshops.drop(1).map { it.category }).doesNotContain(WorkshopCategory.UNKNOWN)
    }

    @Test
    fun `unknown members survive a cache round trip`() {
        val feed = ApiJson.decodeFromJsonElement<HomeFeedResponse>(newerHomeFeed())

        val encoded = ApiJson.encodeToJsonElement(feed).jsonObject
        assertThat(encoded.arr("components")[1]).isEqualTo(buildJsonObject { put("type", "STORY_CAROUSEL") })
        assertThat(ApiJson.decodeFromJsonElement<HomeFeedResponse>(encoded)).isEqualTo(feed)
    }

    @Test
    fun `every action type and its unknown fallback`() {
        fun action(json: String) = ApiJson.decodeFromString<FeedAction>(json)

        assertThat(action("""{"type":"OPEN_TAB","tab":"YOGA"}""")).isEqualTo(FeedAction.OpenTab(AppTab.YOGA))
        assertThat(action("""{"type":"OPEN_TAB","tab":"SHOP"}""")).isEqualTo(FeedAction.OpenTab(AppTab.UNKNOWN))
        assertThat(action("""{"type":"OPEN_RUN_TRACKER","mode":"indoor"}""")).isEqualTo(FeedAction.OpenRunTracker)
        assertThat(action("""{"type":"OPEN_YOGA_SESSION","sessionId":"s1"}""")).isEqualTo(FeedAction.OpenYogaSession("s1"))
        assertThat(action("""{"type":"OPEN_YOGA_EXPLORER","categoryId":null}""")).isEqualTo(FeedAction.OpenYogaExplorer(null))
        assertThat(action("""{"type":"OPEN_YOGA_EXPLORER"}""")).isEqualTo(FeedAction.OpenYogaExplorer(null))
        assertThat(action("""{"type":"JOIN_LIVE_SESSION","sessionId":"s1","batchId":"b1"}"""))
            .isEqualTo(FeedAction.JoinLiveSession("s1", "b1"))
        assertThat(action("""{"type":"OPEN_RACE_DETAIL","eventId":"e"}""")).isEqualTo(FeedAction.OpenRaceDetail("e"))
        assertThat(action("""{"type":"OPEN_RACE_RESULTS","eventId":"e"}""")).isEqualTo(FeedAction.OpenRaceResults("e"))
        assertThat(action("""{"type":"OPEN_DIGITAL_BIB","eventId":"e"}""")).isEqualTo(FeedAction.OpenDigitalBib("e"))
        assertThat(action("""{"type":"OPEN_WORKSHOP","workshopId":"w"}""")).isEqualTo(FeedAction.OpenWorkshop("w"))
        assertThat(action("""{"type":"OPEN_PAYWALL","productId":"p"}""")).isEqualTo(FeedAction.OpenPaywall("p"))
        assertThat(action("""{"type":"OPEN_DIET_LEAD_FORM"}""")).isEqualTo(FeedAction.OpenDietLeadForm)
        assertThat(action("""{"type":"OPEN_ARTICLE","url":"u"}""")).isEqualTo(FeedAction.OpenArticle("u"))
        assertThat(action("""{"type":"OPEN_EXTERNAL","url":"u"}""")).isEqualTo(FeedAction.OpenExternal("u"))
        assertThat(action("""{"type":"OPEN_CHAT","threadId":"t"}""")).isEqualTo(FeedAction.Unknown("OPEN_CHAT"))
        assertThat(action("""{"type":7}""")).isEqualTo(FeedAction.Unknown(null))
        assertThat(action("""{}""")).isEqualTo(FeedAction.Unknown(null))
    }

    @Test
    fun `union fallback is built into the types, not into ApiJson`() {
        // Json.Default rejects unknown keys, yet unknown members still decode, because the
        // fallback lives in the type's serializer and doesn't depend on ApiJson settings.
        assertThat(Json.decodeFromString<FeedAction>("""{"type":"OPEN_CHAT","threadId":"t"}"""))
            .isEqualTo(FeedAction.Unknown("OPEN_CHAT"))
        assertThat(Json.decodeFromString<HeroSlot>("""{"kind":"WEATHER_ALERT","title":"x"}"""))
            .isEqualTo(HeroSlot.Unknown("WEATHER_ALERT"))
        assertThat(Json.decodeFromString<FeedComponent>("""{"type":"STORY_CAROUSEL","id":"s"}"""))
            .isEqualTo(FeedComponent.Unknown("STORY_CAROUSEL"))
    }

    @Test
    fun `missing optional keys and lists parse with defaults`() {
        val rail = ApiJson.decodeFromString<FeedComponent>("""{"type":"VIDEO_RAIL","id":"r","title":"T"}""")
        assertThat(rail).isEqualTo(VideoRailComponent(id = "r", title = "T"))
        rail as VideoRailComponent
        assertThat(rail.items).isEmpty()
        assertThat(rail.actionLabel).isNull()
        assertThat(rail.cardFormat).isEqualTo(CardFormat.VIDEO_LANDSCAPE)

        val stack = ApiJson.decodeFromString<FeedComponent>("""{"type":"HERO_STACK","id":"hero"}""") as HeroStackComponent
        assertThat(stack.knownSlots).isEmpty()

        val slot = ApiJson.decodeFromString<HeroSlot>(
            """{"kind":"YOGA_SESSION","title":"t","ctaLabel":"c","sessionId":"s","batchId":"b","state":"LIVE",
               "startsAt":"2026-10-06T11:15:00.000Z","instructorName":"i","durationMinutes":60}""",
        ) as HeroYogaSession
        assertThat(slot.subtitle).isNull()
        assertThat(slot.secondsToStart).isNull()
        assertThat(slot.instructorAvatarUrl).isNull()
    }

    // ── Session: every closed union on the entitlement spine ─────────────────

    @Test
    fun `newer session with unknown persona, statuses, tier and a diet entitlement decodes`() {
        val session = Fixtures.tree("session.both.json")
        val profile = session.obj("profile")
        val entitlements = session.obj("entitlements")
        val race = entitlements.arr("marathon")[0].jsonObject
        val newer = session.with(
            "profile" to profile.with(
                "concern" to str("ANKLES"),
                "healthGoal" to str("LONGEVITY"),
                "units" to str("SI"),
                "avatarUrl" to str("https://x/avatar.png"),
            ),
            "entitlements" to entitlements.with(
                "yoga" to entitlements.obj("yoga").with("status" to str("PAUSED")),
                "marathon" to JsonArray(listOf(race.with("tier" to str("ELITE"), "status" to str("DNF")))),
                "diet" to buildJsonObject { put("planId", "diet_basic"); put("active", true) },
            ),
            "persona" to session.obj("persona").with("persona" to str("FAMILY_PLAN")),
            "featureFlags" to buildJsonObject { put("newCheckout", true) },
        )

        val decoded = ApiJson.decodeFromJsonElement<SessionResponse>(newer)

        assertThat(decoded.persona.persona).isEqualTo(UserPersona.UNKNOWN)
        assertThat(decoded.persona.hasYoga).isTrue()
        assertThat(decoded.entitlements.yoga?.status).isEqualTo(YogaStatus.UNKNOWN)
        assertThat(decoded.entitlements.yoga?.active).isTrue()
        assertThat(decoded.entitlements.marathon.single().tier).isEqualTo(RaceTier.UNKNOWN)
        assertThat(decoded.entitlements.marathon.single().status).isEqualTo(RaceLifecycleStatus.UNKNOWN)
        assertThat(decoded.entitlements.marathon.single().bibNumber).isEqualTo("DEL-4410B")
        assertThat(decoded.entitlements.diet).isNotNull()
        assertThat(decoded.profile.concern).isEqualTo(Concern.UNKNOWN)
        assertThat(decoded.profile.healthGoal).isEqualTo(HealthGoal.UNKNOWN)
        assertThat(decoded.profile.units).isEqualTo(Units.UNKNOWN)
        assertThat(decoded.profile.name).isEqualTo("Bhavna Both")
    }

    @Test
    fun `newer race detail with a new tier, kit status and section decodes`() {
        val detail = Fixtures.tree("race-detail.registered.json")
        val event = detail.obj("event")
        val options = event.arr("distanceOptions").map { option ->
            val prices = option.jsonObject.obj("pricePaise")
            option.jsonObject.with("pricePaise" to prices.with("ELITE" to JsonPrimitive(999_900)))
        }
        val newer = detail.with(
            "event" to event.with("distanceOptions" to JsonArray(options)),
            "kit" to detail.obj("kit").with("status" to str("LOST_IN_TRANSIT")),
            "weather" to buildJsonObject { put("forecast", "rain") },
        )

        val decoded = ApiJson.decodeFromJsonElement<RaceDetailResponse>(newer)

        val prices = decoded.event.distanceOptions.single { it.code == "21K" }.pricePaise
        assertThat(prices).containsEntry(RaceTier.CLASSIC, 317_700L)
        assertThat(prices).containsEntry(RaceTier.PREMIUM, 519_900L)
        assertThat(prices).containsEntry(RaceTier.UNKNOWN, 999_900L)
        assertThat(decoded.kit?.status).isEqualTo(KitStatus.UNKNOWN)
        assertThat(decoded.upgradeOffer?.netDifferencePaise).isEqualTo(202_200L)
    }

    // ── Other closed unions ─────────────────────────────────────────────────

    @Test
    fun `unknown notification kind maps to UNKNOWN and keeps its route`() {
        val inbox = ApiJson.decodeFromString<NotificationListResponse>(
            """{"items":[
                {"id":"n1","kind":"SESSION_LIVE","title":"Live now","body":"Join","route":"/yoga","sentAt":"2026-10-06T08:00:00.000Z"},
                {"id":"n2","kind":"STREAK_MILESTONE","title":"7 days!","body":"Keep going","sentAt":"2026-10-05T08:00:00.000Z"}
            ]}""",
        )

        assertThat(inbox.items.map { it.kind }).containsExactly(NotificationKind.SESSION_LIVE, NotificationKind.UNKNOWN).inOrder()
        assertThat(inbox.items[1].route).isNull()
    }

    @Test
    fun `unknown order status, gateway, currency, join mode and attendance source map to UNKNOWN`() {
        val status = ApiJson.decodeFromString<OrderStatusResponse>(
            """{"orderId":"o1","status":"REFUNDED","entitlementGranted":false}""",
        )
        assertThat(status.status).isEqualTo(OrderStatus.UNKNOWN)

        val order = ApiJson.decodeFromString<CreateOrderResponse>(
            """{"orderId":"o1","gateway":"STRIPE","gatewayOrderId":"g1","amountPaise":29900,"currency":"USD","gatewayKeyId":null}""",
        )
        assertThat(order.gateway).isEqualTo(PaymentGateway.UNKNOWN)
        assertThat(order.currency).isEqualTo(Currency.UNKNOWN)
        assertThat(order.amountPaise).isEqualTo(29_900L)

        val join = ApiJson.decodeFromString<JoinSessionResponse>(
            """{"joinUrl":"https://x","mode":"PICTURE_IN_PICTURE","attendanceRecorded":true,"source":"KIOSK"}""",
        )
        assertThat(join.mode).isEqualTo(JoinMode.UNKNOWN)
        assertThat(join.source).isEqualTo(AttendanceSource.UNKNOWN)

        val list = ApiJson.decodeFromString<MarathonListResponse>("""{"events":[],"orderedBy":"POPULARITY"}""")
        assertThat(list.orderedBy).isEqualTo(EventOrdering.UNKNOWN)
    }

    @Test
    fun `UNKNOWN encodes as the literal UNKNOWN - the raw value is not kept`() {
        assertThat(ApiJson.encodeToString(OrderStatus.serializer(), OrderStatus.UNKNOWN)).isEqualTo("\"UNKNOWN\"")
        assertThat(ApiJson.decodeFromString(OrderStatus.serializer(), "\"UNKNOWN\"")).isEqualTo(OrderStatus.UNKNOWN)
    }

    // ── Error body ──────────────────────────────────────────────────────────

    @Test
    fun `validation errors decode in the server's zod shape and in the TS shape`() {
        // What apps/api actually sends: zod's flatten().fieldErrors, an array per field.
        val zod = ApiJson.decodeFromString<ApiError>(
            """{"code":"INVALID_BODY","message":"Invalid lead payload",
                "fields":{"phone":["String must contain at least 8 character(s)"],"name":["Required","Too short"]}}""",
        )
        assertThat(zod.fieldMessage("phone")).isEqualTo("String must contain at least 8 character(s)")
        assertThat(zod.fields?.get("name")).containsExactly("Required", "Too short").inOrder()
        assertThat(zod.fieldMessage("email")).isNull()

        // What packages/types declares: a string per field.
        val ts = ApiJson.decodeFromString<ApiError>("""{"code":"INVALID_BODY","message":"m","fields":{"phone":"Enter a valid number"}}""")
        assertThat(ts.fields).containsExactly("phone", listOf("Enter a valid number"))

        // Any other status code, with a field this build doesn't know.
        val conflict = ApiJson.decodeFromString<ApiError>("""{"code":"LOGIN_IDENTIFIER","message":"m","retryAfter":30}""")
        assertThat(conflict.fields).isNull()
    }
}
