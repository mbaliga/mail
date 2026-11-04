// Top-level build file. Plugins are declared here with `apply false` and applied per-module, so
// versions live in one place (gradle/libs.versions.toml) — the constellation's convention.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
