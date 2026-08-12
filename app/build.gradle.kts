plugins {
    id("com.android.application")
}

android {
    namespace = "com.local.runnerhelper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.local.runnerhelper"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
