plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.whatsthat.linux"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.whatsthat.linux"
        minSdk = 21          // Android 5.0 Lollipop — oldest supported
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // The Ubuntu rootfs is downloaded on first launch (keeps the APK light),
        // so the APK itself only carries the launcher + bootstrap scripts.
        ndk {
            // We ship/run a prebuilt proot per-ABI; restrict to the common phone ABIs.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
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
    kotlinOptions {
        jvmTarget = "17"
    }
    // Two footprints from one codebase:
    //   full — XFCE + browser + dev tools, for capable phones
    //   lite — minimal Openbox + a terminal/file-manager, for old/weak devices
    //          (Android 5 era). Each flavor only changes which packages the
    //          in-container installer pulls; the engine is identical.
    flavorDimensions += "footprint"
    productFlavors {
        create("full") {
            dimension = "footprint"
            buildConfigField("String", "DESKTOP_PROFILE", "\"full\"")
            buildConfigField("String", "UBUNTU_VARIANT", "\"standard\"")
        }
        create("lite") {
            dimension = "footprint"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
            buildConfigField("String", "DESKTOP_PROFILE", "\"lite\"")
            buildConfigField("String", "UBUNTU_VARIANT", "\"minimal\"")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
