pluginManagement {
    repositories {
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "morphe-cli"

// Include morphe-patcher and morphe-library as composite builds if they exist locally
val morphePatcherDir = file("../morphe-patcher")
if (morphePatcherDir.exists()) {
    includeBuild(morphePatcherDir) {
        dependencySubstitution {
            substitute(module("app.morphe:morphe-patcher")).using(project(":"))
        }
    }
}

val morpheLibraryDir = file("../morphe-library")
if (morpheLibraryDir.exists()) {
    includeBuild(morpheLibraryDir) {
        dependencySubstitution {
            substitute(module("app.morphe:morphe-library")).using(project(":"))
        }
    }
}
