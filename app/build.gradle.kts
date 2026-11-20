plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hebi.dualtouch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hebi.dualtouch"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-poc"
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
    testImplementation("junit:junit:4.13.2")
}

