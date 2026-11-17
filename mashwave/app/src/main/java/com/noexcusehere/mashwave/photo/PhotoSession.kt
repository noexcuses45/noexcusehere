package com.noexcusehere.mashwave.photo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.noexcusehere.mashwave.APP_NAME
import com.noexcusehere.mashwave.editor.EditProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** Where an edited photo was written, and its size. */
data class SavedPhoto(val uri: String, val width: Int, val height: Int)

/**
 * One photo open in the photo editor: the picture as it stands, the steps that led to it (so
 * they can be undone), and saving the result for the timeline. The original file is only ever
 * read; the edited picture is written to the app's own storage.
 */
class PhotoSession(private val context: Context) {
    /** The picture with everything applied so far. */
    var picture by mutableStateOf<Bitmap?>(null)
        private set

    /** The picture as it was opened, for "hold to compare". */
    var before: Bitmap? = null
        private set

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** True while there is something to save. */
    val changed: Boolean
        get() = canUndo

    // Earlier steps are kept squeezed (as high-quality JPEG) so twenty of them fit in memory.
    private val undoStack = ArrayDeque<ByteArray>()
    private val redoStack = ArrayDeque<ByteArray>()

    /**
     * Opens the photo at [address], turned and mirrored the way the timeline shows it
     * ([rotation] in degrees clockwise). Returns false when it cannot be read.
     */
    suspend fun open(address: String, rotation: Int, mirrored: Boolean): Boolean {
        val loaded = withContext(Dispatchers.Default) {
            runCatching { PhotoFiles.read(context, address, rotation, mirrored) }
                .onFailure { Log.w("UltimateBeats", "Could not open the photo for editing", it) }
                .getOrNull()
        } ?: return false
        before = loaded
        undoStack.clear()
        redoStack.clear()
        picture = loaded
        flags()
        return true
    }

    /** Makes [next] the picture; the one before can be brought back with [undo]. */
    suspend fun commit(next: Bitmap) {
        val old = picture ?: return
        val packed = withContext(Dispatchers.Default) { pack(old) }
        undoStack.addLast(packed)
        while (undoStack.size > STEPS) undoStack.removeFirst()
        redoStack.clear()
        picture = next
        flags()
    }

    suspend fun undo() {
        val current = picture ?: return
        val packed = undoStack.removeLastOrNull() ?: return
        val restored = withContext(Dispatchers.Default) { unpack(packed) to pack(current) }
        redoStack.addLast(restored.second)
        picture = restored.first ?: current
        flags()
    }

    suspend fun redo() {
        val current = picture ?: return
        val packed = redoStack.removeLastOrNull() ?: return
        val restored = withContext(Dispatchers.Default) { unpack(packed) to pack(current) }
        undoStack.addLast(restored.second)
        picture = restored.first ?: current
        flags()
    }

    private fun flags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun pack(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(bitmap.width * bitmap.height / 4)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 97, out)
        return out.toByteArray()
    }

    private fun unpack(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    companion object {
        /** How many steps back "undo" can go. */
        const val STEPS = 20
    }
}

/** Reading photos for the photo editor and keeping the edited copies. */
object PhotoFiles {
    /**
     * Longest side of the picture being worked on. Plenty for a photo inside a video, and small
     * enough that every tool answers quickly and the phone's memory is not strained.
     */
    const val MAX_SIDE = 2048

    private fun folder(context: Context) = File(context.filesDir, "editor/photos")

