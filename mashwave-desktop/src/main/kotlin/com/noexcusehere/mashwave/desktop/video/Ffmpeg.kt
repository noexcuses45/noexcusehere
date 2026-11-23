package com.noexcusehere.mashwave.desktop.video

import com.noexcusehere.mashwave.desktop.ui.bundled
import com.noexcusehere.mashwave.editor.EditProject
import com.noexcusehere.mashwave.editor.EditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.FontVariation
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.Shadow
import org.jetbrains.skia.paragraph.TextStyle
import org.jetbrains.skia.paragraph.TypefaceFontProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** What a video, a song or a picture file turned out to hold. [width] and [height] are as it is shown, upright. */
data class MediaFacts(val durationMs: Long, val width: Int, val height: Int, val hasPicture: Boolean, val hasSound: Boolean)

/**
 * The video engine (FFmpeg): a separate program the app starts to read, cut, join and write
 * video. On Windows and Mac it comes packed with the app; elsewhere one installed on the
 * computer is used.
 */
class Ffmpeg(private val program: File, private val prober: File?, private val folder: File?, val packed: Boolean) {
    val location: String get() = program.absolutePath

    private fun start(tool: File, args: List<String>): Process {
        val builder = ProcessBuilder(listOf(tool.absolutePath) + args)
        if (folder != null) {
            // The engine's own parts sit beside it; Windows looks there by itself, the others are told.
            val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            val key = when {
                os.contains("mac") -> "DYLD_LIBRARY_PATH"
                os.contains("win") -> null
                else -> "LD_LIBRARY_PATH"
            }
            if (key != null) {
                val environment = builder.environment()
                environment[key] = folder.absolutePath + (environment[key]?.takeIf { it.isNotBlank() }?.let { File.pathSeparator + it } ?: "")
            }
        }
        return builder.start()
    }

