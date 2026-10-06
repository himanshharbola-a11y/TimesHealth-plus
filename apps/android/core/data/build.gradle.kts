// Android persistence and repositories: encrypted session storage, the
// offline race pass, checkout safety, the inbox "seen" marker.
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "timeshealth.app.core.data"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:domain"))
    api(project(":core:network"))
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    // Drives the real NetworkFactory wiring (token on the wire, 401 -> session end).
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
    // Gradle prints no test counts by default; print one summary line per unit-test task.
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                println(
                    "core:data tests ($name): ${result.resultType} - ${result.testCount} tests, " +
                        "${result.successfulTestCount} passed, ${result.failedTestCount} failed, " +
                        "${result.skippedTestCount} skipped",
                )
            }
        }
    })
}
