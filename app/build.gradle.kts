import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val privateSigningFile = providers.environmentVariable("DUALTOUCH_SIGNING_PROPERTIES").orNull
val privateSigning = Properties().apply {
    privateSigningFile?.let { path -> file(path).reader(Charsets.UTF_8).use { load(it) } }
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

    if (privateSigningFile != null) {
        signingConfigs.create("upload") {
            fun value(name: String) = requireNotNull(privateSigning.getProperty(name)?.takeIf { it.isNotBlank() })
            storeFile = file(privateSigningFile).parentFile.resolve(value("storeFile"))
            storePassword = value("storePassword")
            keyAlias = value("keyAlias")
            keyPassword = value("keyPassword")
            storeType = value("storeType")
        }
    }
    buildTypes.getByName("release") {
        isDebuggable = false
        if (privateSigningFile != null) signingConfig = signingConfigs.getByName("upload")
    }
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    doFirst { require(privateSigningFile != null) { "An existing private upload key is required for a release AAB." } }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

