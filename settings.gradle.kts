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
// git submodule pinned by this consumer, composited with includeBuild so `dev.aarso:*` coordinates
// resolve to source with no Maven registry involved.
//
// Hyle is substituted for `dev.aarso:hyle` ONLY. Its tree still carries the crash-recovery
// tombstone at the same `dev.aarso:crash-recovery` coordinate that shared-libraries now owns
// (shared-libraries MIGRATION.md); letting both builds offer it automatically makes the
// coordinate ambiguous. Pinning Hyle's contribution explicitly leaves exactly one provider.
includeBuild("hyle-design-system") {
    dependencySubstitution {
        substitute(module("dev.aarso:hyle")).using(project(":hyle"))
    }
}
includeBuild("shared-libraries")

// :mail-core — pure JVM. Protocols (IMAP, SMTP, JMAP, POP3), MIME, discovery, the store, sync,
//              search, selection, rules and on-device heuristics. Testable without an emulator.
// :mail-ai   — pure JVM. The intelligence router: on-device first, BYOK cloud models second.
// :app       — the Android app, "You've got Mail".
include(":mail-core")
include(":mail-ai")
include(":app")
