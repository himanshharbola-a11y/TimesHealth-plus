import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Compose compiler: versioned with Kotlin itself (2.1.20).
    alias(libs.plugins.kotlin.compose)
    // Type-safe navigation routes are @Serializable classes.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * The API this build talks to, fixed at build time:
 *   ./gradlew assembleDebug -PapiBaseUrl=https://timeshealth-api.onrender.com/v1
 * Defaults to the emulator's view of a local dev server.
 */
val apiBaseUrl: String = (findProperty("apiBaseUrl") as String?) ?: "http://10.0.2.2:4000/v1"

/**
 * QA persona sign-in on the login screen. Always on in debug builds; a test APK
 * built as release turns it on with -PdevSignIn=true (the RN app's
 * EXPO_PUBLIC_DEV_SIGNIN=1). The server must also run with ALLOW_DEV_TOKENS=true
 * to accept the persona tokens, and refuses to in production.
 */
val devSignIn: Boolean = (findProperty("devSignIn") as String?).toBoolean()

/**
 * Firebase (sign-in today; FCM push later) is switched on only when the
 * project's google-services.json is available — it lives in the repo's
 * gitignored .secrets/ folder and is copied in for the build. Without it the
 * app still builds and runs with QA persona sign-in only, exactly like the
 * React Native app's dynamic config.
 */
val secretsGoogleServices = rootProject.file("../../.secrets/google-services.json")
val firebaseEnabled = secretsGoogleServices.exists()
if (firebaseEnabled) {
    secretsGoogleServices.copyTo(file("google-services.json"), overwrite = true)
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

android {
    namespace = "timeshealth.app"
    compileSdk = 36

    defaultConfig {
        // Same id as the React Native app it replaces: one Play listing, one
        // Firebase registration, and installs upgrade in place.
        applicationId = "timeshealth.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("boolean", "FIREBASE_ENABLED", "$firebaseEnabled")
        buildConfigField("boolean", "DEV_SIGNIN", "$devSignIn")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        // Robolectric (Compose UI tests on the JVM) needs the merged resources.
        unitTests.isIncludeAndroidResources = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

// The JVM UI tests host composables in the empty test activity that only debug
// builds carry (ui-test-manifest is debugImplementation), so they run against
// debug. Release code is the same code; a release unit-test run would only
// fail for want of that activity.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enableUnitTest = false }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:runtracker"))
    // Plug-in points for outside systems; chosen in wiring/IntegrationsModule.kt.
    implementation(project(":core:integrations"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.serialization.json)

    // UI: Jetpack Compose. Classic Views come in through AndroidView where a View
    // is the better tool (Media3 PlayerView, WebView).
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    // The design's own icon set (BottomNavBar, TopHeader use Filled/Outlined
    // Material icons that only the extended artifact carries).
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Hosts composables in Robolectric tests (an empty activity in the manifest).
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
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
                    "app tests ($name): ${result.resultType} - ${result.testCount} tests, " +
                        "${result.successfulTestCount} passed, ${result.failedTestCount} failed, " +
                        "${result.skippedTestCount} skipped",
                )
            }
        }
    })
}
