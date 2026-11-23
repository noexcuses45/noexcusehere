package com.noexcusehere.mashwave.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.noexcusehere.mashwave.data.AppJson
import com.noexcusehere.mashwave.data.MediaEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

/** A song or a video file on this computer. */
@Serializable
data class LocalFile(
    val path: String,
    val size: Long = 0,
    val changed: Long = 0,
    val video: Boolean = false,
    /** True for a photo (then it is neither a song nor a video). */
    val picture: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val track: Int = 0,
    val durationMs: Long = 0,
    /** A picture for it (the album's cover), as a file on this computer; empty when there is none. */
    val cover: String = "",
) {
    /** The folder it sits in, by name. */
    val folder: String get() = File(path).parentFile?.name.orEmpty()

    fun entry(): MediaEntry = MediaEntry(
        uri = path,
        title = title.ifBlank { File(path).nameWithoutExtension },
        artist = artist,
        album = album,
        isVideo = video,
        durationMs = durationMs,
        track = track,
        art = cover,
    )
}

@Serializable
private data class LibrarySaved(val files: List<LocalFile> = emptyList())

/** Which files count as music and which as video, going by the ending of the name. */
object MediaKinds {
    val audio = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "aiff", "aif", "ape", "mka", "ac3", "dts", "wv")
    val video = setOf("mp4", "mkv", "avi", "mov", "wmv", "webm", "m4v", "mpg", "mpeg", "ts", "m2ts", "mts", "flv", "3gp", "ogv", "divx", "vob")

    /** True for a video, false for music, null for anything else. */
    fun isVideo(name: String): Boolean? {
        val ending = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ending) {
            in video -> true
            in audio -> false
            else -> null
        }
    }

    /** True for a photo the photo editor can open. */
    fun isPicture(name: String): Boolean = PhotoFiles.isPicture(name)

    /** Where music, videos and photos are usually kept on this computer. */
    fun usualFolders(): List<String> {
        val home = File(System.getProperty("user.home").orEmpty())
        val mac = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("mac")
        return listOf("Music", if (mac) "Movies" else "Videos", "Pictures").map { File(home, it) }.filter { it.isDirectory }.map { it.absolutePath }
    }
}

/** What a music file says about itself. */
class SongTags(val title: String, val artist: String, val album: String, val track: Int, val durationMs: Long, val picture: ByteArray?)

/** Reads the title, artist, album, length and cover picture out of a music file. */
object Tags {
    // The kinds the reader understands; other music still plays, it just goes by its file name.
    private val readable = setOf("mp3", "m4a", "m4b", "flac", "ogg", "wav", "wma", "aiff", "aif")
    private const val BIGGEST_PICTURE = 4 * 1024 * 1024

    init {
        // The reader likes to talk about every file it opens; nobody is listening.
        runCatching { Logger.getLogger("org.jaudiotagger").level = Level.OFF }
    }

    fun read(file: File): SongTags? {
        if (file.extension.lowercase(Locale.ROOT) !in readable) return null
        return runCatching {
            val audio = AudioFileIO.read(file)
            val tag = audio.tag
            fun field(key: FieldKey): String = runCatching { tag?.getFirst(key) }.getOrNull().orEmpty().trim()
            val seconds = runCatching { audio.audioHeader.preciseTrackLength }.getOrNull()
                ?: runCatching { audio.audioHeader.trackLength.toDouble() }.getOrDefault(0.0)
            SongTags(
                title = field(FieldKey.TITLE),
                artist = field(FieldKey.ARTIST).ifEmpty { field(FieldKey.ALBUM_ARTIST) },
                album = field(FieldKey.ALBUM),
                track = field(FieldKey.TRACK).substringBefore('/').trim().toIntOrNull() ?: 0,
                durationMs = (seconds * 1000).toLong().coerceAtLeast(0),
                picture = runCatching { tag?.firstArtwork?.binaryData }.getOrNull()?.takeIf { it.isNotEmpty() && it.size <= BIGGEST_PICTURE },
            )
        }.getOrNull()
    }
}

/**
 * The music, videos and photos on this computer: found by looking through the folders it is given,
 * remembered between visits, and looked through again when asked.
 */
class Library(private val dataDir: File, private val scope: CoroutineScope) {
    private val file = File(dataDir, "library.json")
    private val covers = File(dataDir, "covers")

    var songs by mutableStateOf(emptyList<LocalFile>())
        private set
    var videos by mutableStateOf(emptyList<LocalFile>())
        private set

    /** Photos, newest first. */
    var photos by mutableStateOf(emptyList<LocalFile>())
        private set

    /** True while the folders are being looked through. */
    var looking by mutableStateOf(false)
        private set

    /** How many files have been found so far in this look. */
    var foundSoFar by mutableIntStateOf(0)
        private set

    private var job: Job? = null

    /** Shows what was found last time straight away, then looks through [folders] afresh. */
    fun open(folders: List<String>) {
        job?.cancel()
        job = scope.launch {
            val kept = withContext(Dispatchers.IO) {
                runCatching { AppJson.decodeFromString(LibrarySaved.serializer(), file.readText()).files }.getOrDefault(emptyList())
            }
            // Files that have gone since are dropped quietly.
            show(kept)
            look(folders)
        }
    }

    /** Looks through [folders] again. */
    fun lookAgain(folders: List<String>) {
        job?.cancel()
        job = scope.launch { look(folders) }
    }

