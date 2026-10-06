package timeshealth.app.core.data

import timeshealth.app.core.model.CreateOrderRequest
import timeshealth.app.core.model.CreateOrderResponse
import timeshealth.app.core.model.Currency
import timeshealth.app.core.model.DigitalBib
import timeshealth.app.core.model.HomeFeedResponse
import timeshealth.app.core.model.MarathonEvent
import timeshealth.app.core.model.NotificationItem
import timeshealth.app.core.model.NotificationKind
import timeshealth.app.core.model.OrderStatus
import timeshealth.app.core.model.OrderStatusResponse
import timeshealth.app.core.model.PaymentGateway
import timeshealth.app.core.model.ProductType
import timeshealth.app.core.model.ProfileResponse
import timeshealth.app.core.model.RaceDetailResponse
import timeshealth.app.core.model.RaceExpoInfo
import timeshealth.app.core.model.RaceTier
import timeshealth.app.core.model.Units
import timeshealth.app.core.model.UserProfile

/** A fixed "now" for clocks in tests: 2026-10-06T06:00:00Z. */
const val NOW_MS = 1_791_266_400_000L

private const val DAY_S = 86_400L

/** A signed offline payload `ref.userId.expires.sig`, expiring [expiresInS] seconds after [NOW_MS]. */
fun offlinePayload(expiresInS: Long = 7 * DAY_S): String = "REF123.user_1.${NOW_MS / 1000 + expiresInS}.c2ln"

fun bib(payload: String = offlinePayload()) = DigitalBib(
    bibNumber = "A1024",
    participantName = "Asha Rao",
    category = "21K",
    tier = RaceTier.PREMIUM,
    eventName = "Delhi Half Marathon",
    qrToken = "qr-token-short-lived",
    qrExpiresAt = "2026-10-06T06:01:00.000Z",
    offlinePayload = payload,
)

fun expo(
    venue: String = "JLN Stadium, Gate 4",
    instructions: String = "Bring photo ID.",
    documents: List<String> = listOf("Photo ID", "Registration email"),
) = RaceExpoInfo(
    venue = venue,
    address = "Lodhi Road, New Delhi",
    startsAt = "2026-10-24T04:30:00.000Z",
    endsAt = "2026-10-24T12:30:00.000Z",
    pickupWindow = "10 AM to 6 PM",
    instructions = instructions,
    requiredDocuments = documents,
)

fun marathonEvent(id: String = "delhi_half", expo: RaceExpoInfo? = expo()) = MarathonEvent(
    id = id,
    name = "Delhi Half Marathon",
    city = "New Delhi",
    venue = "JLN Stadium",
    imageUrl = "https://img.example/delhi.jpg",
    startsAt = "2026-10-26T00:30:00.000Z",
    flagOffTime = "2026-10-26T00:45:00.000Z",
    distanceOptions = emptyList(),
    registrationOpen = true,
    expo = expo,
)

fun raceDetail(eventId: String = "delhi_half", bib: DigitalBib? = bib()) =
    RaceDetailResponse(event = marathonEvent(eventId), bib = bib)

fun orderRequest(
    productType: ProductType = ProductType.MARATHON_REGISTRATION,
    productId: String = "delhi_half",
    referralCode: String? = null,
) = CreateOrderRequest(
    productType = productType,
    productId = productId,
    eventId = "delhi_half",
    category = "21K",
    tier = RaceTier.PREMIUM,
    referralCode = referralCode,
)

fun created(orderId: String, gateway: PaymentGateway = PaymentGateway.STUB) = CreateOrderResponse(
    orderId = orderId,
    gateway = gateway,
    gatewayOrderId = "gw_$orderId",
    amountPaise = 149_900,
    currency = Currency.INR,
)

fun status(orderId: String, status: OrderStatus, granted: Boolean = false) =
    OrderStatusResponse(orderId = orderId, status = status, entitlementGranted = granted)

fun notification(id: String, sentAt: String) = NotificationItem(
    id = id,
    kind = NotificationKind.SESSION_REMINDER,
    title = "Class at 6",
    body = "Your batch starts soon",
    sentAt = sentAt,
)

fun homeFeed(greeting: String = "Good morning") = HomeFeedResponse(
    greeting = greeting,
    serverTime = "2026-10-06T06:00:00.000Z",
    ttlSeconds = 30,
)

fun profileResponse() = ProfileResponse(
    UserProfile(
        id = "user_1",
        emailIsLogin = true,
        phoneIsLogin = false,
        profileCompletion = 60,
        onboardingCompleted = true,
        units = Units.METRIC,
        locale = "en-IN",
    ),
)
