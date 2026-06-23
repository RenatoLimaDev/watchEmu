plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.watchemu.app"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.watchemu.app"
        // Classic Views UI (no Compose/AndroidX) so the app installs and runs on
        // old standalone Android watches (Android 4.4+).
        minSdk = 19
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    // Pure Android framework UI — no AndroidX/Compose, to keep minSdk low and
    // the runtime light on weak watch hardware.
}
