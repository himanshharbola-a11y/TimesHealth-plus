// API contract as plain Kotlin — mirrors packages/types on the server.
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test {
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
    // Gradle prints no test counts by default; print one summary line for the contract suite.
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                println(
                    "core:model contract tests: ${result.resultType} - ${result.testCount} tests, " +
                        "${result.successfulTestCount} passed, ${result.failedTestCount} failed, " +
                        "${result.skippedTestCount} skipped",
                )
            }
        }
    })
}
