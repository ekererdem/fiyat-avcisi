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
        versionCode = 121
        versionName = "1.21"
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

dependencies {
    // Google'ın kamera barkod tarayıcısı (kamera izni gerektirmez, Play Hizmetleri üzerinden açılır)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Eşleştirme testleri (app/src/test)
    testImplementation("junit:junit:4.13.2")
}
