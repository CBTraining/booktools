plugins {
    id("com.android.application") version "8.9.1"
}

android {
    namespace = "com.cbtraining.booktools"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cbtraining.booktools"
        minSdk = 29
        targetSdk = 36
        versionCode = 1023
        versionName = "1.023"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
}
