plugins {
    id("com.android.application")
}

android {
    namespace = "com.aistudio.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aistudio.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

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
            // Android 14+ / 16 require a v2+ signature; enable v1+v2+v3 for
            // maximum compatibility with OEM installers that verify strictly.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            // Sign debug builds with the same dedicated key so that a manually
            // installed APK always matches and passes strict signature checks.
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
