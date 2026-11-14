import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.noexcusehere.mashwave"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.noexcusehere.mashwave"
        minSdk = 26
        targetSdk = 35
        // Minutes since 2026-01-01, so a newer build always installs over an older one
        // no matter where it was built (this PC, GitHub, ...).
        versionCode = (project.findProperty("appVersionCode") as String?)?.toInt()
            ?: ((System.currentTimeMillis() - 1_767_225_600_000L) / 60_000L).toInt()
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.0"
    }

    // The signing key is NOT in the repo. It is picked up from keystore/mashwave-signing-key.jks
    // when that file exists, with its password from the MASHWAVE_KEYSTORE_PASSWORD environment
    // variable (GitHub) or from local.properties (a PC). Without it the build still works, signed
    // with Android's throwaway debug key - fine for a first install, but such a build can't be
    // installed over one signed with the real key.
    val keystoreFile = rootProject.file("keystore/mashwave-signing-key.jks")
    val keystorePassword: String? = System.getenv("MASHWAVE_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
            Properties().apply { file.inputStream().use { load(it) } }.getProperty("mashwave.keystore.password")
        }
    val hasSigningKey = keystoreFile.exists() && !keystorePassword.isNullOrBlank()

    signingConfigs {
        if (hasSigningKey) {
            create("sideload") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = "mashwave"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("sideload") ?: signingConfigs.getByName("debug")
        }
        debug {
            signingConfigs.findByName("sideload")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Media3's "unstable" player APIs are used on purpose throughout the app.
        disable += "UnsafeOptInUsageError"
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
}

dependencies {
    val media3 = "1.9.4"

    implementation(platform("androidx.compose:compose-bom:2026.01.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.7")
    implementation("androidx.palette:palette-ktx:1.0.0")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Video editor: live preview and export of an edited timeline.
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    // Software audio decoders (AC3, E-AC3, DTS, TrueHD, ALAC, ...) for files the
    // phone's own decoders can't handle.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")

    // Casting to Chromecast (through Google Play services on the phone).
    implementation("com.google.android.gms:play-services-cast-framework:22.3.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
