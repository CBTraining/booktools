plugins {
    id("com.android.application") version "8.9.1"
}

android {
    namespace = "com.example.topbardrop"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.topbardrop"
        minSdk = 29
        targetSdk = 36
        versionCode = 1022
        versionName = "1.022"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
}
