// :mail-ai — the intelligence router. On-device first (Gemini Nano, a local Gemma file, and the
// deterministic heuristics in :mail-core), then the user's own cloud keys, never anything else.
//
// Pure JVM like :mail-core. The on-device engines are Android-only and live in :app behind
// [dev.aarso.mail.ai.LlmEngine]; everything that decides *which* engine runs, what it may see,
// and how its answer is checked lives here where it can be tested.
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
    api(project(":mail-core"))
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
