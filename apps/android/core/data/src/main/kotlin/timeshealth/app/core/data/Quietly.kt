package timeshealth.app.core.data

import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [block] and returns null if it fails, for the steps the RN app ran as `.catch(() =>
 * undefined)`: best-effort work (push, the inbox marker, the offline pass) that must never stop
 * a sign-out or a screen.
 *
 * Cancellation is re-thrown, never swallowed: a cancelled coroutine has to stop, not carry on as
 * if the step had merely failed.
 */
internal inline fun <T> quietly(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
