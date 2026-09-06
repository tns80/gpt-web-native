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
        versionCode = 1000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "1.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("personalRelease") {
            storeFile = file(System.getenv("RELEASE_STORE_FILE") ?: "missing-release-keystore")
            storePassword = System.getenv("RELEASE_STORE_PASSWORD")
            keyAlias = "gptwebnative"
            keyPassword = System.getenv("RELEASE_STORE_PASSWORD")
            storeType = "PKCS12"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            // Fail when the persistent key is missing; never silently generate another.
            signingConfig = signingConfigs.getByName("personalRelease")
        }
    }
}
