import java.util.Properties

// :app — "You've got Mail".
//
// Everything that does not need a device lives in :mail-core and :mail-ai; this module is the UI
// (Compose, in the constellation's navigation shell and Hyle's material language) plus the pieces
// that only exist on Android: the Keystore, WorkManager, notifications, OAuth consent screens, and
// the on-device models (Gemini Nano through AICore, a local Gemma file through MediaPipe).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// OAuth client ids are per-developer registrations, so they come from local.properties (or the
// environment in CI) — never from the repository. Without them, Gmail and Microsoft accounts still
// work with app passwords where the provider allows it; see README → "Sign-in setup".
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secretProp(name: String): String = localProps.getProperty(name) ?: System.getenv(name) ?: ""

android {
    namespace = "dev.aarso.mail.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.aarso.mail"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // AppAuth's redirect receiver (Microsoft sign-in) listens on this scheme.
        manifestPlaceholders["appAuthRedirectScheme"] = "dev.aarso.mail"
        buildConfigField("String", "MICROSOFT_CLIENT_ID", "\"${secretProp("MICROSOFT_CLIENT_ID")}\"")
        // Native libraries (bundled SQLite, MediaPipe) only for the ABIs phones and emulators run.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloadable release builds out of CI; a store build replaces this with a real key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/*.kotlin_module", "META-INF/versions/9/previous-compilation-data.bin", "META-INF/io.netty.versions.properties",
                // GraalVM native-image configs shipped inside the Anthropic SDK: meaningless on Android.
                "META-INF/native-image/**",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.systemProperty("roborazzi.test.record", "true")
            test.systemProperty("robolectric.graphicsMode", "NATIVE")
            test.maxHeapSize = "3g"
            // Robolectric fetches its Android runtime from Maven Central by itself; a mirror can be
            // supplied for networks where Central is unreachable or rate-limited.
            System.getenv("ROBOLECTRIC_REPO")?.let { test.systemProperty("robolectric.dependency.repo.url", it) }
        }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":mail-ai"))

    // The constellation.
    implementation("dev.aarso:hyle:0.2.0")
    implementation("dev.aarso:cell-shell:0.1.0")
    implementation("dev.aarso:crash-recovery:1.4.0")
    implementation("dev.aarso:feedback:0.1.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.coroutines.guava)

    // Sign-in: Google's on-device Authorization API for Gmail; AppAuth (PKCE) for Microsoft.
    implementation(libs.play.services.auth)
    implementation(libs.appauth)

    // On-device intelligence.
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.mlkit.genai.summarization)
    implementation(libs.mlkit.genai.proofreading)
    implementation(libs.mlkit.genai.rewriting)
    implementation(libs.mlkit.language.id)
    implementation(libs.mlkit.translate)
    implementation(libs.mediapipe.tasks.genai)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    // On the JVM the store needs SQLite built for the host, not the Android .so files.
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Unit tests run on the host JVM: swap the Android build of bundled SQLite for the host build.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
}
