package com.noexcusehere.mashwave.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.media.ExifInterface
import com.noexcusehere.mashwave.data.MediaEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** Reads what the editor needs to know about a video or photo before it goes on the timeline. */
object ClipProbe {
    const val DEFAULT_PHOTO_MS = 3000L

    /** Builds timeline clips for the picked files, skipping anything that cannot be read. */
    suspend fun describe(context: Context, uris: List<Uri>): List<EditClip> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri -> runCatching { describe(context, uri) }.getOrNull() }
    }

    suspend fun describeEntries(context: Context, entries: List<MediaEntry>): List<EditClip> = withContext(Dispatchers.IO) {
        entries.mapNotNull { entry ->
            val uri = Uri.parse(entry.uri)
            runCatching { if (entry.isImage) image(context, uri, entry.title) else video(context, uri, entry.title) }
                .onFailure { Log.w("UltimateBeats", "Could not read ${entry.title} for the editor", it) }
                .getOrNull()
        }
    }

    private fun describe(context: Context, uri: Uri): EditClip? {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        val name = displayName(context, uri)
        val extension = name.substringAfterLast('.', "").lowercase()
        val looksLikeImage = type.startsWith("image/") ||
            (!type.startsWith("video/") && extension in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif"))
        // Whatever the label says, try the other reading too before giving up on a file.
        val first = runCatching { if (looksLikeImage) image(context, uri, name) else video(context, uri, name) }
            .onFailure { Log.w("UltimateBeats", "Could not read $name for the editor", it) }
            .getOrNull()
        return first ?: runCatching { if (looksLikeImage) video(context, uri, name) else image(context, uri, name) }.getOrNull()
    }

    private fun video(context: Context, uri: Uri, title: String): EditClip? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return null
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            if (duration <= 0) return null
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val turned = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            // Phone videos are often stored sideways with a note saying "turn me"; use the shape as seen.
            if (turned % 180 != 0) width = height.also { height = width }
            return EditClip(
                id = EditorStore.newId(),
                uri = uri.toString(),
                title = title.substringBeforeLast('.').ifBlank { "Video" },
                sourceDurationMs = duration,
                startMs = 0,
                endMs = duration,
                width = width,
                height = height,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun image(context: Context, uri: Uri, title: String): EditClip? {
        // Only the picture's size is read here. (Measuring never hands back a picture, so the
        // result of the call itself says nothing about success - the measured size does.)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var width = bounds.outWidth
        var height = bounds.outHeight
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
        }.getOrNull() ?: 1
        if (orientation in setOf(5, 6, 7, 8)) width = height.also { height = width }
        return EditClip(
            id = EditorStore.newId(),
            uri = uri.toString(),
            title = title.substringBeforeLast('.').ifBlank { "Photo" },
            isImage = true,
            startMs = 0,
            endMs = DEFAULT_PHOTO_MS,
            width = width,
            height = height,
        )
    }

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
}

/** Small pictures of single moments in a clip, for the film strip on the timeline. */
object FrameCache {
    private const val HEIGHT = 96
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = max(1, value.width * value.height * 4 / 1024)
    }
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val perFile = ConcurrentHashMap<String, Mutex>()
    private val dispatcher = Dispatchers.IO.limitedParallelism(2)

    /** Goes up each time a new picture is ready, so the timeline knows to redraw. */
    val generation = mutableIntStateOf(0)

    /** Frames are shared between nearby moments: one picture per [STEP_MS] of the original. */
    const val STEP_MS = 1000L

    private fun key(clip: EditClip, sourceMs: Long) = clip.uri + "#" + if (clip.isImage) 0 else sourceMs / STEP_MS

    /** Returns the picture if it is ready; otherwise starts fetching it and returns the nearest one we have. */
    fun get(context: Context, scope: CoroutineScope, clip: EditClip, sourceMs: Long): ImageBitmap? {
        val key = key(clip, sourceMs)
        cache.get(key)?.let { return it }
        if (key !in failed && loading.add(key)) {
            val app = context.applicationContext
            scope.launch(dispatcher) {
                val bitmap = perFile.getOrPut(clip.uri) { Mutex() }.withLock {
                    runCatching { decode(app, clip, sourceMs / STEP_MS * STEP_MS) }.getOrNull()
                }
                if (bitmap != null) {
                    cache.put(key, bitmap.asImageBitmap())
                    generation.intValue++
                } else {
                    failed.add(key)
                }
                loading.remove(key)
            }
        }
        // Until it arrives, any other moment of the same clip is better than a blank box.
        return cache.get(clip.uri + "#" + (clip.startMs / STEP_MS)) ?: cache.get(clip.uri + "#0")
    }

    private fun decode(context: Context, clip: EditClip, sourceMs: Long): Bitmap? {
        val uri = Uri.parse(clip.uri)
        if (clip.isImage) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outHeight / (sample * 2) >= HEIGHT) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.fit()
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val timeUs = sourceMs * 1000
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                val scale = if (clip.height > 0 && clip.width > 0) HEIGHT.toFloat() / minOf(clip.width, clip.height) else 0.2f
                val w = max(2, ((if (clip.width > 0) clip.width else 640) * scale).toInt())
                val h = max(2, ((if (clip.height > 0) clip.height else 360) * scale).toInt())
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, max(w, h), max(w, h))
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            frame?.fit()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun Bitmap.fit(): Bitmap {
        if (height <= HEIGHT) return this
        val scale = HEIGHT.toFloat() / height
        return Bitmap.createScaledBitmap(this, max(1, (width * scale).toInt()), HEIGHT, true)
    }
}
