package timeshealth.server.logging

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.pattern.ExtendedThrowableProxyConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy

/**
 * Last line of defence for docs/04 T10 ("tokens, OTPs, phone numbers and coordinates never reach
 * logs"), mirroring app.ts's pino serializers:
 *  - the request log line carries the path WITHOUT its query string (queries carry coordinates,
 *    /marathon/events?lat=…, and tokens, /yoga/wa-join?t=…);
 *  - the Authorization header is never logged.
 *
 * RequestLogFilter already logs only the path and never headers. These converters scrub every
 * other message too (framework logs, exception messages), so a stray URL or bearer token in an
 * error cannot leak.
 */
object LogRedaction {
    private val BEARER = Regex("(?i)(bearer\\s+)[^\\s\"',;]+")

    /** `?query` after an absolute URL or an absolute path. The path itself is kept. */
    private val QUERY = Regex("((?:https?://[^\\s\"'?#]+)|(?:/[^\\s\"'?#]*))\\?[^\\s\"'#]*")

    /** Persona tokens: "qa_x|email|phone". */
    private val PERSONA = Regex("\\bqa_[\\w-]+\\|[^\\s\"']*")

    fun scrub(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        if (out.contains('?')) out = QUERY.replace(out, "$1")
        if (out.contains("earer", ignoreCase = true)) out = BEARER.replace(out, "$1[redacted]")
        if (out.contains('|')) out = PERSONA.replace(out, "[redacted-token]")
        return out
    }
}

/** `%safeMsg`: the formatted message, scrubbed. */
class SafeMessageConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String = LogRedaction.scrub(event.formattedMessage ?: "")
}

/** `%safeEx`: the stack trace, scrubbed (exception messages can quote URLs). */
class SafeThrowableConverter : ExtendedThrowableProxyConverter() {
    override fun throwableProxyToString(tp: IThrowableProxy): String =
        LogRedaction.scrub(super.throwableProxyToString(tp))
}
