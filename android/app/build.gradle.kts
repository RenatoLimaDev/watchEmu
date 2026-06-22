plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.watchemu.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.watchemu.app"
        // Amazfit Stratos runs Android 5.1.1 (API 22). It is NOT a Wear OS
        // device, so the app targets plain Android down to Lollipop.
        minSdk = 22
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")

    // Animation
    implementation("androidx.compose.animation:animation")

    // Regular Jetpack Compose Material (minSdk 21) — replaces Wear Compose so the
    // app installs and runs on plain Android 5.1 (Amazfit Stratos), which has no
    // Wear OS runtime. Provides MaterialTheme, Text, Icon, Button, Surface, etc.
    implementation("androidx.compose.material:material")

    // Material Icons Extended (Bluetooth / Delete glyphs used in the ROM picker)
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
