package timeshealth.server.config

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Turns the Prisma-style `DATABASE_URL` (postgresql://user:pass@host:port/db?params) that the Node
 * server reads into a JDBC URL plus credentials, so both servers run from the same variable.
 *
 * Query parameters are translated where Prisma and pgjdbc spell them differently, Prisma-only
 * pool settings are dropped, and everything else is passed through (pgjdbc ignores keys it does
 * not know).
 */
object DatabaseUrl {
    /** env.ts: `required('DATABASE_URL', <local docker-compose>)`. */
    const val LOCAL_DEFAULT = "postgresql://timeshealth:timeshealth_dev@localhost:5433/timeshealth"

    data class Jdbc(val url: String, val username: String?, val password: String?)

    private val RENAMED = mapOf(
        "schema" to "currentSchema",
        "connect_timeout" to "connectTimeout",
        "socket_timeout" to "socketTimeout",
        "application_name" to "ApplicationName",
        "channel_binding" to "channelBinding",
    )

    /** Prisma connection-pool and engine settings with no pgjdbc meaning (Hikari owns the pool). */
    private val PRISMA_ONLY = setOf(
        "connection_limit", "pool_timeout", "statement_cache_size", "sslaccept", "sslidentity",
        "max_connection_lifetime", "max_idle_connection_lifetime",
    )

    fun toJdbc(raw: String): Jdbc {
        if (raw.startsWith("jdbc:")) return Jdbc(raw, null, null)
        val uri = URI(raw)
        require(uri.scheme == "postgresql" || uri.scheme == "postgres") {
            "DATABASE_URL must be a postgresql:// URL (got scheme \"${uri.scheme}\")"
        }
        val host = requireNotNull(uri.host) { "DATABASE_URL has no host" }
        val port = if (uri.port == -1) 5432 else uri.port
        val database = decode(uri.rawPath.orEmpty().removePrefix("/"))

        var username: String? = null
        var password: String? = null
        uri.rawUserInfo?.let { info ->
            val colon = info.indexOf(':')
            if (colon < 0) {
                username = decode(info)
            } else {
                username = decode(info.substring(0, colon))
                password = decode(info.substring(colon + 1))
            }
        }

        val params = mutableListOf<Pair<String, String>>()
        uri.rawQuery?.split('&')?.filter { it.isNotEmpty() }?.forEach { pair ->
            val eq = pair.indexOf('=')
            val key = decode(if (eq < 0) pair else pair.substring(0, eq))
            val value = if (eq < 0) "" else decode(pair.substring(eq + 1))
            when {
                key in PRISMA_ONLY -> Unit
                // PgBouncer in transaction mode cannot keep server-side prepared statements.
                key == "pgbouncer" -> if (value == "true") params += "prepareThreshold" to "0"
                else -> params += (RENAMED[key] ?: key) to value
            }
        }

        val query = if (params.isEmpty()) "" else params.joinToString("&", prefix = "?") { (k, v) ->
            "${encode(k)}=${encode(v)}"
        }
        return Jdbc("jdbc:postgresql://$host:$port/${encode(database)}$query", username, password)
    }

    /** Percent-decoding without URLDecoder's form rule that turns '+' into a space. */
    private fun decode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8)

    private fun encode(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
}
