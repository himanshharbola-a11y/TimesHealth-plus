package timeshealth.server

import java.util.TimeZone
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/** TimesHealth+ API. The Spring Boot replacement for apps/api; see README.md. */
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
class Application

fun main(args: Array<String>) {
    // Belt and braces with -Duser.timezone=UTC (build.gradle.kts): every date boundary in this
    // product is computed explicitly in IST or UTC, never in the host's zone.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    runApplication<Application>(*args)
}
