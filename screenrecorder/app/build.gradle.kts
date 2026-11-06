import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing. Nothing secret is stored in the repo:
//  * On GitHub Actions the workflow sets KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
//    from repository secrets (see screenrecorder/README.md).
//  * Locally you can set the same environment variables, or drop a git-ignored
//    signing/signing.properties next to this project with storeFile/storePassword/keyAlias/keyPassword.
//  * If neither is present the release APK is signed with the standard Android debug key, which still
//    installs fine for side-loading (but updates built elsewhere need an uninstall first).
val signingProps = Properties().apply {
    val f = rootProject.file("signing/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(envName: String, propName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() } ?: signingProps.getProperty(propName)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("KEYSTORE_FILE", "storeFile")?.let { path ->
    val f = File(path)
    if (f.isAbsolute) f else rootProject.file(path)
}
val hasReleaseKey = releaseStoreFile?.exists() == true

android {
    namespace = "com.noexcusehere.screenrecorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.noexcusehere.screenrecorder"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
}
