package timeshealth.app.ui.login

import androidx.compose.runtime.Immutable

/** A QA persona: the label on the login card and the dev token the server maps to a seeded user. */
@Immutable
data class DevPersona(val label: String, val token: String)

/**
 * QA personas, verbatim from apps/mobile/src/store/session.ts `DEV_PERSONAS`
 * (mirroring apps/api/prisma/personas.ts). Token shape: `uid|email|phone`.
 *
 * Shown on the login screen only when [timeshealth.app.AppBuildInfo.personasEnabled]
 * (debug builds, DEV_SIGNIN test APKs), and accepted only by a server running
 * with ALLOW_DEV_TOKENS=true, which production refuses. The design's
 * persona switcher (QaLabSheetKt) is deliberately NOT ported.
 */
val DEV_PERSONAS: List<DevPersona> = listOf(
    DevPersona("Free user", "qa_free|free@th.test|+919000000001"),
    DevPersona("Yoga subscriber", "qa_yoga|yoga@th.test|+919000000002"),
    DevPersona("Marathon registrant", "qa_marathon|marathon@th.test|+919000000003"),
    DevPersona("Yoga + Marathon", "qa_both|both@th.test|+919000000004"),
    DevPersona("Expired subscriber", "qa_expired|expired@th.test|+919000000005"),
    DevPersona("Race finisher", "qa_finisher|finisher@th.test|+919000000006"),
)
