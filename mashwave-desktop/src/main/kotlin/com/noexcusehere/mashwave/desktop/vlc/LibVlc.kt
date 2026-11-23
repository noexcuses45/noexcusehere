package com.noexcusehere.mashwave.desktop.vlc

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The handful of functions of VLC's playing engine (libVLC 3) this app uses. VLC itself is not
 * part of the app: the copy installed on the computer is loaded when the app starts.
 */
@Suppress("FunctionName")
interface LibVlc : Library {
    fun libvlc_new(argc: Int, argv: Array<String>?): Pointer?
    fun libvlc_release(instance: Pointer)
    fun libvlc_get_version(): String?
    fun libvlc_set_user_agent(instance: Pointer, name: String, http: String)

    fun libvlc_media_new_location(instance: Pointer, mrl: String): Pointer?
    fun libvlc_media_new_path(instance: Pointer, path: String): Pointer?
    fun libvlc_media_add_option(media: Pointer, option: String)
    fun libvlc_media_release(media: Pointer)
    fun libvlc_media_tracks_get(media: Pointer, tracks: PointerByReference): Int
    fun libvlc_media_tracks_release(tracks: Pointer, count: Int)
    fun libvlc_media_get_stats(media: Pointer, stats: Pointer): Int

    fun libvlc_media_player_new(instance: Pointer): Pointer?
    fun libvlc_media_player_release(player: Pointer)
    fun libvlc_media_player_set_media(player: Pointer, media: Pointer?)
    fun libvlc_media_player_get_media(player: Pointer): Pointer?
    fun libvlc_media_player_play(player: Pointer): Int
    fun libvlc_media_player_set_pause(player: Pointer, pause: Int)
    fun libvlc_media_player_stop(player: Pointer)
    fun libvlc_media_player_get_state(player: Pointer): Int
    fun libvlc_media_player_get_time(player: Pointer): Long
    fun libvlc_media_player_set_time(player: Pointer, time: Long)
    fun libvlc_media_player_get_length(player: Pointer): Long
    fun libvlc_media_player_is_seekable(player: Pointer): Int
    fun libvlc_media_player_set_rate(player: Pointer, rate: Float): Int

    fun libvlc_audio_set_volume(player: Pointer, volume: Int): Int
    fun libvlc_audio_set_mute(player: Pointer, mute: Int)
    fun libvlc_audio_get_track(player: Pointer): Int
    fun libvlc_audio_set_track(player: Pointer, id: Int): Int
    fun libvlc_audio_get_track_description(player: Pointer): Pointer?
    fun libvlc_video_get_spu(player: Pointer): Int
    fun libvlc_video_set_spu(player: Pointer, id: Int): Int
    fun libvlc_video_get_spu_description(player: Pointer): Pointer?
    fun libvlc_track_description_list_release(list: Pointer)

    fun libvlc_video_set_callbacks(player: Pointer, lock: LockCallback, unlock: UnlockCallback?, display: DisplayCallback?, opaque: Pointer?)
    fun libvlc_video_set_format_callbacks(player: Pointer, setup: FormatCallback, cleanup: CleanupCallback?)

    /** VLC is about to draw a picture: tell it where the memory for it is. */
    fun interface LockCallback : Callback {
        fun lock(opaque: Pointer?, planes: Pointer): Pointer?
    }

    fun interface UnlockCallback : Callback {
        fun unlock(opaque: Pointer?, picture: Pointer?, planes: Pointer?)
    }

    /** The picture in the memory is complete and it is time to show it. */
    fun interface DisplayCallback : Callback {
        fun display(opaque: Pointer?, picture: Pointer?)
    }

    /** VLC says how big the video is; we answer with the size and layout we want it in. */
    fun interface FormatCallback : Callback {
        fun format(opaque: Pointer, chroma: Pointer, width: Pointer, height: Pointer, pitches: Pointer, lines: Pointer): Int
    }

    fun interface CleanupCallback : Callback {
        fun cleanup(opaque: Pointer?)
    }

    companion object {
        // What libvlc_media_player_get_state answers.
        const val OPENING = 1
        const val BUFFERING = 2
        const val PLAYING = 3
        const val PAUSED = 4
        const val STOPPED = 5
        const val ENDED = 6
        const val ERROR = 7
    }
}

/** Finding the VLC installed on this computer and starting its engine. */
object VlcFinder {
    /** [builtIn] is true for the engine that comes packed with the app, false for a VLC installed on the computer. */
    class Found(val lib: LibVlc, val version: String, val folder: String, val builtIn: Boolean)

