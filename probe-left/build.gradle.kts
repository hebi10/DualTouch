plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hebi.dualtouch.probeleft"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hebi.dualtouch.probeleft"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-poc"
        testInstrumentationRunner = "com.hebi.dualtouch.probeleft.OverlayCaptureRunner"
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
    implementation(project(":probe-common"))
}

