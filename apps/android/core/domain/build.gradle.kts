// Business rules with no Android dependency: IST time, the class join
// window, run-tracker maths, validators, link safety. Pure Kotlin, so it is
// unit-tested on the JVM and can be mirrored line-for-line in Swift.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:model"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
