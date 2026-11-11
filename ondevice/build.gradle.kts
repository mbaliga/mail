// On-demand feature module "ondevice": installed by Play when the person asks for a local model.
// Only part of the build with -PsplitOnDevice=true (see settings.gradle.kts).
plugins {
    alias(libs.plugins.android.dynamic.feature)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.aarso.mail.ondevice.feature"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Phones only, like the base app's release build.
    packaging { jniLibs { excludes += "**/x86_64/**" } }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":app"))
    implementation(project(":ondevice-runtime"))
}
