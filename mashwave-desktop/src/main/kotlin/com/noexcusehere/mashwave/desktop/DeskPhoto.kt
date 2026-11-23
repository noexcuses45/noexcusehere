package com.noexcusehere.mashwave.desktop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.noexcusehere.mashwave.photo.BrushTool
import com.noexcusehere.mashwave.photo.CropTool
import com.noexcusehere.mashwave.photo.DrawTool
import com.noexcusehere.mashwave.photo.FocusTool
import com.noexcusehere.mashwave.photo.HealTool
import com.noexcusehere.mashwave.photo.PhotoOps
import com.noexcusehere.mashwave.photo.PhotoTool
import com.noexcusehere.mashwave.photo.ReshapeTool
import com.noexcusehere.mashwave.photo.ToneTool
import com.noexcusehere.mashwave.photo.bitmapOf
import com.noexcusehere.mashwave.photo.pixels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.ImageInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import org.jetbrains.skia.Bitmap as SkBitmap

/**
 * One photo open in the photo editor: the picture as it stands, the steps that led to it (so
 * they can be undone), and nothing else. The file the photo came from is only ever read.
 */
class PhotoWork {
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

    /** Opens the photo in [file]. Returns false when it cannot be read. */
    suspend fun open(file: File): Boolean {
        val loaded = withContext(Dispatchers.Default) {
            runCatching { PhotoFiles.read(file) }.onFailure { System.err.println("could not open ${file.name} for editing: $it") }.getOrNull()
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
        picture = next.freeze()
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

    private fun unpack(bytes: ByteArray): Bitmap? = runCatching { PhotoFiles.decode(bytes, Int.MAX_VALUE) }.getOrNull()

    companion object {
        /** How many steps back "undo" can go. */
        const val STEPS = 20
    }
}

/** The photo editor's tools, in the order they are laid out. */
enum class PhotoKind(val label: String, val hint: String, val brushDp: Float = 0f) {
    CROP("Crop", "Drag a corner or an edge. Drag inside to move the frame."),
    AUTO("Auto", ""),
    ADJUST("Adjust", "Slide a setting to change the whole photo."),
    LOOKS("Looks", "Pick a look, then set how strong it is."),
    SMOOTH("Smooth", "Paint over skin to even it out. Eyes, lips and hair stay crisp.", 30f),
    HEAL("Heal", "Click or paint over a spot and it is gone.", 14f),
    WHITEN("Whiten", "Paint over teeth or the whites of eyes.", 14f),
    DETAILS("Details", "Paint over eyes, hair or anything that should be crisper.", 22f),
    LIGHTEN("Lighten", "Paint where the photo should be brighter.", 34f),
    RESHAPE("Reshape", "Push gently with the mouse to nudge a shape.", 48f),
    BLUR("Blur", "Drag the circle onto what should stay sharp."),
    DRAW("Draw", "Hold the mouse button down and draw.", 5f),
}

/** How far the picture is zoomed in and where it has been moved to. */
class PhotoView {
    var zoom by mutableFloatStateOf(1f)
    var shiftX by mutableFloatStateOf(0f)
    var shiftY by mutableFloatStateOf(0f)

    /** The space the picture is shown in, as it was last drawn. */
    var area = Size.Zero

    fun reset() {
        zoom = 1f
        shiftX = 0f
        shiftY = 0f
    }

    /** Screen pixels per picture pixel when a picture [w] by [h] is shown in [area] with [margin] around it. */
    fun scale(area: Size, w: Int, h: Int, margin: Float): Float = PhotoOps.fitScale(area.width, area.height, w, h, margin) * zoom

    fun left(area: Size, w: Int, h: Int, margin: Float): Float = area.width / 2f + shiftX - w * scale(area, w, h, margin) / 2f

    fun top(area: Size, w: Int, h: Int, margin: Float): Float = area.height / 2f + shiftY - h * scale(area, w, h, margin) / 2f

    /** Whatever was under [from] follows it by [pan], zoomed by [zoomBy]. */
    fun gesture(area: Size, w: Int, h: Int, margin: Float, from: Offset, pan: Offset, zoomBy: Float) {
        if (area.width <= 0f || area.height <= 0f) return
        val next = PhotoOps.viewAfter(floatArrayOf(zoom, shiftX, shiftY), area.width, area.height, w, h, margin, from.x, from.y, pan.x, pan.y, zoomBy)
        zoom = next[0]
        shiftX = next[1]
        shiftY = next[2]
    }
}

/**
 * The photo editor while it is open on one photo: the picture, the tool in use, and what the
 * buttons and the keys do. The screen only shows this and passes clicks on.
 */
class PhotoEditing(private val desk: Desk, val file: File, private val scope: CoroutineScope) {
    val session = PhotoWork()
    val view = PhotoView()

    /** Screen pixels to a "dp", and the gap kept around the picture: told by the screen that shows it. */
    var density = 1f
    var margin = 18f

    /** Where "Save a copy" puts the picture; the app's folder inside the pictures folder unless set. */
    var saveInto: File? = null

    var tool by mutableStateOf<PhotoTool?>(null)
        private set
    var kind by mutableStateOf<PhotoKind?>(null)
        private set

    /** True while the photo is being opened, a change worked in, or the copy saved. */
    var busy by mutableStateOf(true)
        private set

    /** True while "leave without saving?" is being asked. */
    var leaving by mutableStateOf(false)

    /** True while the brush-size slider is held, so the ring shows in the middle of the picture. */
    var sizing by mutableStateOf(false)

    private val brushes = mutableStateMapOf<PhotoKind, Float>()

    init {
        scope.launch {
            if (!session.open(file)) {
                desk.say("That picture couldn't be opened")
                desk.closePhoto()
            }
            busy = false
        }
    }

    fun brushOf(which: PhotoKind): Float = brushes[which] ?: which.brushDp

    /** Sets the brush of [which] to [dp] across, for the open tool too. */
    fun brushTo(which: PhotoKind, dp: Float) {
        brushes[which] = dp
        if (kind == which) tool?.size = dp * density
    }

    fun closeTool() {
        if (busy) return
        tool?.close()
        tool = null
        kind = null
        sizing = false
    }

    fun open(which: PhotoKind) {
        val picture = session.picture ?: return
        if (busy || tool != null) return
        if (which == PhotoKind.AUTO) {
            busy = true
            scope.launch {
                val out = withContext(Dispatchers.Default) {
                    runCatching { bitmapOf(PhotoOps.auto(picture.pixels(), picture.width, picture.height), picture.width, picture.height) }.getOrNull()
                }
                if (out != null) session.commit(out) else desk.say(NO_MEMORY)
                busy = false
            }
            return
        }
        val made = runCatching {
            when (which) {
                PhotoKind.CROP -> CropTool(picture, density)
                PhotoKind.ADJUST, PhotoKind.LOOKS -> ToneTool(picture, scope)
                PhotoKind.SMOOTH -> BrushTool(picture, scope) { pixels, w, h -> PhotoOps.smooth(pixels, w, h) }
                PhotoKind.WHITEN -> BrushTool(picture, scope) { pixels, _, _ -> PhotoOps.whiten(pixels) }
                PhotoKind.DETAILS -> BrushTool(picture, scope) { pixels, w, h -> PhotoOps.sharpen(pixels, w, h, 1.3f, max(1, minOf(w, h) / 500)) }
                PhotoKind.LIGHTEN -> BrushTool(picture, scope) { pixels, w, h -> PhotoOps.adjust(pixels, w, h, PhotoOps.Adjust(brightness = 0.28f, shadows = 0.3f)) }
                PhotoKind.HEAL -> HealTool(picture, scope)
                PhotoKind.RESHAPE -> ReshapeTool(picture)
                PhotoKind.BLUR -> FocusTool(picture, scope)
                PhotoKind.DRAW -> DrawTool(picture)
            }
        }.getOrNull()
        if (made == null) {
            desk.say(NO_MEMORY)
            return
        }
        made.size = brushOf(which) * density
        if (which == PhotoKind.CROP) view.reset()
        tool = made
        kind = which
    }

    /** Keeps what the open tool did. */
    fun apply() {
        val open = tool ?: return
        if (busy || open.working) return
        if (!open.dirty) {
            closeTool()
            return
        }
        busy = true
        scope.launch {
            val out = withContext(Dispatchers.Default) { runCatching { open.result() }.getOrNull() }
            if (out != null) session.commit(out) else desk.say(NO_MEMORY)
            if (kind == PhotoKind.CROP) view.reset()
            busy = false
            closeTool()
        }
    }

    /** "Save a copy": writes the finished picture as a new file and closes the editor. */
    fun done() {
        if (busy || tool != null) return
        if (!session.changed) {
            desk.closePhoto()
            return
        }
        val finished = session.picture ?: return
        busy = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { PhotoFiles.save(finished, file.name, saveInto ?: PhotoFiles.savedFolder()) }.onFailure { System.err.println("could not save the photo: $it") }.getOrNull()
            }
            busy = false
            if (saved != null) desk.closePhoto(saved) else desk.say("The photo couldn't be saved. Is the disk full?")
        }
    }

