// :mail-core — everything about mail that does not need a device.
//
// Plain `kotlin("jvm")` on purpose, the same constraint :search-core holds itself to: the
// protocols, MIME, the store, sync, search, selection, rules and the on-device heuristics all run
// and are tested on a normal JVM, with no emulator. The Android app supplies only what genuinely
// needs Android (a SQLite driver build, the Keystore, WorkManager, AICore).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "dev.aarso.mail"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The constellation's search engine: query grammar, facets, natural language, ranking.
    api("dev.aarso:search-core:0.2.0")
    api(libs.kotlinx.coroutines.core)
    // KMP driver API (SQLiteDriver / SQLiteConnection / SQLiteStatement). The concrete driver is
    // injected: the bundled build on Android (for FTS5) and the bundled JVM build in tests.
    api(libs.androidx.sqlite)
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.sqlite.bundled)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    // The store's tests load the bundled SQLite natives; give them room.
    maxHeapSize = "1g"
}
