package timeshealth.server.identity

import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import timeshealth.server.config.ServerEnv

@Configuration(proxyBeanMethods = false)
class IdentityConfig {
    @Bean
    fun firebaseAppHolder(env: ServerEnv) = FirebaseAppHolder(env)

    @Bean
    fun firebaseAuthGateway(holder: FirebaseAppHolder): FirebaseAuthGateway = SdkFirebaseAuthGateway(holder)

    /** Chosen once at boot by AUTH_PROVIDER (default firebase), as identity/index.ts does. */
    @Bean
    fun identityProvider(env: ServerEnv, holder: FirebaseAppHolder, gateway: FirebaseAuthGateway, clock: Clock): IdentityProvider =
        if (env.authProvider == "times-sso") TimesSsoIdentityProvider() else FirebaseIdentityProvider(holder, gateway, clock)
}

/**
 * server.ts's boot sequence: initialise the identity provider BEFORE serving, so a bad or missing
 * key fails the boot instead of the first user who tries to log in; then log the auth mode.
 */
@Component
class IdentityStartup(private val provider: IdentityProvider, private val env: ServerEnv) : InitializingBean {
    private val log = LoggerFactory.getLogger(IdentityStartup::class.java)

    /**
     * 'firebase' (or the provider's name) when configured, 'dev-only' when only persona tokens
     * work. The scheduler port must start only when this is "firebase" (server.ts: push needs it).
     */
    final lateinit var authMode: String
        private set

    override fun afterPropertiesSet() {
        authMode = if (provider.configured()) provider.name else "dev-only"
    }

    @EventListener(ApplicationReadyEvent::class)
    fun announce() {
        when {
            authMode == "dev-only" -> log.warn(
                "DEV AUTH MODE — Firebase is not configured. Persona tokens (\"uid|email|phone\") " +
                    "are accepted unverified. Never expose this server to real users.",
            )
            env.allowDevTokens -> log.warn(
                "Firebase auth ON, and ALLOW_DEV_TOKENS=true — QA persona tokens are ALSO " +
                    "accepted. Turn this off before anyone outside the team uses this server.",
            )
            else -> log.info("Firebase auth ON. Persona tokens are rejected.")
        }
    }
}
