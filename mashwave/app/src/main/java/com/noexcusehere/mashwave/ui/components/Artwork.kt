package com.noexcusehere.mashwave.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.ui.theme.Violet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/** Loads and caches album covers and video thumbnails. */
object ArtworkLoader {
    private const val SMALL = 240
    private const val LARGE = 800
    private const val RETRY_AFTER_MS = 20_000L

    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt().coerceIn(8 * 1024, 96 * 1024),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = max(1, value.width * value.height * 4 / 1024)
    }

    /** Files that had no picture last time we looked, and when - so we don't retry on every scroll. */
    private val failures = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val accents = ConcurrentHashMap<String, Int>()
    private val dispatcher = Dispatchers.IO.limitedParallelism(4)

    /** Goes up whenever a new picture arrives, so placeholders can check whether it is theirs. */
    val generation = mutableIntStateOf(0)

    private fun key(entry: MediaEntry, large: Boolean) = entry.artKey + if (large) "@L" else "@S"

    /** A picture already in memory: the exact size if we have it, otherwise the other size. */
    fun cached(entry: MediaEntry, large: Boolean): ImageBitmap? =
        cache.get(key(entry, large)) ?: cache.get(key(entry, !large))

    private fun failedRecently(failKey: String): Boolean {
        val at = failures[failKey] ?: return false
        return SystemClock.elapsedRealtime() - at < RETRY_AFTER_MS
    }

    suspend fun load(context: Context, entry: MediaEntry, large: Boolean): ImageBitmap? {
        val key = key(entry, large)
        cache.get(key)?.let { return it }
        // Songs of one album share a cache slot, but each file is tried (and can fail) by itself:
        // a track without its own picture must not hide the cover its album-mates carry.
        val failKey = entry.uri + if (large) "@L" else "@S"
        if (failedRecently(failKey)) return cache.get(key(entry, !large))
        return withContext(dispatcher) {
            // One decode per album at a time; whoever waited finds the result in the cache.
            locks.getOrPut(key) { Mutex() }.withLock {
                cache.get(key) ?: run {
                    val bitmap = runCatching { decode(context.applicationContext, entry, if (large) LARGE else SMALL) }
                        .onFailure { Log.w("Mashwave", "Cover failed for ${entry.title}", it) }
                        .getOrNull()
                    if (bitmap == null) {
                        failures[failKey] = SystemClock.elapsedRealtime()
                        cache.get(key(entry, !large))
                    } else {
                        if (!accents.containsKey(entry.artKey)) {
                            runCatching { accents[entry.artKey] = pickAccent(bitmap) }
                        }
                        bitmap.asImageBitmap().also {
                            cache.put(key, it)
                            generation.intValue++
                        }
                    }
                }
            }
        }
    }

    /** A colour taken from the cover, used to tint the player background. */
    suspend fun accent(context: Context, entry: MediaEntry): Color {
        accents[entry.artKey]?.let { return Color(it) }
        load(context, entry, large = false)
        return accents[entry.artKey]?.let { Color(it) } ?: placeholderColors(entry).first
    }

    /** Forget earlier misses, e.g. after the library changed. */
    fun forget() {
        failures.clear()
    }

    private fun pickAccent(bitmap: Bitmap): Int {
        val palette = Palette.from(bitmap).maximumColorCount(12).generate()
        val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.darkVibrantSwatch
            ?: palette.mutedSwatch ?: palette.dominantSwatch
        return swatch?.rgb ?: 0xFF8B5CF6.toInt()
    }

    private fun decode(context: Context, entry: MediaEntry, size: Int): Bitmap? {
        if (entry.isStream) return null
        val uri = Uri.parse(entry.uri)
        val resolver = context.contentResolver
        if (uri.scheme == "content") {
            if (Build.VERSION.SDK_INT >= 29) {
                val thumb = runCatching { resolver.loadThumbnail(uri, Size(size, size), null) }.getOrNull()
                if (thumb != null) return thumb
            }
            val art = entry.artworkUri
            if (art != null) {
                val fromAlbum = runCatching {
                    resolver.openInputStream(art)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
                if (fromAlbum != null) return fromAlbum.fit(size)
            }
        }
        // Last resort: look inside the file itself.
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            if (entry.isVideo) {
                retriever.frameAtTime?.fit(size)
            } else {
                val bytes = retriever.embeddedPicture ?: return null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun Bitmap.fit(size: Int): Bitmap {
        val longest = max(width, height)
        if (longest <= size) return this
        val scale = size.toFloat() / longest
        return Bitmap.createScaledBitmap(this, max(1, (width * scale).toInt()), max(1, (height * scale).toInt()), true)
    }
}

private val placeholderPalette = listOf(
    Color(0xFF7C3AED) to Color(0xFFDB2777),
    Color(0xFF0EA5E9) to Color(0xFF6366F1),
    Color(0xFFF97316) to Color(0xFFDB2777),
    Color(0xFF14B8A6) to Color(0xFF3B82F6),
    Color(0xFFE11D48) to Color(0xFF7C3AED),
    Color(0xFF8B5CF6) to Color(0xFF22D3EE),
    Color(0xFFF59E0B) to Color(0xFFEF4444),
    Color(0xFF10B981) to Color(0xFF0EA5E9),
)

/** Every item without a cover gets its own stable pair of colours. */
fun placeholderColors(entry: MediaEntry?): Pair<Color, Color> {
    val seed = entry?.let { (it.album.ifBlank { it.title } + it.artist).hashCode() } ?: 0
    return placeholderPalette[abs(seed % placeholderPalette.size)]
}

@Composable
fun Artwork(
    entry: MediaEntry?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    large: Boolean = false,
) {
    val context = LocalContext.current
    val generation = ArtworkLoader.generation.intValue
    // Keyed on the entry so a new track never shows the previous track's picture.
    var bitmap by remember(entry?.uri, large) { mutableStateOf(entry?.let { ArtworkLoader.cached(it, large) }) }
    // Runs again whenever any new picture arrives: an album-mate may just have supplied ours.
    LaunchedEffect(entry?.uri, large, generation) {
        if (entry != null) ArtworkLoader.load(context, entry, large)?.let { bitmap = it }
    }
    val colors = placeholderColors(entry)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(colors.first, colors.second))),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = when {
                    entry?.isStream == true -> Icons.Rounded.Podcasts
                    entry?.isVideo == true -> Icons.Rounded.Movie
                    else -> Icons.Rounded.MusicNote
                },
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.fillMaxSize(0.45f),
            )
        }
    }
}

/** The cover's accent colour, animated when the track changes. */
@Composable
fun rememberAccent(entry: MediaEntry?): Color {
    val context = LocalContext.current
    val target by produceState(initialValue = entry?.let { placeholderColors(it).first } ?: Violet, entry?.uri) {
        value = entry?.let { ArtworkLoader.accent(context, it) } ?: Violet
    }
    val animated by animateColorAsState(target, tween(600), label = "accent")
    return animated
}
