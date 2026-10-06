// PLUG-IN POINTS for every outside system: subscriptions/payments, analytics,
// in-app campaigns, push handling and video sources.
//
// This module holds the INTERFACES (the contract the rest of the app talks to)
// and the MOCK / interim implementations that work today. A real SDK (TIL
// Subscription SDK, GrowthRx, Google Analytics, Slike…) is added as one new
// class implementing the interface, then selected in ONE file:
//     app/src/main/kotlin/timeshealth/app/wiring/IntegrationsModule.kt
// No screen, ViewModel or repository changes. See README.md in this module.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "timeshealth.app.core.integrations"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    // The mock subscription adapter delegates to our own server checkout.
    api(project(":core:data"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
