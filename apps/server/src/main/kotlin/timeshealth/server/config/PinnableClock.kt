package timeshealth.server.config

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The server's notion of "now" (port of apps/api/src/clock.ts).
 *
 * Tests pin it so a rule like "a join only counts while a class is open" can be checked at any
 * hour the suite happens to run. Production never pins it.
 *
 * Inject `java.time.Clock` (this bean) wherever the Node code calls `now()` or `new Date()` for a
 * business rule; never call `Instant.now()` directly in route or service code. Row timestamps
 * (createdAt/updatedAt), which Prisma fills from the real clock, stay on the system clock.
 */
class PinnableClock(private val base: Clock = Clock.systemUTC()) : Clock() {
    @Volatile
    private var pinned: Instant? = null

    /** `pinClockForTests(at)`; null unpins. */
    fun pin(at: Instant?) {
        pinned = at
    }

    override fun instant(): Instant = pinned ?: base.instant()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
}

@Configuration(proxyBeanMethods = false)
class ClockConfig {
    @Bean
    fun clock(): PinnableClock = PinnableClock()
}