    /** The engine packed with the app, when this build has one (the Windows installer does). */
    fun packed(): File? = System.getProperty("compose.application.resources.dir")
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it, "vlc") }
        ?.takeIf { it.isDirectory && hasEngine(it) }

    /** Why the last [load] failed, in plain words; empty when it worked or was never tried. */
    @Volatile
    var problem: String = ""
        private set

    /**
     * Folders worth looking in, most likely first: the engine packed with the app, then a VLC
     * installed on the computer. Naming one (VLC_DIR) means: look only there.
     */
    fun folders(): List<File> {
        val named = (System.getProperty("vlc.dir") ?: System.getenv("VLC_DIR"))?.takeIf { it.isNotBlank() }
        if (named != null) return listOf(File(named))
        val list = ArrayList<File>()
        packed()?.let { list += it }
        when {
            Platform.isWindows() -> {
                registryFolder()?.let { list += it }
                for (root in listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramW6432"), System.getenv("ProgramFiles(x86)"))) {
                    list += File(root, "VideoLAN\\VLC")
                }
                list += File("C:\\Program Files\\VideoLAN\\VLC")
            }
            Platform.isMac() -> {
                list += File("/Applications/VLC.app/Contents/MacOS/lib")
                list += File(System.getProperty("user.home"), "Applications/VLC.app/Contents/MacOS/lib")
            }
            else -> {
                list += File("/usr/lib/x86_64-linux-gnu")
                list += File("/usr/lib/aarch64-linux-gnu")
                list += File("/usr/lib64")
                list += File("/usr/lib")
                list += File("/usr/local/lib")
                list += File("/snap/vlc/current/usr/lib")
            }
        }
        return list.distinctBy { it.absolutePath.lowercase() }
    }

    /** Where the VLC installer said it put the program (Windows keeps a note of that). */
    private fun registryFolder(): File? {
        for (key in listOf("HKLM\\SOFTWARE\\VideoLAN\\VLC", "HKLM\\SOFTWARE\\WOW6432Node\\VideoLAN\\VLC", "HKCU\\SOFTWARE\\VideoLAN\\VLC")) {
            val text = runCatching {
                val process = ProcessBuilder("reg", "query", key, "/v", "InstallDir").redirectErrorStream(true).start()
                val out = process.inputStream.bufferedReader().readText()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
                out
            }.getOrNull() ?: continue
            val line = text.lineSequence().firstOrNull { it.contains("InstallDir") && it.contains("REG_SZ") } ?: continue
            val path = line.substringAfter("REG_SZ").trim()
            if (path.isNotEmpty()) return File(path)
        }
        return null
    }

    private fun hasEngine(folder: File): Boolean = when {
        Platform.isWindows() -> File(folder, "libvlc.dll").isFile
        Platform.isMac() -> File(folder, "libvlc.dylib").isFile
        else -> folder.listFiles { f -> f.name.startsWith("libvlc.so") }?.isNotEmpty() == true
    }

    /** Loads VLC's engine, or returns null (with [problem] saying why) when it is not there. */
    fun load(): Found? {
        val candidates = folders().filter { it.isDirectory && hasEngine(it) }
        if (candidates.isEmpty()) {
            problem = "The video player was not found on this computer."
            return null
        }
        val own = packed()?.absolutePath
        var last = ""
        for (folder in candidates) {
            try {
                val dir = folder.absolutePath
                NativeLibrary.addSearchPath("libvlccore", dir)
                NativeLibrary.addSearchPath("vlccore", dir)
                NativeLibrary.addSearchPath("libvlc", dir)
                NativeLibrary.addSearchPath("vlc", dir)
                if (Platform.isMac()) {
                    // On a Mac the engine has to be told where its plug-ins are.
                    runCatching { Native.load("c", LibC::class.java).setenv("VLC_PLUGIN_PATH", File(folder.parentFile, "plugins").absolutePath, 1) }
                }
                // The engine leans on a second file next to it, which has to be in first.
                NativeLibrary.getInstance(if (Platform.isWindows()) "libvlccore" else "vlccore")
                val lib = Native.load(
                    if (Platform.isWindows()) "libvlc" else "vlc",
                    LibVlc::class.java,
                    mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"),
                )
                val version = lib.libvlc_get_version().orEmpty()
                if (!version.startsWith("3.")) {
                    last = "The VLC on this computer is version ${version.substringBefore(' ')}; version 3 is needed."
                    continue
                }
                problem = ""
                return Found(lib, version.substringBefore(' '), dir, builtIn = dir == own)
            } catch (e: Throwable) {
                // The usual cause on Windows: a 32-bit VLC, which a 64-bit program cannot use.
                last = if (folder.absolutePath == own) {
                    "The app's own video player could not be started (${e.javaClass.simpleName})."
                } else if (Platform.isWindows()) {
                    "VLC is installed, but it is the 32-bit kind. Install the 64-bit VLC from videolan.org instead."
                } else {
                    "VLC is installed but could not be loaded (${e.javaClass.simpleName})."
                }
            }
        }
        problem = last.ifEmpty { "VLC could not be loaded." }
        return null
    }

    @Suppress("FunctionName")
    private interface LibC : Library {
        fun setenv(name: String, value: String, overwrite: Int): Int
    }
}
