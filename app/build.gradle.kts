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
        versionCode = 2
        versionName = "0.1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
