package com.noexcusehere.mashwave.desktop

import com.noexcusehere.mashwave.data.AppJson
import com.noexcusehere.mashwave.data.ResumePoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where the app keeps its files on this computer. */
object AppFolders {
    const val NAME = "Ultimate Media Manager"

    /** Sign-ins, lists, favourites: things that must survive. [override] is for trying the app out without touching the real ones. */
    fun data(override: String? = System.getProperty("umm.data") ?: System.getenv("UMM_DATA")): File {
        if (!override.isNullOrBlank()) return File(override).apply { mkdirs() }
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val home = System.getProperty("user.home").orEmpty()
        val base = when {
            os.contains("win") -> System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { File(it) } ?: File(home, "AppData\\Roaming")
            os.contains("mac") -> File(home, "Library/Application Support")
            else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) } ?: File(home, ".local/share")
        }
        return File(base, NAME).apply { mkdirs() }
    }

    /**
     * A file in [folder] called [name] with [ending] that is not taken yet: letters a file's name
     * may not have are left out, and "(2)", "(3)"... is added while the name is in use.
     */
    fun freeFile(folder: File, name: String, ending: String, fallback: String): File {
        var clean = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').ifEmpty { fallback }
        // A computer set up for plain English letters only cannot have every character in a file's
        // name; there the name is spelled with the letters it does have.
        if (runCatching { File(folder, "$clean.$ending").toPath() }.isFailure) {
            clean = clean.replace('\u00D7', 'x').replace(Regex("[^\\x20-\\x7E]"), "").replace(Regex("\\s+"), " ").trim().ifEmpty { fallback }
        }
        var target = File(folder, "$clean.$ending")
        var n = 2
        while (target.exists()) target = File(folder, "$clean ($n).$ending").also { n++ }
        return target
    }
}

/** The app's own small settings and the places videos were stopped at. */
@Serializable
data class DeskSaved(
    val volume: Int = 100,
    val muted: Boolean = false,
    val look: String = "FIT",
    val resume: Map<String, ResumePoint> = emptyMap(),
    val windowWidth: Int = 1280,
    val windowHeight: Int = 800,
    val maximised: Boolean = false,
    /** Folders looked through for music, videos and photos; null until the user changes the usual ones. */
    val folders: List<String>? = null,
    val shuffle: Boolean = false,
    val repeat: String = "OFF",
    /** Set once the pictures folder has been added to a list of folders saved before the app had a photo editor. */
    val photosAdded: Boolean = false,
)

class DeskStore(dir: File, scope: CoroutineScope) {
    private val file = File(dir.apply { mkdirs() }, "desktop.json")
    private val writes = Channel<DeskSaved>(Channel.CONFLATED)

    var saved: DeskSaved = runCatching {
        if (file.exists()) AppJson.decodeFromString(DeskSaved.serializer(), file.readText()) else DeskSaved()
    }.getOrDefault(DeskSaved())
        private set

    init {
        scope.launch(Dispatchers.IO) {
            for (snapshot in writes) write(snapshot)
        }
    }

    fun edit(change: (DeskSaved) -> DeskSaved) {
        val next = change(saved)
        if (next == saved) return
        saved = next
        writes.trySend(next)
    }

    /** Writes straight away; for when the app is closing. */
    fun flush() = write(saved)

    private fun write(snapshot: DeskSaved) {
        runCatching {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(AppJson.encodeToString(DeskSaved.serializer(), snapshot))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
