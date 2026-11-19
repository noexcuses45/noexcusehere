import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    kotlin("plugin.compose") version "2.3.21"
    id("org.jetbrains.compose") version "1.12.2"
}

group = "com.noexcusehere.mashwave"
version = (findProperty("appVersionName") as String?) ?: "1.0.0"

// The TV side of the phone app (signing in, lists, countries, search) is plain Kotlin with no
// Android in it. The computer app compiles those very files, so both behave the same.
val phoneSources = file("../mashwave/app/src/main/java/com/noexcusehere/mashwave")
val sharedSources = layout.buildDirectory.dir("shared")
val copyShared by tasks.registering(Sync::class) {
    from(phoneSources) {
        include(
            "iptv/Xtream.kt",
            "iptv/M3u.kt",
            "iptv/TvClient.kt",
            "iptv/TvModels.kt",
            "iptv/TvNet.kt",
            "iptv/TvRegions.kt",
            "iptv/TvSearch.kt",
            "iptv/LiveCushion.kt",
            "iptv/TvStore.kt",
            "iptv/TvRepository.kt",
            "data/Resume.kt",
        )
    }
    into(sharedSources.map { it.dir("com/noexcusehere/mashwave") })
}

// Every icon the screens mention ("Icons.Rounded.Search") is one small file in the big icon set
// (a few everyday ones come with the basic set instead). DesktopTest checks none went missing.
val allIcons: Configuration by configurations.creating
val usedIconsJar by tasks.registering(Jar::class) {
    val sources = fileTree("src/main/kotlin")
    inputs.files(sources)
    archiveFileName.set("icons-used.jar")
    destinationDirectory.set(layout.buildDirectory.dir("icons"))
    val mention = Regex("""Icons\.(AutoMirrored\.)?Rounded\.(\w+)""")
    val wanted = provider {
        sources.files.flatMap { file ->
            mention.findAll(file.readText()).map { found ->
                "androidx/compose/material/icons/" + (if (found.groupValues[1].isEmpty()) "" else "automirrored/") + "rounded/" + found.groupValues[2] + "Kt.class"
            }
        }.toSet()
    }
    from({ allIcons.map { zipTree(it) } }) {
        include { it.isDirectory || it.path in wanted.get() }
    }
    includeEmptyDirs = false
}

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.srcDir(copyShared.map { sharedSources.get() })
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    // The full set of icons is 38 MB; only the few the app draws are packed with it (see usedIconsJar).
    compileOnly("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    allIcons("org.jetbrains.compose.material:material-icons-extended-desktop:1.7.3") { isTransitive = false }
    runtimeOnly(files(usedIconsJar))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // Talks to the VLC installed on the computer, which does the actual playing.
    implementation("net.java.dev.jna:jna:5.19.1")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.noexcusehere.mashwave.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Ultimate Media Manager"
            // Installers only accept plain numbers here.
            packageVersion = project.version.toString()
            description = "Ultimate Media Manager - TV player"
            vendor = "Ultimate Media Manager"
            // The parts of Java the app needs beyond the basics (found with "gradlew suggestRuntimeModules").
            modules("java.instrument", "jdk.unsupported")

            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menuGroup = "Ultimate Media Manager"
                shortcut = true
                menu = true
                perUserInstall = true
                // Stays the same for every version, so a new installer replaces the old app.
                upgradeUuid = "6f0c7c4e-5d0b-4c1e-9a55-2b7d7f6b9d11"
            }
            linux {
                iconFile.set(project.file("icons/icon.png"))
            }
        }
    }
}
