plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Fireworks API key for development builds: read from the (git-ignored) key file at the repo root.
val fwKey: String = rootProject.file("../.fireworks_key").takeIf { it.exists() }?.readText()?.trim() ?: ""

android {
    namespace = "com.prism.tva"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.prism.tva"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.3"
        buildConfigField("String", "FW_KEY", "\"$fwKey\"")
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
