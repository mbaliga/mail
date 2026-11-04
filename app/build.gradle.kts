plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.aarso.mail.app"
    compileSdk = 36
    defaultConfig { applicationId = "dev.aarso.mail"; minSdk = 31; targetSdk = 36; versionCode = 1; versionName = "1.0.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":mail-ai"))
    implementation("dev.aarso:hyle:0.2.0")
    implementation("dev.aarso:cell-shell:0.1.0")
    implementation("dev.aarso:crash-recovery:1.4.0")
    implementation("dev.aarso:feedback:0.1.0")
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.mlkit.genai.summarization)
    implementation(libs.mlkit.genai.proofreading)
    implementation(libs.mlkit.genai.rewriting)
    implementation(libs.mlkit.language.id)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.entity.extraction)
    implementation(libs.litertlm.android)
    implementation(libs.appauth)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.sqlite.bundled)
}