    /** Closing: asks first when there are changes that would be lost. */
    fun leave() {
        when {
            busy -> Unit
            session.changed -> leaving = true
            else -> desk.closePhoto()
        }
    }

    fun leaveNow() {
        leaving = false
        desk.closePhoto()
    }

    /** Undo: the last stroke of a tool that keeps its own, otherwise the last change to the photo. */
    fun stepBack() {
        if (busy) return
        when (val open = tool) {
            null -> scope.launch { session.undo().also { view.reset() } }
            is HealTool -> open.undo()
            is ReshapeTool -> open.undo()
            is DrawTool -> open.undo()
            else -> Unit
        }
    }

    fun stepForward() {
        if (busy || tool != null) return
        scope.launch { session.redo().also { view.reset() } }
    }

    fun zoomBy(times: Float) {
        val picture = session.picture ?: return
        val w = tool?.width ?: picture.width
        val h = tool?.height ?: picture.height
        view.gesture(view.area, w, h, margin, Offset(view.area.width / 2f, view.area.height / 2f), Offset.Zero, times)
    }

    /** The keys: Ctrl+Z back, Ctrl+Y forward, Enter keeps a change, Esc drops it, Ctrl+S saves, + - 0 zoom. True when the key was used. */
    fun key(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val command = event.isCtrlPressed || event.isMetaPressed
        return when {
            leaving -> {
                if (event.key == Key.Escape) leaving = false
                event.key == Key.Escape
            }
            command && event.key == Key.Z && event.isShiftPressed || command && event.key == Key.Y -> {
                stepForward()
                true
            }
            command && event.key == Key.Z -> {
                stepBack()
                true
            }
            command && event.key == Key.S -> {
                done()
                true
            }
            event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                val open = tool != null
                if (open) apply()
                open
            }
            event.key == Key.Escape -> {
                if (tool != null) closeTool() else leave()
                true
            }
            event.key == Key.Equals || event.key == Key.Plus || event.key == Key.NumPadAdd -> {
                zoomBy(1.25f)
                true
            }
            event.key == Key.Minus || event.key == Key.NumPadSubtract -> {
                zoomBy(0.8f)
                true
            }
            event.key == Key.Zero || event.key == Key.NumPad0 -> {
                view.reset()
                true
            }
            else -> false
        }
    }

    /** The editor is being shut: stops whatever is still being worked out and lets go of the pictures. */
    fun close() {
        tool?.close()
        tool = null
        kind = null
        scope.cancel()
        // The pictures held here are large; let go of them now rather than some time later.
        System.gc()
    }

    companion object {
        const val NO_MEMORY = "That didn't work - the computer ran short of memory. Close other programs and try again."

        /** How small and how big the brush of [which] can be made, in "dp" across. */
        fun brushRange(which: PhotoKind): ClosedFloatingPointRange<Float> = if (which == PhotoKind.DRAW) 2f..30f else 6f..120f
    }
}

