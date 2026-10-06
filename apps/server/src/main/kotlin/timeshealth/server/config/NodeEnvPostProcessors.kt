package timeshealth.server.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Picks the Spring profile from NODE_ENV when none is set explicitly, so the one .env that drives
 * the Node server drives this one too:
 *
 *   development (or unset) → dev,   test → test,   anything else → prod
 *
 * "Anything else" is production, the same fail-closed rule as env.ts. It runs before config
 * files are read, so application-{profile}.yml applies. An explicit SPRING_PROFILES_ACTIVE (or
 * `@ActiveProfiles` in tests) always wins.
 */
class NodeEnvProfilePostProcessor : EnvironmentPostProcessor, Ordered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 5

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val explicit = environment.activeProfiles.isNotEmpty() ||
            !environment.getProperty("spring.profiles.active").isNullOrBlank()
        if (explicit) return
        environment.addActiveProfile(profileFor(environment.getProperty("NODE_ENV")))
    }

    companion object {
        fun profileFor(nodeEnv: String?): String = when (nodeEnv ?: "development") {
            "development" -> "dev"
            "test" -> "test"
            else -> "prod"
        }
    }
}

/**
 * Derives `spring.datasource.url/username/password` from the Prisma-style DATABASE_URL (see
 * [DatabaseUrl]). An explicit `spring.datasource.url` is left alone, and Testcontainers'
 * `@ServiceConnection` overrides both.
 *
 * Empty or unset DATABASE_URL means the local docker-compose database, like env.ts (`||`).
 */
class DatabaseUrlPostProcessor : EnvironmentPostProcessor, Ordered {
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (!environment.getProperty("spring.datasource.url").isNullOrBlank()) return
        val raw = environment.getProperty("DATABASE_URL").orEmpty().ifEmpty { DatabaseUrl.LOCAL_DEFAULT }
        val jdbc = DatabaseUrl.toJdbc(raw)
        val props = buildMap<String, Any> {
            put("spring.datasource.url", jdbc.url)
            jdbc.username?.let { put("spring.datasource.username", it) }
            jdbc.password?.let { put("spring.datasource.password", it) }
        }
        environment.propertySources.addLast(MapPropertySource("DATABASE_URL", props))
    }
}
