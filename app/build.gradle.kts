plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.openclaw.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openclaw.android"
        minSdk = 24     // Android 7+, same as Termux official
        targetSdk = 28  // Intentionally below 29 to bypass W^X exec restriction for sideload
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "ROOTFS_URL", "\"https://github.com/tangfuhao/Android-Openclaw-Launcher/releases/download/rootfs-v0.1.0/rootfs-aarch64.tar.xz\"")
        buildConfigField("String", "GATEWAY_HOST", "\"127.0.0.1\"")
        buildConfigField("int", "GATEWAY_PORT", "18789")
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
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.runtime)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Network
    implementation(libs.okhttp)

    // Kotlin
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Termux terminal components
    implementation(libs.termux.terminal.view)
    implementation(libs.termux.terminal.emulator)
}
