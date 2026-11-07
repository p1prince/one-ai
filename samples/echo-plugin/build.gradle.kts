plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Reference ONE AI plugin (Stage 8.6). A separate, independently installable APK: it shares no code with
// ONE AI except the plugin SDK, exactly like a third-party plugin would.
android {
    namespace = "com.oneai.sample.echo"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.oneai.sample.echo"
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
    implementation(project(":one-ai-plugin-sdk"))
    testImplementation("junit:junit:4.13.2")
}