    private suspend fun look(folders: List<String>) {
        looking = true
        foundSoFar = 0
        try {
            val known = (songs + videos + photos).associateBy { it.path }
            val found = withContext(Dispatchers.IO) { scan(folders.map { File(it) }, known) }
            show(found)
            withContext(Dispatchers.IO) {
                runCatching {
                    val temp = File(file.parentFile, file.name + ".tmp")
                    temp.writeText(AppJson.encodeToString(LibrarySaved.serializer(), LibrarySaved(found)))
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } finally {
            looking = false
        }
    }

    private fun show(files: List<LocalFile>) {
        songs = files.filter { !it.video && !it.picture }.sortedWith(songOrder)
        videos = files.filter { it.video }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        photos = files.filter { it.picture }.sortedByDescending { it.changed }
    }

    /** Every music and video file under [folders]. Files in [known] that have not changed keep what was read from them. */
    internal suspend fun scan(folders: List<File>, known: Map<String, LocalFile>): List<LocalFile> {
        val context = currentCoroutineContext()
        val found = LinkedHashMap<String, LocalFile>()
        for (folder in folders) {
            if (!folder.isDirectory) continue
            runCatching {
                Files.walkFileTree(folder.toPath(), emptySet(), DEEPEST, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        context.ensureActive()
                        val name = dir.fileName?.toString().orEmpty()
                        // Hidden folders hold programs' own things, not anybody's music.
                        return if (dir != folder.toPath() && (name.startsWith(".") || name.startsWith("$"))) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                    }

                    override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (found.size >= MOST_FILES) return FileVisitResult.TERMINATE
                        val name = path.fileName?.toString().orEmpty()
                        val picture = MediaKinds.isPicture(name)
                        val video = if (picture) false else MediaKinds.isVideo(name) ?: return FileVisitResult.CONTINUE
                        if (!attrs.isRegularFile || name.startsWith("._")) return FileVisitResult.CONTINUE
                        val size = attrs.size()
                        // Album covers and the little pictures programs keep for themselves are not anybody's photos.
                        if (picture && (size < SMALLEST_PHOTO || name.substringBeforeLast('.').lowercase(Locale.ROOT) in notPhotos)) return FileVisitResult.CONTINUE
                        val full = path.toAbsolutePath().toString()
                        val old = known[full]
                        val changed = attrs.lastModifiedTime().toMillis()
                        found[full] = if (old != null && old.size == size && old.changed == changed && (old.cover.isEmpty() || File(old.cover).isFile)) {
                            old
                        } else if (picture) {
                            LocalFile(path = full, size = size, changed = changed, picture = true, title = tidyName(path.toFile().nameWithoutExtension))
                        } else {
                            describe(path.toFile(), size, changed, video)
                        }
                        foundSoFar = found.size
                        return FileVisitResult.CONTINUE
                    }

                    // A folder that may not be opened is skipped; the rest are still looked through.
                    override fun visitFileFailed(path: Path, problem: IOException): FileVisitResult = FileVisitResult.CONTINUE
                })
            }
            context.ensureActive()
        }
        return found.values.toList()
    }

    private fun describe(file: File, size: Long, changed: Long, video: Boolean): LocalFile {
        val plain = LocalFile(path = file.absolutePath, size = size, changed = changed, video = video, title = tidyName(file.nameWithoutExtension))
        if (video) return plain
        val tags = Tags.read(file)
        val picture = tags?.picture?.let { keepPicture(it) } ?: pictureBeside(file)
        return plain.copy(
            title = tags?.title?.ifEmpty { null } ?: plain.title,
            artist = tags?.artist.orEmpty(),
            album = tags?.album.orEmpty(),
            track = tags?.track ?: 0,
            durationMs = tags?.durationMs ?: 0,
            cover = picture.orEmpty(),
        )
    }

    /** Puts a picture found inside a song among the app's own files, once however many songs carry it. */
    private fun keepPicture(bytes: ByteArray): String? = runCatching {
        covers.mkdirs()
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        val target = File(covers, digest.joinToString("") { "%02x".format(it) })
        if (!target.isFile || target.length() != bytes.size.toLong()) target.writeBytes(bytes)
        target.absolutePath
    }.getOrNull()

    /** "cover.jpg" and the like, kept in the same folder as an album's songs. */
    private fun pictureBeside(song: File): String? {
        val folder = song.parentFile ?: return null
        for (name in besideNames) {
            for (ending in listOf("jpg", "jpeg", "png")) {
                val picture = File(folder, "$name.$ending")
                if (picture.isFile && picture.length() in 1..(4 * 1024 * 1024)) return picture.absolutePath
            }
        }
        return null
    }

    companion object {
        private const val DEEPEST = 12
        private const val MOST_FILES = 60_000
        private val besideNames = listOf("cover", "folder", "front", "album", "Cover", "Folder", "Front", "AlbumArt")
        private val notPhotos = setOf("cover", "folder", "front", "back", "album", "albumart", "albumartsmall", "thumb", "thumbnail", "icon", "poster", "fanart", "banner", "logo")
        private const val SMALLEST_PHOTO = 20 * 1024L

        /** Songs in the order of a record collection: by artist, then album, then the order on the album. */
        val songOrder: Comparator<LocalFile> = compareBy<LocalFile, String>(String.CASE_INSENSITIVE_ORDER) { it.artist.ifEmpty { "￿" } }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album.ifEmpty { "￿" } }
            .thenBy { it.track }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }

        /** A file name made a little more like a title: "01_my_song" reads "01 my song". */
        fun tidyName(name: String): String = name.replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifEmpty { name }
    }
}
