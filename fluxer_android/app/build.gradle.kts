plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.fluxer.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.fluxer.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing — uses debug keystore by default (CI/dev builds).
    // Override with KEYSTORE_* env vars for production signing.
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            } else {
                storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("release")
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // ABI splits for smaller APKs
    splits {
        abi {
            isEnable = true
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/version-control-info.textproto",
                "META-INF/com/android/build/gradle/app-metadata.properties",
            )
        }
    }

    // Web assets go in assets/www/ — loaded by WebView at runtime
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.webkit)
}
