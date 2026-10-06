// TimesHealth+ API server (Spring Boot + Kotlin). A standalone build: it is not part of the
// Android build in ../android, but it compiles two of that build's pure-Kotlin source sets
// (core/model and core/domain) into itself so the app and the server share one contract.
// See build.gradle.kts and README.md.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "timeshealth-server"
