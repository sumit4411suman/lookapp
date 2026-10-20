plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "in.goxl.mydooraccess"
    compileSdk = 35
    defaultConfig {
        applicationId = "in.goxl.mydooraccess"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-diagnostic"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
