package com.noexcusehere.mashwave.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.noexcusehere.mashwave.desktop.Look
import com.noexcusehere.mashwave.desktop.vlc.VlcPlayer
import com.noexcusehere.mashwave.iptv.TvNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Channel logos and posters from the web: fetched once, kept small, remembered on disk. */
object Pictures {
    private const val KEEP_IN_MEMORY = 500
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val KEEP_ON_DISK = 4000

    private val memory = object : LinkedHashMap<String, ImageBitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > KEEP_IN_MEMORY
    }
    private val hopeless = ConcurrentHashMap.newKeySet<String>()
    private val gate = Semaphore(6)

    @Volatile
    private var folder: File? = null

    /** Tells the loader where it may keep copies, and clears out the oldest when there are too many. */
    fun keepIn(dir: File) {
        folder = dir.apply { mkdirs() }
        Thread {
            runCatching {
                val files = dir.listFiles() ?: return@runCatching
                if (files.size > KEEP_ON_DISK) files.sortedBy { it.lastModified() }.take(files.size - KEEP_ON_DISK * 3 / 4).forEach { it.delete() }
            }
        }.apply { isDaemon = true }.start()
    }

    fun ready(url: String, side: Int): ImageBitmap? = synchronized(memory) { memory["$side|$url"] }

    suspend fun load(url: String, side: Int): ImageBitmap? {
        if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) return null
        val key = "$side|$url"
        synchronized(memory) { memory[key] }?.let { return it }
        if (url in hopeless) return null
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                synchronized(memory) { memory[key] }?.let { return@withContext it }
                val bytes = runCatching { fromDisk(url) ?: fetch(url)?.also { toDisk(url, it) } }.getOrNull()
                val picture = bytes?.let { runCatching { shrink(it, side) }.getOrNull() }
                if (picture == null) hopeless += url else synchronized(memory) { memory[key] = picture }
                picture
            }
        }
    }

    private fun fileFor(url: String): File? {
        val dir = folder ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) })
    }

    private fun fromDisk(url: String): ByteArray? = fileFor(url)?.takeIf { it.isFile && it.length() > 0 }?.readBytes()

    private fun toDisk(url: String, bytes: ByteArray) {
        if (bytes.size > 1024 * 1024) return
        runCatching { fileFor(url)?.writeBytes(bytes) }
    }

    private fun fetch(url: String, hops: Int = 0): ByteArray? {
        val connection = URI(url).toURL().openConnection() as? HttpURLConnection ?: return null
        try {
            connection.connectTimeout = 6000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", TvNet.userAgent)
            connection.setRequestProperty("Accept", "image/*,*/*;q=0.5")
            val code = connection.responseCode
            if (code in 300..399 && hops < 4) {
                // Java does not follow a hop from http to https (or back) by itself.
                val next = connection.getHeaderField("Location") ?: return null
                return fetch(URI(url).resolve(next).toString(), hops + 1)
            }
            if (code != 200) return null
            if (connection.contentLengthLong > MAX_BYTES) return null
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    out.write(chunk, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
                out.toByteArray()
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** Decodes the picture and makes it no bigger than [side] on its longest edge. */
    private fun shrink(bytes: ByteArray, side: Int): ImageBitmap? {
        val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
        try {
            val longest = max(image.width, image.height)
            if (longest <= 0) return null
            if (longest <= side) return image.toComposeImageBitmap()
            val scale = side.toFloat() / longest
            val w = (image.width * scale).roundToInt().coerceAtLeast(1)
            val h = (image.height * scale).roundToInt().coerceAtLeast(1)
            val surface = Surface.makeRasterN32Premul(w, h)
            try {
                surface.canvas.drawImageRect(
                    image,
                    Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                    Rect.makeWH(w.toFloat(), h.toFloat()),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
                    null,
                    true,
                )
                val small = surface.makeImageSnapshot()
                try {
                    return small.toComposeImageBitmap()
                } finally {
                    small.close()
                }
            } finally {
                surface.close()
            }
        } finally {
            image.close()
        }
    }
}

/** A picture from the web. Until it arrives (or when there is none) [fallback] is shown. */
@Composable
fun Picture(
    url: String,
    modifier: Modifier = Modifier,
    side: Int = 360,
    scale: ContentScale = ContentScale.Crop,
    fallback: @Composable () -> Unit = {},
) {
    val picture by produceState(Pictures.ready(url, side), url, side) {
        if (value == null) value = Pictures.load(url, side)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val shown = picture
        if (shown == null) fallback() else Image(shown, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = scale)
    }
}

private val smooth = FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE)

/** The video itself: whatever picture the player made last, fitted into the space as [look] says. */
@Composable
fun VideoPicture(player: VlcPlayer, look: Look, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color.Black)) {
        // Reading this here makes the canvas draw again with every new picture.
        player.frameTick
        val wanted = when (look) {
            Look.WIDE -> 16f / 9f
            Look.CLASSIC -> 4f / 3f
            else -> player.shape
        }
        player.frames.use { image ->
            if (image == null || image.width <= 0 || image.height <= 0) return@use
            val shape = if (wanted > 0f) wanted else image.width.toFloat() / image.height
            val fit = if (look == Look.FILL) max(size.width / shape, size.height) else min(size.width / shape, size.height)
            val w = fit * shape
            val h = fit
            val left = (size.width - w) / 2f
            val top = (size.height - h) / 2f
            clipRect {
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawImageRect(
                        image,
                        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                        Rect.makeXYWH(left, top, w, h),
                        smooth,
                        null,
                        true,
                    )
                }
            }
        }
    }
}

/** A still picture that ships with the app, e.g. the logo. */
val AppLogo: ImageBitmap? by lazy {
    bundled("icon.png")?.let { bytes -> runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() }
}