/** Reading photos for the photo editor and saving the edited copies. */
object PhotoFiles {
    /**
     * Longest side of the picture being worked on. A photo from a phone's camera fits whole;
     * anything bigger is brought down to this so every tool still answers quickly.
     */
    const val MAX_SIDE = 4096

    /** The kinds of picture that can be opened, by the ending of the name. */
    val kinds = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

    private const val MOST_BYTES = 80L * 1024 * 1024
    private const val MOST_DOTS = 64L * 1000 * 1000

    fun isPicture(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in kinds

    /** Reads the photo in [file] upright, no bigger than [maxSide]. Null when it is not a picture that can be read. */
    fun read(file: File, maxSide: Int = MAX_SIDE): Bitmap? {
        if (!file.isFile || file.length() !in 1..MOST_BYTES) return null
        return decode(file.readBytes(), maxSide)
    }

    /** The picture held in [bytes] (the contents of a JPEG, PNG... file), upright, no bigger than [maxSide]. */
    fun decode(bytes: ByteArray, maxSide: Int = MAX_SIDE): Bitmap? {
        val data = Data.makeFromBytes(bytes)
        try {
            val codec = runCatching { Codec.makeFromData(data) }.getOrNull() ?: return null
            try {
                if (codec.width <= 0 || codec.height <= 0 || codec.width.toLong() * codec.height > MOST_DOTS) return null
                val dots = SkBitmap()
                check(dots.allocPixels(ImageInfo(codec.width, codec.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL))) { "no room for the picture" }
                codec.readPixels(dots)
                val raw = Bitmap(dots, Bitmap.Config.ARGB_8888, false)

                // Cameras often store the picture sideways with a note saying how to hold it.
                val matrix = Matrix()
                when (codec.encodedOrigin) {
                    EncodedOrigin.TOP_RIGHT -> matrix.postScale(-1f, 1f)
                    EncodedOrigin.BOTTOM_RIGHT -> matrix.postRotate(180f)
                    EncodedOrigin.BOTTOM_LEFT -> matrix.postScale(1f, -1f)
                    EncodedOrigin.LEFT_TOP -> {
                        matrix.postRotate(90f)
                        matrix.postScale(-1f, 1f)
                    }
                    EncodedOrigin.RIGHT_TOP -> matrix.postRotate(90f)
                    EncodedOrigin.RIGHT_BOTTOM -> {
                        matrix.postRotate(-90f)
                        matrix.postScale(-1f, 1f)
                    }
                    EncodedOrigin.LEFT_BOTTOM -> matrix.postRotate(-90f)
                    else -> Unit
                }
                val frame = RectF(0f, 0f, raw.width.toFloat(), raw.height.toFloat())
                matrix.mapRect(frame)
                val scale = minOf(1f, maxSide / max(frame.width(), frame.height()))
                matrix.postTranslate(-frame.left, -frame.top)
                matrix.postScale(scale, scale)
                val width = (frame.width() * scale).roundToInt().coerceAtLeast(1)
                val height = (frame.height() * scale).roundToInt().coerceAtLeast(1)
                val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                // See-through parts of a picture are shown as black, as they are on the phone.
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(raw, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                raw.recycle()
                return out.freeze()
            } finally {
                codec.close()
            }
        } finally {
            data.close()
        }
    }

    /** True when the photo in [file] is stored lying down or mirrored, with a camera's note saying how to hold it. */
    fun isTurned(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() !in 1..MOST_BYTES) return false
        val data = Data.makeFromBytes(file.readBytes())
        try {
            val codec = Codec.makeFromData(data)
            try {
                codec.encodedOrigin != EncodedOrigin.TOP_LEFT && codec.encodedOrigin != EncodedOrigin.UNUSED
            } finally {
                codec.close()
            }
        } finally {
            data.close()
        }
    }.getOrDefault(false)

    /** Where pictures are usually kept on this computer. */
    fun picturesFolder(): File {
        val home = File(System.getProperty("user.home").orEmpty())
        return File(home, "Pictures").takeIf { it.isDirectory } ?: home
    }

    /** Where edited photos are saved: a folder named after the app, inside the pictures folder. */
    fun savedFolder(): File = File(picturesFolder(), AppFolders.NAME)

    /**
     * Saves [bitmap] as a new JPEG in [folder], named after the photo it was made from ([name]).
     * Nothing already there is replaced. Returns the new file.
     */
    fun save(bitmap: Bitmap, name: String, folder: File = savedFolder()): File {
        folder.mkdirs()
        val target = AppFolders.freeFile(folder, name.substringBeforeLast('.').ifBlank { "Photo" } + " edited", "jpg", "Photo edited")
        val temp = File(folder, target.name + ".part")
        try {
            temp.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw IOException("could not write the picture")
            }
            if (temp.length() == 0L) throw IOException("nothing was written")
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
        return target
    }
}

/** Small copies of photos for the grid: made once, remembered on disk, a few hundred kept ready. */
object PhotoThumbs {
    private const val SIDE = 256
    private const val KEEP_IN_MEMORY = 320
    private const val KEEP_ON_DISK = 6000

