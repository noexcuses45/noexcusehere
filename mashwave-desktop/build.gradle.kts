import org.gradle.internal.os.OperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.security.MessageDigest

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
            "licence/Licence.kt",
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

// ---- the video player packed with the Windows app --------------------------------------------
//
// The picture and sound are played by the VLC engine (libVLC). For Windows it is packed inside
// the app, so nothing else has to be installed. The files come from VideoLAN's own package for
// use inside other programs, which is under the LGPL; only the plug-ins listed in
// engine/plugins.txt are taken. The download is checked against a known fingerprint before use.
val engineVersion = "3.0.24"
val engineFingerprint = "3da3f37ee2b2b10e5c788d830a3b513658c36521ea1b0f966ed86eb69f41ef3c"
val engineResources = layout.buildDirectory.dir("engine")
val packEngine = OperatingSystem.current().isWindows || hasProperty("packEngine")

val fetchEngine by tasks.registering {
    val download = layout.buildDirectory.file("downloads/libvlc-$engineVersion.nupkg")
    val wantedList = file("engine/plugins.txt")
    val notices = file("engine/notices")
    val into = engineResources.map { it.dir("windows-x64/vlc") }
    inputs.property("version", engineVersion)
    inputs.file(wantedList)
    inputs.dir(notices)
    outputs.dir(into)
    onlyIf { packEngine }
    doLast {
        fun fingerprint(of: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            of.inputStream().use { input ->
                val chunk = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    digest.update(chunk, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        val archive = download.get().asFile
        if (!archive.isFile || fingerprint(archive) != engineFingerprint) {
            archive.parentFile.mkdirs()
            val address = "https://api.nuget.org/v3-flatcontainer/videolan.libvlc.windows/$engineVersion/videolan.libvlc.windows.$engineVersion.nupkg"
            URI(address).toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        }
        check(fingerprint(archive) == engineFingerprint) { "The player engine that was downloaded is not the expected file" }

        val wanted = wantedList.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { "build/x64/plugins/" + it.substringBefore('/') + "/lib" + it.substringAfter('/') + "_plugin.dll" }.toSet()
        val target = into.get().asFile
        delete(target)
        copy {
            from(zipTree(archive)) {
                include { it.isDirectory || it.path == "build/x64/libvlc.dll" || it.path == "build/x64/libvlccore.dll" || it.path in wanted }
                eachFile { path = path.removePrefix("build/x64/") }
                includeEmptyDirs = false
            }
            into(target)
        }
        copy {
            from(notices)
            into(target)
            filter { it.replace("@VERSION@", engineVersion) }
        }
        val found = fileTree(target).files.count { it.name.endsWith("_plugin.dll") }
        check(found == wanted.size) { "Only $found of the ${wanted.size} listed player plug-ins are in the package" }
        check(File(target, "libvlc.dll").isFile && File(target, "libvlccore.dll").isFile) { "The player engine itself is missing from the package" }
    }
}

// The packaging step that gathers the app's extra files must wait for the engine to be there.
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(fetchEngine) }

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
            // Extra files that travel with the app: the video player, for Windows.
            appResourcesRootDir.set(engineResources)

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
