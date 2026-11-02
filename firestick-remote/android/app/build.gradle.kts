plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.noexcusehere.firestickremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.noexcusehere.firestickremote"
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("BUILD_NUMBER") ?: "1")
    }

    // A fixed key so new versions install over old ones without uninstalling.
    signingConfigs {
        create("release") {
            storeFile = file("remote.keystore")
            storePassword = "firestick"
            keyAlias = "remote"
            keyPassword = "firestick"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // The remote's web UI is shared with the Python server version.
    sourceSets["main"].assets.srcDirs("../../static")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("dev.mobile:dadb:1.2.10")
}
