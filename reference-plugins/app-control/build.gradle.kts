plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oneai.reference.appcontrol"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.oneai.reference.appcontrol"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // This reference capability is an independent APK and shares only the public plugin SDK with ONE AI.
    implementation(project(":one-ai-plugin-sdk"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
