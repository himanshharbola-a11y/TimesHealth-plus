package timeshealth.app.core.domain

import java.time.Instant

/*
 * When a live yoga class can be joined. The app's rule (joinClass.ts
 * isJoinOpen) and the server's (apps/api/src/time.ts inJoinWindow) are the
 * same formula; here they are literally one function, so what the app offers
 * and what the server counts can never disagree.
 */

/** Yoga batches run one hour (PRD §2). */
const val YOGA_BATCH_DURATION_MINUTES: Long = 60L

/**
 * How early the class link opens ("Join Wait Room" on the Home hero). Shared
 * by the hero and the join endpoint.
 */
const val WAIT_ROOM_MINUTES: Long = 60L

/** [WAIT_ROOM_MINUTES] in ms — the TS app's `WAIT_ROOM_MS`. */
const val WAIT_ROOM_MS: Long = WAIT_ROOM_MINUTES * MS_PER_MINUTE

/** [YOGA_BATCH_DURATION_MINUTES] in ms — the TS app's `CLASS_MS`. */
const val CLASS_MS: Long = YOGA_BATCH_DURATION_MINUTES * MS_PER_MINUTE

/**
 * True from the wait room opening (1 h before [startMs]) until the class ends
 * (1 h after): `start − 60 min ≤ now < start + 60 min`. Inclusive at the
 * opening, exclusive at the end.
 *
 * A join in this window counts as attendance (PRD §7.1: joining late still
 * counts); outside it there is no class to attend, so a tap at 10 PM for
 * tomorrow's batch must not write a streak day — the server refuses it, so
 * the app never offers it. Pass the SERVER clock ([ServerClock.nowMs]).
 */
fun isJoinOpen(startMs: Long, nowMs: Long): Boolean =
    nowMs >= startMs - WAIT_ROOM_MS && nowMs < startMs + CLASS_MS

/** [isJoinOpen] for the API's `startsAt`. An unparseable value is closed (the TS compared NaN: false). */
fun isJoinOpen(startsAtIso: String, nowMs: Long): Boolean {
    val start = parseIsoInstant(startsAtIso) ?: return false
    return isJoinOpen(start.toEpochMilli(), nowMs)
}

/** The server's name and signature for the same rule (apps/api/src/time.ts). */
fun inJoinWindow(start: Instant, now: Instant): Boolean = isJoinOpen(start.toEpochMilli(), now.toEpochMilli())

/** The class itself is running: `start ≤ now < start + 60 min` (the "LIVE" badge). */
fun isLiveNow(startMs: Long, nowMs: Long): Boolean = nowMs >= startMs && nowMs < startMs + CLASS_MS

/** [isLiveNow] with the server's signature. */
fun isLiveNow(start: Instant, now: Instant): Boolean = isLiveNow(start.toEpochMilli(), now.toEpochMilli())
