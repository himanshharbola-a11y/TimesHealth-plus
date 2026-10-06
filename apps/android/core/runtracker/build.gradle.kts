// GPS run tracking (PRD §8.6): a foreground location service, a Room store
// that survives the app being killed, and a WorkManager upload queue.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "timeshealth.app.core.runtracker"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Robolectric (Room in-memory tests) needs the merged manifest and resources.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin { jvmToolchain(17) }

// The Room schema is exported and committed, so every future migration is a
// reviewable diff against the shape that is already on runners' phones.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    // Room DAO tests run against a real in-memory SQLite under Robolectric.
    // Pinned here rather than in the shared version catalog so this module
    // doesn't touch files other modules are editing.
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
}
