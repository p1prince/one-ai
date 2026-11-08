plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.oneai.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.oneai.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        // Emulator/device tests (Stage 11 scaffolding); never run by CI, which only compiles them.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // D9 / D8 slice 2: capability code lives only in the `capabilities` flavor (src/capabilities, src/testCapabilities).
    // The `core` flavor is the Core app and ships none of it.
    flavorDimensions += "edition"
    productFlavors {
        create("core") { dimension = "edition" }
        create("capabilities") {
            dimension = "edition"
            applicationIdSuffix = ".capabilities"
            versionNameSuffix = "-capabilities"
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation(project(":one-ai-plugin-sdk"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (the Android stub throws "not mocked").
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
}
