plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xtsdx.virtualxbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xtsdx.virtualxbox"
        minSdk = 28 // BluetoothHidDevice
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