    /** Collects what a stream says on a thread of its own, so the program never waits on a full pipe. */
    private class Listener(private val stream: java.io.InputStream, private val most: Int) {
        private val kept = ByteArrayOutputStream()
        private val thread = Thread {
            runCatching {
                val chunk = ByteArray(1 shl 14)
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    synchronized(kept) { if (kept.size() < most) kept.write(chunk, 0, n) }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        fun bytes(): ByteArray {
            thread.join(4000)
            return synchronized(kept) { kept.toByteArray() }
        }
    }

    /** Runs [tool] to the end. Returns how it ended, what it wrote, and what it said about problems. */
    private fun run(tool: File, args: List<String>, seconds: Long, most: Int = 64 shl 20): Triple<Int, ByteArray, String>? = runCatching {
        val process = start(tool, args)
        try {
            process.outputStream.close()
            val out = Listener(process.inputStream, most)
            val said = Listener(process.errorStream, 1 shl 16)
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) return@runCatching null
            Triple(process.exitValue(), out.bytes(), said.bytes().toString(Charsets.UTF_8))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }.getOrNull()

    /** The names of the ways this engine can pack pictures and sound. */
    val encoders: Set<String> by lazy {
        val text = run(program, listOf("-hide_banner", "-encoders"), 20)?.second?.toString(Charsets.UTF_8).orEmpty()
        text.lineSequence().map { it.trim().split(Regex("\\s+")) }.filter { it.size >= 2 && it[0].length == 6 && it[0][0] in "VAS" }.map { it[1] }.toSet()
    }

    /** The version line, for the settings page and the self-check. */
    val version: String by lazy {
        run(program, listOf("-hide_banner", "-version"), 20)?.second?.toString(Charsets.UTF_8).orEmpty().lineSequence().firstOrNull().orEmpty().take(80)
    }

    /**
     * Ways of packing the picture, best first. Everything plays an "H.264" video; the last one
     * is an older kind that every engine has, for when nothing better works.
     */
    fun videoEncoders(): List<String> {
        val mac = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("mac")
        val wanted = if (mac) listOf("h264_videotoolbox", "libx264", "libopenh264", "mpeg4") else listOf("libx264", "libopenh264", "h264_mf", "mpeg4")
        return wanted.filter { it in encoders }.ifEmpty { listOf("mpeg4") }
    }

    /** Looks inside [file]. Null when it is not something the engine can read. */
    fun facts(file: File): MediaFacts? {
        if (!file.isFile) return null
        if (prober != null) {
            val answer = run(prober, listOf("-v", "error", "-print_format", "json", "-show_format", "-show_streams", file.absolutePath), 30)
            if (answer != null && answer.first == 0) {
                runCatching { return read(Json.parseToJsonElement(answer.second.toString(Charsets.UTF_8)).jsonObject) }
            }
        }
        // No prober: the engine itself says the same in words when asked to open the file.
        val said = run(program, listOf("-hide_banner", "-i", file.absolutePath), 30)?.third ?: return null
        val length = Regex("Duration: (\\d+):(\\d+):(\\d+)\\.(\\d+)").find(said)?.destructured?.let { (h, m, s, part) ->
            (h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + (part.padEnd(3, '0').take(3).toLongOrNull() ?: 0)
        } ?: 0
        val size = Regex("Video: [^\\n]*?(\\d{2,5})x(\\d{2,5})").find(said)
        val sound = said.contains(Regex("Stream #[^\\n]*Audio:"))
        if (size == null && !sound) return null
        return MediaFacts(length, size?.groupValues?.get(1)?.toIntOrNull() ?: 0, size?.groupValues?.get(2)?.toIntOrNull() ?: 0, size != null, sound)
    }

    private fun read(found: JsonObject): MediaFacts? {
        fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
        val streams = found["streams"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        // A song's cover picture is listed like a video; it is not one.
        val picture = streams.firstOrNull { it.text("codec_type") == "video" && it["disposition"]?.jsonObject?.get("attached_pic")?.jsonPrimitive?.intOrNull != 1 }
        val sound = streams.any { it.text("codec_type") == "audio" }
        if (picture == null && !sound) return null
        val seconds = found["format"]?.jsonObject?.text("duration")?.toDoubleOrNull()
            ?: streams.mapNotNull { it.text("duration")?.toDoubleOrNull() }.maxOrNull() ?: 0.0
        var w = picture?.get("width")?.jsonPrimitive?.intOrNull ?: 0
        var h = picture?.get("height")?.jsonPrimitive?.intOrNull ?: 0
        // Phones store video lying down with a note to turn it; the engine turns it when reading, so the size is given turned too.
        val turn = picture?.get("side_data_list")?.jsonArray?.firstNotNullOfOrNull { it.jsonObject["rotation"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() }
            ?: picture?.get("tags")?.jsonObject?.text("rotate")?.toDoubleOrNull() ?: 0.0
        if (abs(turn.roundToInt()) % 180 == 90) w = h.also { h = w }
        return MediaFacts((seconds * 1000).toLong().coerceAtLeast(0), w, h, picture != null, sound)
    }

    /** Runs the engine for one picture and hands back the picture file's bytes; null when it did not work. */
    suspend fun picture(args: List<String>): ByteArray? = withContext(Dispatchers.IO) {
        val answer = run(program, args, 40) ?: return@withContext null
        if (answer.first != 0 || answer.second.isEmpty()) {
            System.err.println("the video engine could not make a picture: ${answer.third.trim().takeLast(400)}")
            null
        } else {
            answer.second
        }
    }

    /**
     * Makes a video. [onProgress] hears how far along it is, 0 to 1. Returns null when the video
     * was made, otherwise what the engine said went wrong. Stopping the caller stops the engine.
     */
    suspend fun render(job: RenderJob, onProgress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        val process = runCatching { start(program, job.args) }.getOrElse { return@withContext "the video engine could not be started: ${it.message}" }
        try {
            process.outputStream.close()
            val said = Listener(process.errorStream, 1 shl 16)
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    ensureActive()
                    // "out_time_us" and, in older engines, "out_time_ms" both count millionths of a second.
                    val at = line.substringAfter('=', "").trim().toLongOrNull() ?: continue
                    if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
                        if (job.totalMs > 0) onProgress((at / 1000f / job.totalMs).coerceIn(0f, 1f))
                    }
                }
            }
            ensureActive()
            val ended = process.waitFor()
            if (ended == 0) null else said.bytes().toString(Charsets.UTF_8).trim().takeLast(600).ifEmpty { "the video engine stopped with code $ended" }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    companion object {
        private fun named(base: String): String = if (System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win")) "$base.exe" else base

        private fun inFolder(dir: File, packed: Boolean): Ffmpeg? {
            val program = File(dir, named("ffmpeg")).takeIf { it.isFile } ?: return null
            val prober = File(dir, named("ffprobe")).takeIf { it.isFile }
            if (packed) {
                // A file copied out of a package can lose its "may be run" mark on the way.
                runCatching { program.setExecutable(true) }
                runCatching { prober?.setExecutable(true) }
            }
            return Ffmpeg(program, prober, if (packed) dir else null, packed)
        }

        /** The engine packed with the app when there is one, otherwise one installed on the computer; null when there is none. */
        fun find(): Ffmpeg? {
            System.getenv("UMM_FFMPEG")?.takeIf { it.isNotBlank() }?.let { given -> inFolder(File(given), false)?.let { return it } }
            System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }?.let { inFolder(File(it, "ffmpeg"), true) }?.let { return it }
            val places = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() } + listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
            for (place in places) inFolder(File(place), false)?.let { return it }
            return null
        }
    }
}

/** Captions drawn as small see-through pictures, for the engine to lay over the video. */
object TextPictures {
    private val letters: FontCollection by lazy {
        val collection = FontCollection()
        collection.setDefaultFontManager(FontMgr.default)
        // The app's own lettering in a bold weight, so captions read clearly over moving pictures.
        runCatching {
            val bytes = bundled("font/outfit.ttf")
            if (bytes != null) {
                val face = FontMgr.default.makeFromData(Data.makeFromBytes(bytes))?.makeClone(FontVariation("wght", 700f))
                if (face != null) collection.setAssetFontManager(TypefaceFontProvider().registerTypeface(face, "Caption"))
            }
        }
        // Smileys and letters the app's own lettering lacks come from whatever the computer has.
        collection.setEnableFallback(true)
        collection
    }

    /**
     * Draws the captions of [project] that pass [only] for a frame [w] by [h], as picture files
     * in [folder] (one drawn before is used again). Returns each with where its corner goes.
     */
    fun make(project: EditProject, w: Int, h: Int, folder: File, only: (EditText) -> Boolean = { true }): List<TextPicture> {
        folder.mkdirs()
        return project.texts.filter { it.text.isNotBlank() && it.endMs > it.startMs && only(it) }.mapNotNull { item ->
            runCatching { one(item, w, h, folder) }.onFailure { System.err.println("a caption could not be drawn: $it") }.getOrNull()
        }
    }

    private fun one(item: EditText, w: Int, h: Int, folder: File): TextPicture? {
        val size = (h * item.size.coerceIn(0.02f, 0.4f)).coerceAtLeast(8f)
        val style = TextStyle()
            .setColor(item.color.toInt())
            .setFontSize(size)
            .setFontFamilies(arrayOf("Caption"))
            .setFontStyle(FontStyle.BOLD)
            .addShadow(Shadow(0xCC000000.toInt(), 0f, size * 0.04f, (size * 0.12f / 2f).toDouble()))
        val paragraph = ParagraphBuilder(ParagraphStyle().apply { alignment = Alignment.CENTER }, letters).pushStyle(style).addText(item.text).build()
        val room = (w * 0.92f).coerceAtLeast(16f)
        paragraph.layout(room)
        val pad = ceil(size * 0.3f).toInt()
        val widest = ceil(paragraph.longestLine).toInt().coerceAtLeast(1)
        val width = (widest + 2 * pad).coerceIn(2, w.coerceAtLeast(2))
        val height = (ceil(paragraph.height).toInt() + 2 * pad).coerceAtLeast(2)

        val key = MessageDigest.getInstance("SHA-1").digest("${item.text}|${item.color}|$size|$w|$h".toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(folder, "text-$key.png")
        if (!file.isFile || file.length() == 0L) {
            val sheet = Bitmap()
            check(sheet.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL))) { "no room for the caption" }
            sheet.erase(0)
            Canvas(sheet).use { canvas -> paragraph.paint(canvas, (width - room) / 2f, pad.toFloat()) }
            val image = Image.makeFromBitmap(sheet)
            val data = image.encodeToData(EncodedImageFormat.PNG, 100) ?: return null
            file.writeBytes(data.bytes)
            data.close()
            image.close()
            sheet.close()
        }
        // The caption's middle sits at (x, y), each from -1 (left, bottom) to +1 (right, top).
        val middleX = (item.x.coerceIn(-1f, 1f) + 1f) / 2f * w
        val middleY = (1f - (item.y.coerceIn(-1f, 1f) + 1f) / 2f) * h
        return TextPicture(file.absolutePath, (middleX - width / 2f).roundToInt(), (middleY - height / 2f).roundToInt(), item.startMs, item.endMs)
    }
}
