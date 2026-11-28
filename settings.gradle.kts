pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "YouveGotMail"

// The constellation's sharing mechanism (Personal-Tracker DECISIONS.md D-A): each shared repo is a
// git submodule pinned by this consumer, composited with includeBuild so `com.asystemofcells:*` coordinates
// resolve to source with no Maven registry involved.
//
// Hyle is substituted for `com.asystemofcells:hyle` ONLY. Its tree still carries the old
// `dev.aarso:crash-recovery` tombstone (a different group since the 2026-11 move, so it can no
// longer collide with shared-libraries' `com.asystemofcells:crash-recovery`); keeping Hyle's
// contribution explicit leaves exactly one provider per coordinate regardless.
includeBuild("hyle-design-system") {
    dependencySubstitution {
        substitute(module("com.asystemofcells:hyle")).using(project(":hyle"))
    }
}
includeBuild("shared-libraries")

// :mail-core — pure JVM. Protocols (IMAP, SMTP, JMAP, POP3), MIME, discovery, the store, sync,
//              search, selection, rules and on-device heuristics. Testable without an emulator.
// :mail-ai   — pure JVM. The intelligence router: on-device first, BYOK cloud models second.
// :ondevice-runtime — Android library: the MediaPipe-backed local model engine. Nothing else in
//              the project may reference MediaPipe.
// :app       — the Android app, "You've got Mail".
include(":mail-core")
include(":mail-ai")
include(":ondevice-runtime")
include(":app")

// Play Store builds ship the MediaPipe runtime as an on-demand feature module; every other build
// (CI's debug APK, sideloads, emulators) carries it inside the base app so it just works.
//   ./gradlew -PsplitOnDevice=true :app:bundleRelease
if (providers.gradleProperty("splitOnDevice").orNull == "true") include(":ondevice")
