package timeshealth.server.session

import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import timeshealth.app.core.model.AppConfigResponse
import timeshealth.app.core.model.DeleteAccountResponse
import timeshealth.app.core.model.ProfileResponse
import timeshealth.app.core.model.SessionResponse
import timeshealth.server.db.entity.UserEntity
import timeshealth.server.json.RawBody
import timeshealth.server.security.CurrentUser

/**
 * apps/api/src/routes/session.ts. Every response type is the Android app's own model
 * (timeshealth.app.core.model), so the two cannot drift.
 *
 * Not here yet: GET /v1/home (it is built by services/feed.ts; ported with the feed).
 */
@RestController
@RequestMapping("/v1")
class SessionController(private val sessions: SessionService) {

    /** Public: no auth (the update gate must work before sign-in). */
    @GetMapping("/config")
    fun config(): AppConfigResponse = sessions.config()

    @GetMapping("/session")
    fun session(@CurrentUser user: UserEntity): SessionResponse = sessions.session(user)

    @PostMapping("/onboarding/step")
    fun onboardingStep(body: RawBody, @CurrentUser user: UserEntity): ProfileResponse =
        ProfileResponse(sessions.onboardingStep(user, body))

    @PostMapping("/onboarding/skip")
    fun onboardingSkip(@Suppress("UNUSED_PARAMETER") body: RawBody, @CurrentUser user: UserEntity): ProfileResponse =
        ProfileResponse(sessions.skipOnboarding(user))

    @PatchMapping("/profile")
    fun updateProfile(body: RawBody, @CurrentUser user: UserEntity): ProfileResponse =
        ProfileResponse(sessions.updateProfile(user, body))

    @DeleteMapping("/account")
    fun deleteAccount(@Suppress("UNUSED_PARAMETER") body: RawBody, @CurrentUser user: UserEntity): DeleteAccountResponse =
        sessions.deleteAccount(user)
}
