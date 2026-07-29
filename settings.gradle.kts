rootProject.name = "Nostrord"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // livekit-kmp (desktop AV for NIP-29 spaces) is not on Maven Central. Build it from
        // ../livekit-kmp with `./gradlew publishToMavenLocal` before building the desktop
        // target. Scoped to that group so nothing else can silently resolve from ~/.m2.
        mavenLocal {
            mavenContent { includeGroup("io.github.nostrord") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":composeApp")
