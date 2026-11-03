plugins {
    // AGP 9 has built-in Kotlin support, so the standalone
    // org.jetbrains.kotlin.android plugin is not applied (AGP refuses it).
    // The Compose compiler plugin is still applied separately.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.soulstone.tps"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.soulstone.tps"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "0.3.0-dev.9"
        // On-device checks of what only a real Android runtime can answer (run on an emulator).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Fixed debug keystore committed to the repo (a debug key is non-secret; its
    // password is the well-known "android") so CI and local builds sign
    // identically and an update installs over the previous build.
    testOptions {
        unitTests.all {
            // A raw recording to replay (RecordingReplayTest). Recordings hold the place they
            // were made, so they stay out of the repo and come in only from the command line.
            val recording = providers.gradleProperty("tps.recording").orNull
            if (recording != null) it.systemProperty("tps.recording", recording)
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
