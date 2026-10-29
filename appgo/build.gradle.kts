plugins {
    id("com.android.application")
}

// Second flavour of the launcher: wraps the Go backend Mag1cFall/AIStudio2API
// (listens on :2048) instead of the Node backend. It reuses the same PRoot +
// Ubuntu 24.04 arm64 rootfs (Node/Camoufox/Xvfb/x11vnc/websockify) already
// bundled for :app, so only the backend payload differs.
android {
    namespace = "com.aistudio2api.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aistudio2api.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "0.2.14"

        // Only ship arm64 native code.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/aistudio-release.jks")
            storePassword = "aistudio123"
            keyAlias = "aistudio"
            keyPassword = "aistudio123"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            // Same dedicated key as :app so both APKs can be installed side by
            // side without signature surprises.
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

    // The ~850MB rootfs asset is already zstd-compressed; keep aapt2 from
    // deflating it again (slow, memory hungry, and pointless).
    androidResources {
        noCompress += listOf("zst")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.github.luben:zstd-jni:1.5.6-4@aar")
}

configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.24")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.24")
    }
}
