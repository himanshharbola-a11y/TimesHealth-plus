package timeshealth.server.db

import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import timeshealth.server.db.entity.AppConfigEntity
import timeshealth.server.db.repo.AppConfigRepository
import timeshealth.server.db.repo.ThRepositoryImpl

@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(basePackages = ["timeshealth.server.db.repo"], repositoryBaseClass = ThRepositoryImpl::class)
class DbConfig

/** Port of db.ts `getAppConfig()`: the single platform-config row, created on first read. */
@Service
class AppConfigService(private val repo: AppConfigRepository, private val jdbc: JdbcClient) {
    @Transactional
    fun get(): AppConfigEntity {
        repo.findById(1).orElse(null)?.let { return it }
        // Node: findUnique, else create. ON CONFLICT makes two first readers at once safe too.
        jdbc.sql(
            """INSERT INTO "AppConfig" ("id", "minSupportedAppVersion", "maintenanceActive", "updatedAt")
               VALUES (1, '1.0.0', false, date_trunc('milliseconds', now() AT TIME ZONE 'UTC'))
               ON CONFLICT ("id") DO NOTHING""",
        ).update()
        return repo.findById(1).orElseThrow()
    }
}
