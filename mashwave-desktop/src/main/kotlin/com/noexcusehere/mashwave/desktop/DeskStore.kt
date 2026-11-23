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
    /** Folders looked through for music and videos; null until the user changes the usual ones. */
    val folders: List<String>? = null,
    val shuffle: Boolean = false,
    val repeat: String = "OFF",
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
