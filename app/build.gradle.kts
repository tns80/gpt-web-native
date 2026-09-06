plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.local.gptwebnative"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.local.gptwebnative"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            // Personal sideload build: release optimizations, debug keystore signing.
            // Replace with your own persistent signing key before distributing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