    /** Reads the photo at [address] upright, turned [rotation] degrees clockwise and [mirrored] as asked, no bigger than [MAX_SIDE]. */
    fun read(context: Context, address: String, rotation: Int, mirrored: Boolean): Bitmap? {
        val uri = Uri.parse(address)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Measuring never hands back a picture; whether it worked shows in the size it found.
        val measuring = context.contentResolver.openInputStream(uri) ?: return null
        measuring.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Read at a half, a quarter... of full size while that still leaves more than is needed.
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        // Phone cameras often store the picture sideways with a note saying how to hold it.
        val note = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
        }.getOrNull() ?: 1
        val matrix = Matrix()
        when (note) {
            2 -> matrix.postScale(-1f, 1f)
            3 -> matrix.postRotate(180f)
            4 -> matrix.postScale(1f, -1f)
            5 -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            6 -> matrix.postRotate(90f)
            7 -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            8 -> matrix.postRotate(-90f)
        }
        // Then what the timeline did to it: mirrored first, then turned.
        if (mirrored) matrix.postScale(-1f, 1f)
        if (rotation % 360 != 0) matrix.postRotate(rotation.toFloat())

        val frame = RectF(0f, 0f, raw.width.toFloat(), raw.height.toFloat())
        matrix.mapRect(frame)
        val scale = minOf(1f, MAX_SIDE / max(frame.width(), frame.height()))
        matrix.postTranslate(-frame.left, -frame.top)
        matrix.postScale(scale, scale)
        val width = (frame.width() * scale).roundToInt().coerceAtLeast(1)
        val height = (frame.height() * scale).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // See-through parts of a picture show as black in a video, so they are black here too.
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(raw, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        if (raw !== out) raw.recycle()
        return out
    }

    fun write(context: Context, clipId: String, bitmap: Bitmap): SavedPhoto {
        val dir = folder(context).apply { mkdirs() }
        val file = File(dir, "${clipId.filter { it.isLetterOrDigit() }}-${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (file.length() == 0L) throw IllegalStateException("Nothing was written")
        return SavedPhoto(Uri.fromFile(file).toString(), bitmap.width, bitmap.height)
    }

    /**
     * Saves [bitmap] as a new picture in the phone's gallery, in a folder named after the app,
     * and returns where it went in plain words. [name] is the name of the photo it was made from.
     */
    fun publish(context: Context, bitmap: Bitmap, name: String): String {
        val clean = name.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().ifBlank { "Photo" }
        val title = "$clean edited"
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$title.jpg")
                put(MediaStore.Images.Media.TITLE, title)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + APP_NAME)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val item = resolver.insert(collection, values) ?: throw IOException("could not create the picture")
            try {
                resolver.openOutputStream(item)?.use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw IOException("could not write the picture")
                } ?: throw IOException("could not open the picture")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(item, values, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(item, null, null) }
                throw e
            }
            return "Pictures › $APP_NAME"
        }
        // Older Android: keep it in the app's own pictures folder (no extra permission needed).
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, APP_NAME)
        dir.mkdirs()
        var target = File(dir, "$title.jpg")
        var n = 2
        while (target.exists()) target = File(dir, "$title ($n).jpg").also { n++ }
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (target.length() == 0L) throw IOException("nothing was written")
        return "the app's Pictures folder"
    }

    /**
     * Clears out edited photos no project uses any more (the project was deleted, or the photo
     * was edited again). Recent files are left alone: "undo" in an open project may still want them.
     */
    fun sweep(context: Context, projects: List<EditProject>) {
        val files = folder(context).listFiles() ?: return
        val used = projects.flatMap { project -> project.clips.flatMap { listOf(it.uri, it.original) } }.toSet()
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        for (file in files) {
            if (file.lastModified() < cutoff && Uri.fromFile(file).toString() !in used) file.delete()
        }
    }
}

// ---- small helpers shared by the tools ---------------------------------------------------------

/** Every pixel of the picture, row by row. */
fun Bitmap.pixels(): IntArray {
    val out = IntArray(width * height)
    getPixels(out, 0, width, 0, 0, width, height)
    return out
}

fun bitmapOf(pixels: IntArray, width: Int, height: Int): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

/** A copy no longer than [side] on its longest side (the picture itself when it is already small enough). */
fun Bitmap.fitWithin(side: Int): Bitmap {
    val longest = max(width, height)
    if (longest <= side) return this
    val scale = side.toFloat() / longest
    return Bitmap.createScaledBitmap(this, (width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1), true)
}
