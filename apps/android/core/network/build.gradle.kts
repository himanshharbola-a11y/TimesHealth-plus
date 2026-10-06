// HTTP client for the TimesHealth+ API. Plain JVM (Retrofit/OkHttp), so it is
// tested against MockWebServer without an emulator.
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:model"))
    api(libs.retrofit)
    api(libs.okhttp)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

sourceSets {
    test {
        // The recorded staging responses live with the contract tests in :core:model. Sharing the
        // directory instead of copying it means a re-recorded fixture is exercised by both suites
        // and the two can't drift apart.
        resources.srcDir("../model/src/test/resources")
    }
}

tasks.test {
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
    // Gradle prints no test counts by default; print one summary line for the suite.
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                println(
                    "core:network tests: ${result.resultType} - ${result.testCount} tests, " +
                        "${result.successfulTestCount} passed, ${result.failedTestCount} failed, " +
                        "${result.skippedTestCount} skipped",
                )
            }
        }
    })
}
