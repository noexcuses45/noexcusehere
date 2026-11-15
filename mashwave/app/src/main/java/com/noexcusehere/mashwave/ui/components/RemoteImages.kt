package com.noexcusehere.mashwave.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noexcusehere.mashwave.iptv.TvNet
import com.noexcusehere.mashwave.ui.theme.Surface3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Pictures from the web - channel logos, movie posters. Each is fetched once, kept on the phone
 * (in the cache folder Android may clear when space runs low) and held in memory while in use.
 */
object RemoteImages {
    const val LOGO = 200
    const val POSTER = 420
    const val WIDE = 900

    private const val RETRY_AFTER_MS = 5 * 60_000L
    private const val MAX_DOWNLOAD = 3 * 1024 * 1024
    private const val DISK_LIMIT = 80L * 1024 * 1024

    private val memory = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceIn(8 * 1024, 64 * 1024),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = max(1, value.width * value.height * 4 / 1024)
    }
    private val failures = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val dispatcher = Dispatchers.IO.limitedParallelism(6)
    private val trimmed = AtomicBoolean(false)

    private fun key(url: String, size: Int) = "$size|$url"

    fun cached(url: String, size: Int): ImageBitmap? = memory.get(key(url, size))

    suspend fun load(context: Context, url: String, size: Int): ImageBitmap? {
        if (!url.startsWith("http", ignoreCase = true)) return null
        val key = key(url, size)
        memory.get(key)?.let { return it }
        failures[url]?.let { if (SystemClock.elapsedRealtime() - it < RETRY_AFTER_MS) return null }
        return withContext(dispatcher) {
            locks.getOrPut(key) { Mutex() }.withLock {
                memory.get(key) ?: run {
                    val bitmap = runCatching { fetch(context.applicationContext, url, size) }.getOrNull()
                    if (bitmap == null) {
                        failures[url] = SystemClock.elapsedRealtime()
                        null
                    } else {
                        bitmap.asImageBitmap().also { memory.put(key, it) }
                    }
                }
            }.also { locks.remove(key) }
        }
    }

    private fun folder(context: Context): File = File(context.cacheDir, "tv-pictures").apply { mkdirs() }

    private fun fileFor(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return File(folder(context), digest.joinToString("") { "%02x".format(it) })
    }

    private fun fetch(context: Context, url: String, size: Int): Bitmap? {
        val file = fileFor(context, url)
        if (!file.exists() || file.length() == 0L) {
            val bytes = TvNet.open(url, connectMs = 8_000, readMs = 12_000).use { response ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                val stream = response.stream
                while (out.size() <= MAX_DOWNLOAD) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                if (out.size() > MAX_DOWNLOAD) return null
                out.toByteArray()
            }
            if (bytes.isEmpty()) return null
            file.writeBytes(bytes)
            if (trimmed.compareAndSet(false, true)) trim(context)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            // Not a picture after all (an error page, most likely).
            file.delete()
            return null
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= size * 1.5f) return bitmap
        val scale = size.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).toInt()), max(1, (bitmap.height * scale).toInt()), true)
    }

    /** Once per launch: when the folder has grown too big, the pictures not looked at for longest go. */
    private fun trim(context: Context) {
        runCatching {
            val files = folder(context).listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (file in files) {
                if (total <= DISK_LIMIT) break
                total -= file.length()
                file.delete()
            }
        }
    }
}

private val tilePalette = listOf(
    Color(0xFF312E81) to Color(0xFF6D28D9),
    Color(0xFF0C4A6E) to Color(0xFF4338CA),
    Color(0xFF831843) to Color(0xFF6D28D9),
    Color(0xFF134E4A) to Color(0xFF1D4ED8),
    Color(0xFF7C2D12) to Color(0xFF9D174D),
    Color(0xFF3B0764) to Color(0xFF0E7490),
)

/**
 * A picture from the web inside a rounded tile. Logos are shown whole on a dark tile ([crop] off);
 * posters fill the tile. Until it arrives - or if it never does - a coloured tile with [fallback].
 */
@Composable
fun WebPicture(
    url: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    size: Int = RemoteImages.LOGO,
    crop: Boolean = false,
    seed: String = url,
    inset: Dp = 6.dp,
    fallback: ImageVector = Icons.Rounded.LiveTv,
) {
    val context = LocalContext.current
    var bitmap by remember(url, size) { mutableStateOf(if (url.isEmpty()) null else RemoteImages.cached(url, size)) }
    LaunchedEffect(url, size) {
        if (bitmap == null && url.isNotEmpty()) bitmap = RemoteImages.load(context, url, size)
    }
    val image = bitmap
    val colors = tilePalette[abs(seed.hashCode() % tilePalette.size)]
    Box(
        modifier
            .clip(shape)
            .then(if (image != null && !crop) Modifier.background(Surface3) else Modifier.background(Brush.linearGradient(listOf(colors.first, colors.second)))),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (crop) Modifier else Modifier.padding(inset)),
            )
        } else {
            Icon(fallback, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.fillMaxSize(0.42f))
        }
    }
}
