plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hebi.dualtouch.proberight"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hebi.dualtouch.proberight"
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
    implementation(project(":probe-common"))
}

