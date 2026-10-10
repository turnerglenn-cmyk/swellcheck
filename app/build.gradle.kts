plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.swellcheck"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.swellcheck"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // F-Droid: don't embed Google's encrypted dependency metadata blob.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// No third-party dependencies on purpose: only the Android framework and the
// Kotlin standard library. Nothing to track you, nothing proprietary.
