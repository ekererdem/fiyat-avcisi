plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.erdem.fiyatavcisi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.erdem.fiyatavcisi"
        minSdk = 26
        targetSdk = 34
        versionCode = 28
        versionName = "0.28-test"
    }

    // Sabit imza: yeni sürümler eskisinin üstüne kaldırmadan kurulsun diye
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
