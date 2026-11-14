package com.noexcusehere.mashwave.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Reads the songs and videos the phone already knows about (Android's MediaStore index). */
class MediaScanner(private val context: Context) {

    fun scanAudio(): List<MediaEntry> {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.IS_RINGTONE,
            MediaStore.Audio.Media.IS_NOTIFICATION,
            MediaStore.Audio.Media.IS_ALARM,
        )
        val out = ArrayList<MediaEntry>()
        runCatching {
            context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val added = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val size = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val track = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val data = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val name = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val ringtone = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RINGTONE)
                val notification = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_NOTIFICATION)
                val alarm = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_ALARM)
                while (c.moveToNext()) {
                    if (c.int(ringtone) != 0 || c.int(notification) != 0 || c.int(alarm) != 0) continue
                    val path = c.string(data)
                    val displayName = c.string(name)
                    out += MediaEntry(
                        uri = ContentUris.withAppendedId(collection, c.getLong(id)).toString(),
                        title = cleanTitle(c.string(title), displayName, path),
                        artist = c.string(artist).takeUnless { it.isBlank() || it == "<unknown>" } ?: "",
                        album = c.string(album).takeUnless { it.isBlank() || it == "<unknown>" } ?: "",
                        albumId = c.long(albumId),
                        durationMs = c.long(duration),
                        isVideo = false,
                        folder = parentOf(path),
                        dateAdded = c.long(added),
                        size = c.long(size),
                        track = c.int(track) % 1000,
                    )
                }
            }
        }.onFailure { Log.w("Mashwave", "Could not read the song list", it) }
        return out
    }

    fun scanVideo(): List<MediaEntry> {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
        )
        val out = ArrayList<MediaEntry>()
        runCatching {
            context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val title = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val duration = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val added = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val size = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val data = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val width = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val height = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                while (c.moveToNext()) {
                    val path = c.string(data)
                    out += MediaEntry(
                        uri = ContentUris.withAppendedId(collection, c.getLong(id)).toString(),
                        title = cleanTitle(c.string(title), c.string(name), path),
                        durationMs = c.long(duration),
                        isVideo = true,
                        folder = parentOf(path),
                        dateAdded = c.long(added),
                        size = c.long(size),
                        width = c.int(width),
                        height = c.int(height),
                    )
                }
            }
        }.onFailure { Log.w("Mashwave", "Could not read the video list", it) }
        return out
    }

    /** Every photo on the phone the app is allowed to see, newest first. */
    fun scanImages(): List<MediaEntry> {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        val out = ArrayList<MediaEntry>()
        runCatching {
            context.contentResolver.query(collection, projection, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val added = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val size = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val data = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val width = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val height = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                while (c.moveToNext()) {
                    val path = c.string(data)
                    // Empty leftovers (failed downloads and the like) cannot be shown anyway.
                    if (c.long(size) in 1..199) continue
                    out += MediaEntry(
                        uri = ContentUris.withAppendedId(collection, c.getLong(id)).toString(),
                        title = cleanTitle("", c.string(name), path),
                        isImage = true,
                        folder = parentOf(path),
                        dateAdded = c.long(added),
                        size = c.long(size),
                        width = c.int(width),
                        height = c.int(height),
                    )
                }
            }
        }.onFailure { Log.w("Mashwave", "Could not read the photo list", it) }
        return out
    }

    private fun cleanTitle(title: String, displayName: String, path: String): String {
        if (title.isNotBlank()) return title
        val base = displayName.ifBlank { path.substringAfterLast('/') }
        return base.substringBeforeLast('.').ifBlank { "Untitled" }
    }

    private fun parentOf(path: String): String = if (path.isBlank()) "" else File(path).parent ?: ""

    private fun Cursor.string(i: Int): String = if (isNull(i)) "" else getString(i) ?: ""
    private fun Cursor.long(i: Int): Long = if (isNull(i)) 0 else getLong(i)
    private fun Cursor.int(i: Int): Int = if (isNull(i)) 0 else getInt(i)
}

class LibraryRepository(private val context: Context, private val scope: CoroutineScope, private val store: UserStore) {
    private val scanner = MediaScanner(context)
    private var scanJob: Job? = null
    private var observing = false
    private var foundSongs: List<MediaEntry> = emptyList()
    private var foundVideos: List<MediaEntry> = emptyList()

