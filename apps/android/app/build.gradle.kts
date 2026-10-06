plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
    }

    buildFeatures { buildConfig = true }

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

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:runtracker"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
