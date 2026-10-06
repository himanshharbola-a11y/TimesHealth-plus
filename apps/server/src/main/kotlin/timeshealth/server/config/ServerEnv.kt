package timeshealth.server.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Port of apps/api/src/env.ts. Every value is bound in application.yml from the SAME environment
 * variable name the Node server reads, so one .env file configures both servers.
 *
 * The raw values arrive as strings and are interpreted here with the Node semantics:
 *  - `process.env.X ?? default`: unset gives the default, an empty value stays empty. That is what
 *    a Spring placeholder `${X:default}` does, so those are bound as-is.
 *  - `required(X, fallback)` uses `||`: an EMPTY value also gives the fallback (a blank line copied
 *    from .env.example must not become an empty HMAC key). See [orFallback].
 *  - `Number(x)`: "" is 0, junk is NaN. See [jsNumber].
 *
 * Construction validates (the env.ts guards at the bottom of that file), so a bad production
 * config stops the boot instead of serving traffic.
 */
@ConfigurationProperties(prefix = "th.env")
class ServerEnv(
    val nodeEnv: String = "development",
    val firebaseServiceAccountFile: String = "",
    val firebaseServiceAccountB64: String = "",
    /** Read by env.ts but used nowhere in the Node server; kept for parity. */
    val firebaseProjectId: String = "",
    allowDevTokens: String = "",
    publicTunnel: String = "",
    /** Identity seam switch: 'firebase' (default) or 'times-sso' (see identity/). */
    val authProvider: String = "firebase",
    trustProxyHops: String = "1",
    bibSigningSecret: String = "",
    bibTokenTtlSeconds: String = "60",
    mediaSigningSecret: String = "",
    waJoinSigningSecret: String = "",
    /** Base of shared links (Refer & Win). The web funnel domain — docs/01 F6. */
    val shareBaseUrl: String = "https://timeshealthplus.invalid",
    scannerKeys: String = "",
    /** Where a non-subscriber who opens a WhatsApp class link is sent instead. */
    val yogaRenewUrl: String = "https://timeshealthplus.invalid/yoga",
    mediaUrlTtlSeconds: String = "300",
    val mediaBaseUrl: String = "https://media.timeshealthplus.invalid",
    dietLeadWebhookUrl: String = "",
    razorpayKeyId: String = "",
    razorpayKeySecret: String = "",
    corsOrigins: String = "*",
    /** routes/yoga.ts reads YOGA_CLASS_LINK directly; it lives here so it is bound once. */
    val yogaClassLink: String = "https://timeshealthplus.invalid/live",
) {
    /**
     * FAIL CLOSED: anything that is not explicitly development or test is treated as production.
     * A deploy with NODE_ENV "staging", "prod" or "" must not accept persona tokens, simulated
     * payments or leak raw errors. (Unset is "development", as in env.ts.)
     */
    val isProd: Boolean = nodeEnv != "development" && nodeEnv != "test"

    /**
     * Accept QA persona tokens ("uid|email|phone") alongside real Firebase tokens. Anyone can mint
     * these, so they are refused outright in production (see [validate]).
     */
    val allowDevTokens: Boolean = allowDevTokens == "true"

    /** Set by scripts/serve.mjs: reachable from the internet through a tunnel. */
    val publicTunnel: Boolean = publicTunnel == "true"

    /** Proxies in front of the API (ngrok / one load balancer = 1). NaN trusts none. */
    val trustProxyHops: Double = jsNumber(trustProxyHops)

    /** Signs digital-bib QR tokens. */
    val bibSigningSecret: String = bibSigningSecret.orFallback("dev-only-bib-secret-change-me")
    val bibTokenTtlSeconds: Long = jsNumber(bibTokenTtlSeconds).toWholeOrThrow("BIB_TOKEN_TTL_SECONDS")

    /** Signs media playback URLs for entitlement-gated video. */
    val mediaSigningSecret: String = mediaSigningSecret.orFallback("dev-only-media-secret-change-me")

    /** Signs the per-user WhatsApp class links (GET /yoga/wa-join). */
    val waJoinSigningSecret: String = waJoinSigningSecret.orFallback("dev-only-wa-join-secret-change-me")

    /**
     * Expo bib scanners: "counter1:key1,counter2:key2". Keys shorter than 24 characters are
     * dropped, exactly as env.ts does.
     */
    val scannerKeys: List<ScannerKey> = scannerKeys.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { pair ->
            val parts = pair.split(':')
            ScannerKey(id = parts[0], key = parts.drop(1).joinToString(":"))
        }
        .filter { it.id.isNotEmpty() && it.key.length >= 24 }

    val mediaUrlTtlSeconds: Long = jsNumber(mediaUrlTtlSeconds).toWholeOrThrow("MEDIA_URL_TTL_SECONDS")

    /** Where diet leads are forwarded (PRD §9). Null when unset or empty. */
    val dietLeadWebhookUrl: String? = dietLeadWebhookUrl.ifEmpty { null }
    val razorpayKeyId: String? = razorpayKeyId.ifEmpty { null }
    val razorpayKeySecret: String? = razorpayKeySecret.ifEmpty { null }

    /** `CORS_ORIGINS.split(',')`, untrimmed — exactly what app.ts hands @fastify/cors. */
    val corsOrigins: List<String> = corsOrigins.split(',')

    /** True when a Firebase service account is configured (file path or base64 JSON). */
    val firebaseConfigured: Boolean =
        firebaseServiceAccountFile.isNotEmpty() || firebaseServiceAccountB64.isNotEmpty()

    init {
        validate()
    }

    /** The guards at the bottom of env.ts, in the same order and with the same messages. */
    private fun validate() {
        if (authProvider != "firebase" && authProvider != "times-sso") {
            throw IllegalStateException(
                "Unknown AUTH_PROVIDER \"$authProvider\" — expected firebase or times-sso.",
            )
        }
        if (authProvider == "times-sso") {
            // Fail at boot in EVERY environment until the provider exists, so nobody discovers a
            // dead login screen in front of users.
            throw IllegalStateException(
                "AUTH_PROVIDER=times-sso is not implemented yet — see src/identity/timesSso.ts " +
                    "for exactly what the Times SSO team must supply, and docs/06 for the swap plan.",
            )
        }
        if (isProd && !firebaseConfigured) {
            throw IllegalStateException(
                "A Firebase service account is required in production " +
                    "(FIREBASE_SERVICE_ACCOUNT_FILE or FIREBASE_SERVICE_ACCOUNT_B64). " +
                    "Dev auth mode must never run with NODE_ENV=production.",
            )
        }
        // The signing secrets have dev fallbacks so local setup is one command. In production a
        // fallback would let anyone mint bib passes, playback URLs and attendance links.
        if (isProd) {
            val secrets = linkedMapOf(
                "BIB_SIGNING_SECRET" to bibSigningSecret,
                "MEDIA_SIGNING_SECRET" to mediaSigningSecret,
                "WA_JOIN_SIGNING_SECRET" to waJoinSigningSecret,
            )
            val weak = secrets.filterValues { it.startsWith("dev-only-") || it.length < 32 }.keys
            if (weak.isNotEmpty()) {
                throw IllegalStateException(
                    "Production requires real signing secrets (32+ chars): ${weak.joinToString(", ")}",
                )
            }
        }
        if (isProd && allowDevTokens) {
            throw IllegalStateException(
                "ALLOW_DEV_TOKENS=true is refused in production: persona tokens can be " +
                    "forged by anyone and would let them sign in as any user.",
            )
        }
    }

    data class ScannerKey(val id: String, val key: String)

    companion object {
        /** `||` semantics: an empty value means "use the fallback". */
        private fun String.orFallback(fallback: String): String = ifEmpty { fallback }

        /**
         * JavaScript `Number(s)` for the values env.ts converts: surrounding whitespace ignored,
         * "" is 0, anything that is not a decimal number is NaN.
         */
        fun jsNumber(raw: String): Double {
            val s = raw.trim()
            if (s.isEmpty()) return 0.0
            if (s == "Infinity" || s == "+Infinity") return Double.POSITIVE_INFINITY
            if (s == "-Infinity") return Double.NEGATIVE_INFINITY
            if (!Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?").matches(s)) {
                return if (Regex("0[xX][0-9a-fA-F]+").matches(s)) s.substring(2).toLong(16).toDouble() else Double.NaN
            }
            return s.toDouble()
        }

        /**
         * A TTL the Node server would turn into NaN/Infinity produces broken links there; here it
         * stops the boot instead. Fractions are truncated (`Math.trunc`-like) as no TTL uses one.
         */
        private fun Double.toWholeOrThrow(name: String): Long {
            check(!isNaN() && !isInfinite()) { "$name must be a number" }
            return toLong()
        }
    }
}