    /** Songs on the phone plus the ones added from Google Drive and other apps. */
    val songs = MutableStateFlow<List<MediaEntry>>(emptyList())

    /** Videos on the phone plus the ones added from Google Photos, Drive and other apps. */
    val videos = MutableStateFlow<List<MediaEntry>>(emptyList())

    /** Photos on the phone, newest first. Only used by the video editor. */
    val photos = MutableStateFlow<List<MediaEntry>>(emptyList())
    val loading = MutableStateFlow(false)
    val scannedOnce = MutableStateFlow(false)

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh(debounceMs = 1500)
    }

    init {
        // Things added from other apps show up (or go away) without waiting for a rescan.
        scope.launch {
            var last: List<MediaEntry>? = null
            store.data.collect { data ->
                if (data.imported !== last) {
                    last = data.imported
                    publish()
                }
            }
        }
    }

    fun hasAudioPermission(): Boolean = granted(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE,
    )

    /** True when the app may list photos (all of them, or the ones the user picked on Android 14+). */
    fun hasPhotoPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= 34 ->
            granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** True when the user chose "only the photos I select" instead of all photos (Android 14+). */
    fun photoAccessIsPartial(): Boolean = Build.VERSION.SDK_INT >= 34 &&
        !granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun refresh(debounceMs: Long = 0) {
        scanJob?.cancel()
        scanJob = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            loading.value = true
            try {
                val (audio, video) = withContext(Dispatchers.IO) {
                    scanner.scanAudio() to scanner.scanVideo()
                }
                foundSongs = audio
                foundVideos = video
                publish()
                scannedOnce.value = true
                photos.value = if (hasPhotoPermission()) withContext(Dispatchers.IO) { scanner.scanImages() } else emptyList()
            } finally {
                loading.value = false
            }
        }
        if (!observing) {
            observing = true
            runCatching {
                context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
                context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
                context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            }
        }
    }

    private fun publish() {
        val imported = store.data.value.imported
        val known = HashSet<String>()
        foundSongs.forEach { known += it.uri }
        foundVideos.forEach { known += it.uri }
        val extra = imported.filter { it.uri !in known }
        songs.value = (foundSongs + extra.filter { !it.isVideo && !it.isImage }).sortedBy { it.title.lowercase() }
        videos.value = (foundVideos + extra.filter { it.isVideo }).sortedByDescending { it.dateAdded }
    }

    /** Finds the library entry for a uri, e.g. to show full details for an item restored from disk. */
    fun find(uri: String): MediaEntry? =
        songs.value.firstOrNull { it.uri == uri } ?: videos.value.firstOrNull { it.uri == uri }
}

/** Builds an entry for a file or stream that is not in the phone's media index. */
fun describeUri(context: Context, uri: Uri, mimeHint: String? = null): MediaEntry {
    val text = uri.toString()
    var title = ""
    var size = 0L
    var mime = mimeHint
    if (uri.scheme == "content") {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (n >= 0 && !c.isNull(n)) title = c.getString(n) ?: ""
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        if (mime == null) mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    }
    if (title.isBlank()) title = uri.lastPathSegment?.substringAfterLast('/') ?: ""
    val lower = (title.ifBlank { text }).lowercase().substringBefore('?')
    val videoExt = listOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".3gp", ".ts", ".flv", ".mpg", ".mpeg", ".m3u8", ".mpd", ".wmv")
    val audioExt = listOf(".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".wav", ".wma", ".amr", ".mka", ".aiff", ".mid")
    val imageExt = listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".bmp", ".gif")
    val isImage = mime?.startsWith("image/") == true ||
        (mime?.startsWith("video/") != true && mime?.startsWith("audio/") != true && imageExt.any { lower.endsWith(it) })
    val isVideo = when {
        isImage -> false
        mime?.startsWith("video/") == true -> true
        mime?.startsWith("audio/") == true -> false
        audioExt.any { lower.endsWith(it) } -> false
        videoExt.any { lower.endsWith(it) } -> true
        else -> uri.scheme == "rtsp"
    }
    val display = title.substringBeforeLast('.').ifBlank { title }.ifBlank { uri.host ?: "Stream" }
    return MediaEntry(uri = text, title = display, isVideo = isVideo, size = size, isImage = isImage)
}