    private val memory = object : LinkedHashMap<String, ImageBitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > KEEP_IN_MEMORY
    }
    private val gate = Semaphore(3)

    @Volatile
    private var folder: File? = null

    /** Tells it where the small copies may be kept, and clears out the oldest when there are too many. */
    fun keepIn(dir: File) {
        folder = dir.apply { mkdirs() }
        Thread {
            runCatching {
                val files = dir.listFiles() ?: return@runCatching
                if (files.size > KEEP_ON_DISK) files.sortedBy { it.lastModified() }.take(files.size - KEEP_ON_DISK * 3 / 4).forEach { it.delete() }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun keyOf(photo: LocalFile) = "${photo.path}|${photo.size}|${photo.changed}"

    fun ready(photo: LocalFile): ImageBitmap? = synchronized(memory) { memory[keyOf(photo)] }

    suspend fun load(photo: LocalFile): ImageBitmap? {
        val key = keyOf(photo)
        synchronized(memory) { memory[key] }?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                synchronized(memory) { memory[key] }?.let { return@withContext it }
                val kept = folder?.let { File(it, MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { b -> "%02x".format(b) } + ".jpg") }
                val small = runCatching {
                    kept?.takeIf { it.isFile && it.length() > 0 }?.let { PhotoFiles.decode(it.readBytes(), SIDE) }
                        ?: PhotoFiles.read(File(photo.path), SIDE)?.also { made ->
                            if (kept != null) runCatching { kept.outputStream().use { made.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                        }
                }.getOrNull() ?: return@withContext null
                val shown = small.image().toComposeImageBitmap()
                small.recycle()
                synchronized(memory) { memory[key] = shown }
                shown
            }
        }
    }
}

/** A picture from the photo tools in the form the app's screens draw. */
fun Bitmap.toScreen(): ImageBitmap = image().toComposeImageBitmap()
