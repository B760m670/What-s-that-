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
        // targetSdk 28 on purpose: API 29+ enforces W^X (no executing files from
        // app-writable storage), which would block proot from running the guest
        // Ubuntu binaries. Termux/UserLAnd cap here for the same reason.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        // Commit the app was built from, for the in-app self-updater. CI passes
        // -PgitSha=<commit>; local builds fall back to "dev".
        val gitSha = (project.findProperty("gitSha") as String?)?.takeIf { it.isNotBlank() } ?: "dev"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")

        // The Ubuntu rootfs is downloaded on first launch (keeps the APK light),
        // so the APK itself only carries the launcher + bootstrap scripts.
        ndk {
            // We ship/run a prebuilt proot per-ABI; restrict to the common phone ABIs.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // A committed, fixed debug keystore so every build (EAS, CI, local) signs
    // with the SAME certificate. Without this, each environment generates its
    // own debug key → different signature → Android refuses in-place updates and
    // the user must uninstall (wiping the multi-GB rootfs). These are the
    // well-known public debug credentials; nothing secret.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
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
    // Pure-Java XZ decompression for the Ubuntu .tar.xz rootfs (no native/curl
    // dependency on the device). Tar is parsed by our own extractor.
    implementation("org.tukaani:xz:1.9")
}
