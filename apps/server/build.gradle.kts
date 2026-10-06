import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.tasks.run.BootRun

// TimesHealth+ API: the Spring Boot + Kotlin replacement for apps/api (Node/Fastify).
// Same HTTP contract, same Postgres schema. See README.md.

plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    // The same Kotlin as apps/android (gradle/libs.versions.toml), because this build compiles
    // that project's core/model and core/domain sources too.
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.spring") version "2.1.20"
    kotlin("plugin.jpa") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
}

// The VS Code Java extension builds this project in the background into build/. A command-line
// build running at the same moment collides with it ("Failed to create MD5 hash ..."), so CLI
// builds can use their own folder: ./gradlew test -PbuildDirName=build-cli
(findProperty("buildDirName") as String?)?.let { layout.buildDirectory.set(layout.projectDirectory.dir(it)) }

group = "timeshealth"
version = "1.0.0"

// Spring Boot 3.5's BOM pins Kotlin 1.9 and kotlinx.serialization 1.6. The shared model needs
// the versions the Android build uses (@KeepGeneratedSerializer is 1.7+).
extra["kotlin.version"] = "2.1.20"
extra["kotlin-serialization.version"] = "1.8.1"

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// ── The shared contract ──────────────────────────────────────────────────────
// The Android app's API models and business rules are compiled into the server, so a field
// renamed in one can never silently drift from the other. Both source sets are pure Kotlin
// (kotlinx.serialization + the JDK only). They are compiled, never copied: edit them in
// apps/android, not here.
val sharedModel = layout.projectDirectory.dir("../android/core/model/src/main/kotlin")
val sharedDomain = layout.projectDirectory.dir("../android/core/domain/src/main/kotlin")

sourceSets {
    main {
        kotlin.srcDir(sharedModel)
        kotlin.srcDir(sharedDomain)
    }
}

allOpen {
    // Hibernate proxies need open entity classes.
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
    // Jackson stays on the classpath for Spring internals only. Every API body goes through
    // kotlinx.serialization (see json/ServerJson.kt).
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    implementation("com.bucket4j:bucket4j_jdk17-core:8.21.0")
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Firebase Auth (token verification, account deletion) and, later, FCM push.
    // Firestore and Cloud Storage are not used, and they pull in most of gRPC.
    implementation("com.google.firebase:firebase-admin:9.11.0") {
        exclude(group = "com.google.cloud", module = "google-cloud-firestore")
        exclude(group = "com.google.cloud", module = "google-cloud-storage")
    }
    // FirebaseOptions (firebase-admin 9.11) still defaults to JacksonFactory, but the resolved
    // google-http-client is the 2.x line, which no longer pulls the jackson2 module in.
    // Without it the server fails at boot with NoClassDefFoundError as soon as Firebase starts.
    implementation("com.google.http-client:google-http-client-jackson2:1.45.3")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// All server time is UTC, whatever the host's zone. Prisma stores TIMESTAMP(3) columns as UTC
// wall-clock time; the JDBC driver and Hibernate must read and write them the same way.
val utcJvmArgs = listOf("-Duser.timezone=UTC", "-Dfile.encoding=UTF-8")

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs(utcJvmArgs)
    // Tests always run as NODE_ENV=test, whatever the developer's shell exports.
    environment("NODE_ENV", "test")
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                println(
                    "server tests: ${result.resultType} - ${result.testCount} tests, " +
                        "${result.successfulTestCount} passed, ${result.failedTestCount} failed, " +
                        "${result.skippedTestCount} skipped",
                )
            }
        }
    })
}

tasks.named<BootRun>("bootRun") {
    jvmArgs(utcJvmArgs)
}

// Demo data: until the Kotlin port of seed.ts/personas.ts lands, load it with the Node scripts,
// which write the same tables:  npm run seed && npm run personas   (repo root).
