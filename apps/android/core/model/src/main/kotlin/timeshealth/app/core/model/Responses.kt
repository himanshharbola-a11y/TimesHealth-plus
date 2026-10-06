/*
 * Small request/response shapes the API uses that packages/types does not declare. The RN app
 * types them inline (apps/mobile/src/api/hooks.ts, src/lib/notifications.ts) or ignores the
 * response entirely. Every shape here is taken from the server route that produces or accepts it
 * (apps/api/src/routes/), which is the source of truth. Conventions as in Domain.kt.
 */
package timeshealth.app.core.model

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// Acknowledgements
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `{ ok: true }`: PUT /yoga/reminder-slot and PATCH /marathon/events/{id}/participant.
 * Typed rather than ignored so a 200 that isn't the API's JSON (a proxy page) is still caught.
 */
@Serializable
data class OkResponse(val ok: Boolean)

/** DELETE /account (session.ts). */
@Serializable
data class DeleteAccountResponse(
    val deleted: Boolean,
    /**
     * False when our data is gone but the identity provider's record could not be deleted (or
     * the account never had one: QA personas, imported legacy users). Support finishes the job
     * from the server log; the app signs out either way.
     */
    val authRecordDeleted: Boolean,
)

// ─────────────────────────────────────────────────────────────────────────────
// Yoga: save / complete (yoga.ts)
//
// Both send the state the user WANTS, never "toggle". The server toggles when the body is
// missing (for older app builds), so a retried or late request would flip the state back. With
// an explicit value a double tap or a retry lands on the same answer.
// ─────────────────────────────────────────────────────────────────────────────

/** POST /yoga/sessions/{id}/save. */
@Serializable
data class SetSavedRequest(val saved: Boolean)

/** Response of POST /yoga/sessions/{id}/save: the state now stored. */
@Serializable
data class SavedResponse(val saved: Boolean)

/** POST /yoga/sessions/{id}/complete. Completing a recording does NOT write attendance (§7.1). */
@Serializable
data class SetCompletedRequest(val completed: Boolean)

/** Response of POST /yoga/sessions/{id}/complete: the state now stored. */
@Serializable
data class CompletedResponse(val completed: Boolean)

// ─────────────────────────────────────────────────────────────────────────────
// Marathon (marathon.ts)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The body of PATCH /marathon/events/{eventId}/participant: [UpdateParticipantRequest] without
 * `eventId`, which travels in the path (RN sends `Omit<UpdateParticipantRequest, 'eventId'>`).
 *
 * Null fields are omitted and left unchanged. An empty string CLEARS a field on the server.
 */
@Serializable
data class UpdateParticipantBody(
    val tshirtSize: String? = null,
    val emergencyContactName: String? = null,
    /** Normalised by the server; 400 INVALID_PHONE if it isn't a callable number. */
    val emergencyContactPhone: String? = null,
)

/** The wire body for this request; its [UpdateParticipantRequest.eventId] goes in the path. */
fun UpdateParticipantRequest.toBody(): UpdateParticipantBody = UpdateParticipantBody(
    tshirtSize = tshirtSize,
    emergencyContactName = emergencyContactName,
    emergencyContactPhone = emergencyContactPhone,
)

/** POST /marathon/referral/apply: a friend's Refer & Win code (4–16 chars, §8.3). */
@Serializable
data class ApplyReferralRequest(val code: String)

/**
 * Response of POST /marathon/referral/apply. `applied` is false when the user already had a
 * code recorded (first code wins). Invalid and own codes are errors (404 / 409), not `false`.
 */
@Serializable
data class ApplyReferralResponse(val applied: Boolean)

/** Response of POST /marathon/events/{id}/claim-upgrade: the free Premium upgrade (§8.3). */
@Serializable
data class ClaimUpgradeResponse(
    val ok: Boolean,
    /** Always PREMIUM today. */
    val tier: RaceTier,
)

// ─────────────────────────────────────────────────────────────────────────────
// Orders (orders.ts)
// ─────────────────────────────────────────────────────────────────────────────

/** The server's `SettleResult`: what settling a payment did. */
@Serializable(with = SettleResult.Serializer::class)
enum class SettleResult {
    GRANTED, ALREADY_GRANTED, FAILED, NOT_FOUND, NEEDS_REFUND, UNKNOWN;

    internal object Serializer : ForwardCompatibleEnumSerializer<SettleResult>(entries, UNKNOWN)
}

/**
 * Response of POST /orders/{id}/simulate-payment (development only; 404 in production). The
 * client still polls GET /orders/{id} for the outcome: entitlement is the server's call.
 */
@Serializable
data class SimulatePaymentResponse(
    val ok: Boolean,
    val result: SettleResult,
)

// ─────────────────────────────────────────────────────────────────────────────
// Runs and workshops
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Response of POST /runs (runs.ts). Re-uploading an existing id is a no-op that answers the same,
 * which is what makes the upload safe to retry. RN types it as `{ id }` and drops `synced`.
 */
@Serializable
data class UploadRunResponse(
    val id: String,
    val synced: Boolean,
)

/**
 * Response of POST /workshops/{id}/register (content.ts). The endpoint TOGGLES: a second call
 * cancels a free seat, so `registered` is the state after this call.
 */
@Serializable
data class WorkshopRegistrationResponse(val registered: Boolean)

/**
 * Body of POST /workshops/{id}/register: the state the user WANTS, so a double
 * tap or a retry can't cancel the seat it just booked.
 */
@Serializable
data class SetRegisteredRequest(val registered: Boolean)

// ─────────────────────────────────────────────────────────────────────────────
// Push tokens (devices.ts)
//
// These two enums only ever travel in requests, so they have no UNKNOWN entry: nothing decodes
// them, and an UNKNOWN would be a value the server rejects with 400.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
enum class DevicePlatform { ANDROID, IOS }

@Serializable
enum class PushProvider { EXPO, FCM, APNS }

/**
 * POST /devices/push-token. The server upserts on the token, not the user, so a reinstall or a
 * second account on the same phone moves the token instead of duplicating it.
 */
@Serializable
data class RegisterPushTokenRequest(
    /** 10–500 chars. */
    val token: String,
    val platform: DevicePlatform,
    val provider: PushProvider,
)

@Serializable
data class RegisterPushTokenResponse(val registered: Boolean)

/**
 * POST /devices/push-token/remove, called on sign-out so a shared phone stops receiving the
 * previous user's alerts. A POST with a body rather than a DELETE with the token in the URL:
 * URLs end up in access logs.
 */
@Serializable
data class RemovePushTokenRequest(val token: String)

@Serializable
data class RemovePushTokenResponse(val removed: Boolean)
