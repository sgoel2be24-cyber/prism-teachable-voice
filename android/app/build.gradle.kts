plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Fireworks API keys come from git-ignored files at the repo root, never from source:
//   .fireworks_key             development builds (assembleDebug)
//   .fireworks_key_submission  the APK handed to judges (assembleRelease); falls back to the dev key
// Either can be overridden at runtime from the app's "Language model key" field.
fun keyFile(name: String): String = rootProject.file("../$name").takeIf { it.exists() }?.readText()?.trim() ?: ""
val devKey: String = keyFile(".fireworks_key")
val submissionKey: String = keyFile(".fireworks_key_submission").ifEmpty { devKey }

android {
    namespace = "com.prism.tva"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.prism.tva"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "1.0"
    }

    buildTypes {
        debug {
            buildConfigField("String", "FW_KEY", "\"$devKey\"")
        }
        release {
            isMinifyEnabled = false
            // Signed with the standard debug keystore so the APK installs directly (sideloaded, not
            // for the Play Store).
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "FW_KEY", "\"$submissionKey\"")
        }
    }

    buildFeatures {
        buildConfig = true
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
