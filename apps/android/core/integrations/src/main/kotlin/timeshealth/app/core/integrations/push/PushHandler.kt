package timeshealth.app.core.integrations.push

/**
 * PLUG-IN POINT — incoming push messages.
 *
 * Every push (Firebase Cloud Messaging) arrives in the app's messaging
 * service, which offers it to each registered [PushHandler] in [priority]
 * order (highest first). The first handler that returns true has taken it;
 * otherwise the app's default handler shows a notification that opens the
 * message's `route` (e.g. "/race/delhi_half", checked against the app's
 * screens first).
 *
 * Why handlers: campaign pushes scheduled from the GrowthRx panel carry
 * GrowthRx's own payload format and tracking. Its SDK claims those messages,
 * while our own server's reminders keep using the default path.
 *
 * TODAY: no extra handlers — every push uses the default notification.
 *
 * HOW TO PLUG IN GROWTHRX PUSH
 *  1. Write `class GrowthRxPushHandler @Inject constructor(...) : PushHandler`
 *     returning true for GrowthRx payloads (and passing them to its SDK).
 *  2. In app/.../wiring/IntegrationsModule.kt add ONE line:
 *       `@Binds @IntoSet fun growthRxPush(impl: GrowthRxPushHandler): PushHandler`
 *  3. If GrowthRx needs the FCM token, also hand it over in
 *     `onNewToken` of TimesHealthMessagingService.
 */
interface PushHandler {
    /** Higher runs first. The default notification handler is effectively 0. */
    val priority: Int get() = 100

    /** Returns true when this handler has fully dealt with [message]. */
    fun handle(message: PushMessage): Boolean
}

/** A received push, independent of the FCM classes so handlers stay testable. */
data class PushMessage(
    val title: String?,
    val body: String?,
    /** The data payload. Our server sends `kind` and an optional `route`. */
    val data: Map<String, String>,
) {
    val route: String? get() = data["route"]
    val kind: String? get() = data["kind"]
}

/**
 * Offers a push to the plugged-in [PushHandler]s, highest [PushHandler.priority]
 * first, and falls back to [fallback] (the app's own notification) when none
 * takes it. A handler that throws is skipped: one broken SDK must not swallow
 * every class reminder.
 */
class PushRouter @javax.inject.Inject constructor(
    handlers: Set<@JvmSuppressWildcards PushHandler>,
) {
    private val ordered = handlers.sortedByDescending { it.priority }

    /** Returns true when a plugged-in handler took [message]; false when [fallback] ran. */
    fun route(message: PushMessage, fallback: (PushMessage) -> Unit): Boolean {
        val taken = ordered.any { h -> runCatching { h.handle(message) }.getOrDefault(false) }
        if (!taken) fallback(message)
        return taken
    }
}
