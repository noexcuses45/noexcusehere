package com.noexcusehere.mashwave.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val smoothPaint = Paint(Paint.FILTER_BITMAP_FLAG)

/**
 * One of the photo editor's tools while it is open. It shows the picture the way it would look
 * if the tool were applied now, takes the finger's movements (in picture pixels), and hands
 * back the finished picture when the tick is pressed. Nothing changes until then.
 */
abstract class PhotoTool(val base: Bitmap) {
    /** Goes up whenever what this tool shows has changed, so the screen redraws. */
    var tick by mutableIntStateOf(0)
        protected set

    /** Busy getting ready, or working something out. */
    var working by mutableStateOf(false)
        protected set

    /** Brush size: its radius on the screen, in screen pixels. Tools without a brush ignore it. */
    var size by mutableFloatStateOf(0f)

    /** Whether a ring the size of the brush is shown under the finger. */
    open val hasBrush: Boolean
        get() = false

    open val width: Int
        get() = base.width
    open val height: Int
        get() = base.height

    /** Whether applying would change the picture. */
    abstract val dirty: Boolean

    /** Draws the picture as it stands, in picture pixels. */
    abstract fun draw(canvas: Canvas)

    /** Handles and guide lines that are not part of the picture. [scale] is screen pixels per picture pixel. */
    open fun guides(canvas: Canvas, scale: Float) = Unit

    open fun down(x: Float, y: Float, scale: Float) = Unit
    open fun move(x: Float, y: Float, scale: Float) = Unit
    open fun up() = Unit

    /** The touch turned out to be the start of a two-finger zoom: forget it. */
    open fun cancel() = Unit

    /** The finished picture. Called away from the main thread. */
    abstract fun result(): Bitmap

    open fun close() = Unit
}

/** Adjustments and looks: the whole picture changes with the sliders. */
class ToneTool(base: Bitmap, private val scope: CoroutineScope) : PhotoTool(base) {
    var values by mutableStateOf(PhotoOps.Adjust())
        private set

    // While the sliders move, a smaller copy is recalculated - quick enough to follow the finger.
    private val small: Bitmap = base.fitWithin(PREVIEW_SIDE)
    private val smallPixels: IntArray by lazy { small.pixels() }
    private val frame = RectF(0f, 0f, base.width.toFloat(), base.height.toFloat())

    @Volatile
    private var shown: Bitmap? = null
    private var job: Job? = null
    private var again = false

    override val dirty: Boolean
        get() = !values.isNeutral

    fun set(next: PhotoOps.Adjust) {
        if (next == values) return
        values = next
        if (job?.isActive == true) {
            // Still busy with the last position of the slider: do this one straight after.
            again = true
            return
        }
        job = scope.launch {
            do {
                again = false
                val wanted = values
                shown = if (wanted.isNeutral) {
                    null
                } else {
                    withContext(Dispatchers.Default) {
                        runCatching { bitmapOf(PhotoOps.adjust(smallPixels, small.width, small.height, wanted), small.width, small.height) }.getOrNull()
                    }
                }
                tick++
            } while (again)
        }
    }

    override fun draw(canvas: Canvas) {
        val preview = shown
        if (preview == null) canvas.drawBitmap(base, 0f, 0f, smoothPaint) else canvas.drawBitmap(preview, null, frame, smoothPaint)
    }

    override fun result(): Bitmap = bitmapOf(PhotoOps.adjust(base.pixels(), base.width, base.height, values), base.width, base.height)

    override fun close() {
        job?.cancel()
    }

    companion object {
        const val PREVIEW_SIDE = 960
    }
}

/**
 * A paint-on effect (smooth, whiten, details, lighten). The effect is worked out once for the
 * whole picture; the finger then paints where it should show through.
 */
class BrushTool(base: Bitmap, scope: CoroutineScope, private val make: (IntArray, Int, Int) -> IntArray) : PhotoTool(base) {
    /** How strongly the effect shows where it was painted, 0 to 1. */
    var strength by mutableFloatStateOf(0.75f)

    /** Rub paint out instead of putting it on. */
    var erasing by mutableStateOf(false)

    var painted by mutableStateOf(false)
        private set

    /** Set when the effect could not be worked out (the phone ran short of memory). */
    var failed by mutableStateOf(false)
        private set

    @Volatile
    private var effect: Bitmap? = null

    // Where the finger painted: one byte a pixel. The same bytes feed the picture on screen.
    private val mask: Bitmap = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ALPHA_8)
    private val stride = mask.rowBytes
    private val bytes = ByteArray(stride * base.height)
    private val beforeStroke = ByteArray(bytes.size)
    private val buffer = ByteBuffer.wrap(bytes)
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var stroking = false
    private var lastX = 0f
    private var lastY = 0f

    init {
        working = true
        scope.launch {
            effect = withContext(Dispatchers.Default) {
                runCatching { bitmapOf(make(base.pixels(), base.width, base.height), base.width, base.height) }.getOrNull()
            }
            failed = effect == null
            working = false
            tick++
        }
    }

    override val hasBrush: Boolean
        get() = true

    override val dirty: Boolean
        get() = painted && effect != null

    override fun down(x: Float, y: Float, scale: Float) {
        if (effect == null) return
        stroking = true
        System.arraycopy(bytes, 0, beforeStroke, 0, bytes.size)
        PhotoOps.dab(bytes, stride, base.width, base.height, x, y, radius(scale), FLOW, erasing)
        lastX = x
        lastY = y
        show()
    }

    override fun move(x: Float, y: Float, scale: Float) {
        if (!stroking) return
        PhotoOps.dabLine(bytes, stride, base.width, base.height, lastX, lastY, x, y, radius(scale), FLOW, erasing)
        lastX = x
        lastY = y
        show()
    }

    override fun up() {
        if (!stroking) return
        stroking = false
        if (!erasing) painted = true
    }

    override fun cancel() {
        if (!stroking) return
        stroking = false
        System.arraycopy(beforeStroke, 0, bytes, 0, bytes.size)
        show()
    }

    private fun radius(scale: Float) = (size / scale).coerceAtLeast(1.5f)

    private fun show() {
        buffer.rewind()
        mask.copyPixelsFromBuffer(buffer)
        tick++
    }

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(base, 0f, 0f, smoothPaint)
        val over = effect ?: return
        // The effect goes on a sheet of its own, is cut down to where the brush went, and the
        // sheet is laid over the picture at the chosen strength.
        val sheet = canvas.saveLayerAlpha(0f, 0f, base.width.toFloat(), base.height.toFloat(), (strength.coerceIn(0f, 1f) * 255).roundToInt())
        canvas.drawBitmap(over, 0f, 0f, smoothPaint)
        canvas.drawBitmap(mask, 0f, 0f, maskPaint)
        canvas.restoreToCount(sheet)
    }

    override fun result(): Bitmap {
        val over = effect ?: return base
        val out = base.pixels()
        PhotoOps.mix(out, over.pixels(), bytes, stride, base.width, base.height, strength)
        return bitmapOf(out, base.width, base.height)
    }

    companion object {
        private const val FLOW = 0.5f
    }
}

/** Spot remover: paint over a blemish and it is rebuilt from the skin around it. */
class HealTool(base: Bitmap, private val scope: CoroutineScope) : PhotoTool(base) {
    var canUndo by mutableStateOf(false)
        private set

    private val work: Bitmap = base.copy(Bitmap.Config.ARGB_8888, true)

    /** What a healed patch looked like before, so it can be put back. */
    private class Patch(val left: Int, val top: Int, val w: Int, val h: Int, val pixels: IntArray)

    private val patches = ArrayDeque<Patch>()
    private val points = ArrayList<Float>()
    private val path = Path()
    private var radius = 0f
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0x77FF4D9D
    }

    override val hasBrush: Boolean
        get() = true

    override val dirty: Boolean
        get() = patches.isNotEmpty()

    override fun down(x: Float, y: Float, scale: Float) {
        if (working) return
        radius = (size / scale).coerceAtLeast(1.5f)
        points.clear()
        points += x
        points += y
        path.reset()
        path.moveTo(x, y)
        path.lineTo(x + 0.01f, y)
        tick++
    }

    override fun move(x: Float, y: Float, scale: Float) {
        if (working || points.isEmpty()) return
        points += x
        points += y
        path.lineTo(x, y)
        tick++
    }

    override fun up() {
        if (working || points.isEmpty()) return
        val stroke = points.toFloatArray()
        points.clear()
        working = true
        scope.launch {
            val reach = radius
            val healed = withContext(Dispatchers.Default) { runCatching { heal(stroke, reach) }.getOrNull() }
            if (healed != null) {
                val (patch, pixels) = healed
                work.setPixels(pixels, 0, patch.w, patch.left, patch.top, patch.w, patch.h)
                patches.addLast(patch)
                while (patches.size > 40) patches.removeFirst()
                canUndo = true
            }
            path.reset()
            working = false
            tick++
        }
    }

    /** Works out the healed patch for one stroke: what was there before, and what goes there now. */
    private fun heal(stroke: FloatArray, reach: Float): Pair<Patch, IntArray>? {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in stroke.indices step 2) {
            minX = min(minX, stroke[i])
            maxX = max(maxX, stroke[i])
            minY = min(minY, stroke[i + 1])
            maxY = max(maxY, stroke[i + 1])
        }
        // the stroke, plus a rim of untouched picture to rebuild it from
        val left = (minX - reach - 6).toInt().coerceIn(0, base.width - 1)
        val top = (minY - reach - 6).toInt().coerceIn(0, base.height - 1)
        val right = (maxX + reach + 7).toInt().coerceIn(0, base.width - 1)
        val bottom = (maxY + reach + 7).toInt().coerceIn(0, base.height - 1)
        val w = right - left + 1
        val h = bottom - top + 1
        if (w < 2 || h < 2) return null
        val before = IntArray(w * h)
        work.getPixels(before, 0, w, left, top, w, h)
        val after = before.copyOf()
        PhotoOps.heal(after, w, h, PhotoOps.strokeMask(stroke, reach, left, top, w, h))
        return Patch(left, top, w, h, before) to after
    }

    override fun cancel() {
        if (working) return
        points.clear()
        path.reset()
        tick++
    }

    fun undo() {
        if (working) return
        val patch = patches.removeLastOrNull() ?: return
        work.setPixels(patch.pixels, 0, patch.w, patch.left, patch.top, patch.w, patch.h)
        canUndo = patches.isNotEmpty()
        tick++
    }

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(work, 0f, 0f, smoothPaint)
        if (!path.isEmpty) {
            marker.strokeWidth = radius * 2
            canvas.drawPath(path, marker)
        }
    }

    override fun result(): Bitmap = work
}

/** Reshape: the picture behaves like a rubber sheet that the finger pushes around. */
class ReshapeTool(base: Bitmap) : PhotoTool(base) {
    var canUndo by mutableStateOf(false)
        private set

    private val columns = (GRID * base.width / max(base.width, base.height)).coerceAtLeast(8)
    private val rows = (GRID * base.height / max(base.width, base.height)).coerceAtLeast(8)
    private val cell = max(base.width.toFloat() / columns, base.height.toFloat() / rows)
    private val home = PhotoOps.grid(columns, rows, base.width.toFloat(), base.height.toFloat())
    private var points = home.copyOf()
    private val history = ArrayDeque<FloatArray>()
    private var stroking = false
    private var moved = false
    private var lastX = 0f
    private var lastY = 0f

    override val hasBrush: Boolean
        get() = true

    override val dirty: Boolean
        get() = !points.contentEquals(home)

    override fun down(x: Float, y: Float, scale: Float) {
        history.addLast(points.copyOf())
        while (history.size > 40) history.removeFirst()
        stroking = true
        moved = false
        lastX = x
        lastY = y
    }

    override fun move(x: Float, y: Float, scale: Float) {
        if (!stroking) return
        // A brush much smaller than the squares of the sheet would catch nothing.
        val reach = max(size / scale, cell * 1.6f)
        PhotoOps.push(points, columns, rows, lastX, lastY, (x - lastX) * GRIP, (y - lastY) * GRIP, reach, base.width.toFloat(), base.height.toFloat())
        lastX = x
        lastY = y
        moved = true
        tick++
    }

    override fun up() {
        if (!stroking) return
        stroking = false
        if (!moved) history.removeLastOrNull()
        canUndo = history.isNotEmpty()
    }

    override fun cancel() {
        if (!stroking) return
        stroking = false
        history.removeLastOrNull()?.let { points = it }
        canUndo = history.isNotEmpty()
        tick++
    }

    fun undo() {
        points = history.removeLastOrNull() ?: return
        canUndo = history.isNotEmpty()
        tick++
    }

    /** Back to the picture as it was when the tool was opened. */
    fun reset() {
        if (!dirty) return
        history.addLast(points.copyOf())
        points = home.copyOf()
        canUndo = true
        tick++
    }

    override fun draw(canvas: Canvas) {
        canvas.drawBitmapMesh(base, columns, rows, points, 0, null, 0, smoothPaint)
    }

    override fun result(): Bitmap {
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmapMesh(base, columns, rows, points, 0, null, 0, smoothPaint)
        return out
    }

    companion object {
        /** Squares along the longer side of the sheet. */
        private const val GRID = 96

        /** How much of the finger's movement the picture follows. */
        private const val GRIP = 0.7f
    }
}

/** Background blur: one round area stays sharp, everything around it goes soft. */
class FocusTool(base: Bitmap, private val scope: CoroutineScope) : PhotoTool(base) {
    /** How blurred the surroundings are, 0 to 1. */
    var amount by mutableFloatStateOf(0.55f)
        private set

    /** How big the sharp area is, 0 to 1. */
    var spread by mutableFloatStateOf(0.45f)
        private set

    private var centreX = base.width / 2f
    private var centreY = base.height / 2f
    private var beforeX = centreX
    private var beforeY = centreY
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var travelled = 0f
    private var touching = false

    // Blur hides fine detail anyway, so the soft copy is made small and stretched: far quicker.
    private val small: Bitmap = base.fitWithin(SOFT_SIDE)
    private val smallPixels: IntArray by lazy { small.pixels() }
    private val frame = RectF(0f, 0f, base.width.toFloat(), base.height.toFloat())

    @Volatile
    private var soft: Bitmap? = null
    private var job: Job? = null
    private var again = false
    private var hole: Shader? = null
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCCFFFFFF.toInt()
    }

    init {
        working = true
        blur()
    }

    override val dirty: Boolean
        get() = soft != null

    private val radius: Float
        get() = min(base.width, base.height) * (0.12f + 0.6f * spread)

    fun changeAmount(value: Float) {
        amount = value.coerceIn(0f, 1f)
        blur()
    }

    fun changeSpread(value: Float) {
        spread = value.coerceIn(0f, 1f)
        hole = null
        tick++
    }

    private fun blur() {
        if (job?.isActive == true) {
            again = true
            return
        }
        job = scope.launch {
            do {
                again = false
                val reach = (1.5f + amount * 7f).roundToInt()
                val made = withContext(Dispatchers.Default) {
                    runCatching { bitmapOf(PhotoOps.blur(smallPixels, small.width, small.height, reach), small.width, small.height) }.getOrNull()
                }
                if (made != null) soft = made
                working = false
                tick++
            } while (again)
        }
    }

    override fun down(x: Float, y: Float, scale: Float) {
        touching = true
        beforeX = centreX
        beforeY = centreY
        downX = x
        downY = y
        lastX = x
        lastY = y
        travelled = 0f
    }

    override fun move(x: Float, y: Float, scale: Float) {
        if (!touching) return
        travelled += (abs(x - lastX) + abs(y - lastY)) * scale
        place(centreX + x - lastX, centreY + y - lastY)
        lastX = x
        lastY = y
    }

    override fun up() {
        if (!touching) return
        touching = false
        // A tap rather than a drag: the sharp spot jumps to where the finger was.
        if (travelled < 14f) place(downX, downY)
    }

    override fun cancel() {
        if (!touching) return
        touching = false
        place(beforeX, beforeY)
    }

    private fun place(x: Float, y: Float) {
        centreX = x.coerceIn(0f, base.width.toFloat())
        centreY = y.coerceIn(0f, base.height.toFloat())
        hole = null
        tick++
    }

    private fun paint(canvas: Canvas, blurred: Bitmap) {
        val sheet = canvas.saveLayer(frame, null)
        canvas.drawBitmap(blurred, null, frame, smoothPaint)
        // Rub a soft-edged hole in the blurred sheet so the sharp picture shows through.
        val shader = hole ?: RadialGradient(
            centreX, centreY, radius * 1.6f,
            intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        ).also { hole = it }
        holePaint.shader = shader
        canvas.drawRect(frame, holePaint)
        canvas.restoreToCount(sheet)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(base, 0f, 0f, smoothPaint)
        soft?.let { paint(canvas, it) }
    }

    override fun guides(canvas: Canvas, scale: Float) {
        ring.strokeWidth = 1.5f / scale
        canvas.drawCircle(centreX, centreY, radius, ring)
        canvas.drawCircle(centreX, centreY, 3f / scale, ring)
    }

    override fun result(): Bitmap {
        val blurred = soft ?: return base
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        paint(Canvas(out), blurred)
        return out
    }

    override fun close() {
        job?.cancel()
    }

    companion object {
        private const val SOFT_SIDE = 420
    }
}

/** Freehand drawing over the picture. */
class DrawTool(base: Bitmap) : PhotoTool(base) {
    var colour by mutableIntStateOf(Color.WHITE)

    var canUndo by mutableStateOf(false)
        private set

    private class Line(val path: Path, val colour: Int, val width: Float)

    private val lines = ArrayList<Line>()
    private var current: Line? = null
    private var lastX = 0f
    private var lastY = 0f
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override val hasBrush: Boolean
        get() = true

    override val dirty: Boolean
        get() = lines.isNotEmpty()

    override fun down(x: Float, y: Float, scale: Float) {
        val path = Path()
        path.moveTo(x, y)
        path.lineTo(x + 0.01f, y)
        val line = Line(path, colour, (size * 2f / scale).coerceAtLeast(1f))
        lines += line
        current = line
        lastX = x
        lastY = y
        tick++
    }

    override fun move(x: Float, y: Float, scale: Float) {
        val line = current ?: return
        // Curving through the midpoints rounds off the corners between finger samples.
        line.path.quadTo(lastX, lastY, (lastX + x) / 2f, (lastY + y) / 2f)
        lastX = x
        lastY = y
        tick++
    }

    override fun up() {
        current?.path?.lineTo(lastX, lastY)
        current = null
        canUndo = lines.isNotEmpty()
        tick++
    }

    override fun cancel() {
        current?.let { lines.remove(it) }
        current = null
        canUndo = lines.isNotEmpty()
        tick++
    }

    fun undo() {
        if (lines.isEmpty()) return
        lines.removeAt(lines.lastIndex)
        canUndo = lines.isNotEmpty()
        tick++
    }

    private fun lines(canvas: Canvas) {
        for (line in lines) {
            pen.color = line.colour
            pen.strokeWidth = line.width
            canvas.drawPath(line.path, pen)
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(base, 0f, 0f, smoothPaint)
        lines(canvas)
    }

    override fun result(): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        lines(Canvas(out))
        return out
    }
}

/**
 * Crop, with quarter turns and mirroring. [dp] is how many screen pixels make one "dp", so the
 * handles are finger-sized on every screen.
 */
class CropTool(base: Bitmap, private val dp: Float) : PhotoTool(base) {
    private var work: Bitmap = base
    private var reshaped = false

    /** The part that is kept: left, top, right, bottom in picture pixels. */
    var box: FloatArray = floatArrayOf(0f, 0f, base.width.toFloat(), base.height.toFloat())
        private set

    /** The shape the frame is held to (width / height), or null for any shape. */
    var aspect: Float? = null
        private set

    /** Name of the chosen shape, for the buttons. */
    var shape by mutableStateOf(FREE)
        private set

    private var startBox = box
    private var startX = 0f
    private var startY = 0f
    private var holdLeft = false
    private var holdTop = false
    private var holdRight = false
    private var holdBottom = false
    private var sliding = false
    private var touching = false

    private val shade = Paint().apply { color = 0xB0000000.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    override val width: Int
        get() = work.width
    override val height: Int
        get() = work.height

    override val dirty: Boolean
        get() = reshaped || box[0] > 0.5f || box[1] > 0.5f || box[2] < work.width - 0.5f || box[3] < work.height - 0.5f

    /** Holds the frame to a shape. [ratio] is width / height; null lets it be any shape. */
    fun choose(name: String, ratio: Float?) {
        shape = name
        aspect = ratio
        box = PhotoOps.centredCrop(work.width.toFloat(), work.height.toFloat(), ratio)
        tick++
    }

    /** The shape of the picture itself, as it is turned now. */
    val ownAspect: Float
        get() = work.width.toFloat() / work.height

    /** A quarter turn clockwise. */
    fun turn() {
        work = Bitmap.createBitmap(work, 0, 0, work.width, work.height, Matrix().apply { postRotate(90f) }, true)
        reshaped = true
        if (shape == ORIGINAL) aspect = ownAspect
        box = PhotoOps.centredCrop(work.width.toFloat(), work.height.toFloat(), aspect)
        tick++
    }

    /** Mirrors left to right, or with [upsideDown] top to bottom. The frame stays on the same part of the picture. */
    fun flip(upsideDown: Boolean) {
        val matrix = Matrix().apply { if (upsideDown) postScale(1f, -1f) else postScale(-1f, 1f) }
        work = Bitmap.createBitmap(work, 0, 0, work.width, work.height, matrix, true)
        reshaped = true
        box = if (upsideDown) {
            floatArrayOf(box[0], work.height - box[3], box[2], work.height - box[1])
        } else {
            floatArrayOf(work.width - box[2], box[1], work.width - box[0], box[3])
        }
        tick++
    }

    override fun down(x: Float, y: Float, scale: Float) {
        touching = true
        startBox = box.copyOf()
        startX = x
        startY = y
        val (l, t, r, b) = box
        // How close to an edge counts as taking hold of it. The middle of the frame is always
        // left free for sliding the whole frame, however small it gets.
        val reachX = min(30f * dp / scale, (r - l) / 3f)
        val reachY = min(30f * dp / scale, (b - t) / 3f)
        val outside = 30f * dp / scale
        val within = x > l - outside && x < r + outside && y > t - outside && y < b + outside
        holdLeft = within && x < l + reachX
        holdRight = within && !holdLeft && x > r - reachX
        holdTop = within && y < t + reachY
        holdBottom = within && !holdTop && y > b - reachY
        sliding = within && !holdLeft && !holdRight && !holdTop && !holdBottom
    }

    override fun move(x: Float, y: Float, scale: Float) {
        if (!touching) return
        val dx = x - startX
        val dy = y - startY
        val w = work.width.toFloat()
        val h = work.height.toFloat()
        if (sliding) {
            val bw = startBox[2] - startBox[0]
            val bh = startBox[3] - startBox[1]
            val l = (startBox[0] + dx).coerceIn(0f, w - bw)
            val t = (startBox[1] + dy).coerceIn(0f, h - bh)
            box = floatArrayOf(l, t, l + bw, t + bh)
        } else if (holdLeft || holdRight || holdTop || holdBottom) {
            val smallest = min(max(24f, 44f * dp / scale), min(w, h) / 2f)
            box = PhotoOps.resizeCrop(startBox, holdLeft, holdTop, holdRight, holdBottom, dx, dy, w, h, aspect, smallest)
        } else {
            return
        }
        tick++
    }

    override fun up() {
        touching = false
    }

    override fun cancel() {
        if (!touching) return
        touching = false
        box = startBox
        tick++
    }

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(work, 0f, 0f, smoothPaint)
    }

    override fun guides(canvas: Canvas, scale: Float) {
        val (l, t, r, b) = box
        val w = work.width.toFloat()
        val h = work.height.toFloat()
        // darken what will be cut away
        canvas.drawRect(0f, 0f, w, t, shade)
        canvas.drawRect(0f, b, w, h, shade)
        canvas.drawRect(0f, t, l, b, shade)
        canvas.drawRect(r, t, w, b, shade)
        // thirds
        line.strokeWidth = 1f / scale
        line.alpha = 110
        for (i in 1..2) {
            canvas.drawLine(l + (r - l) * i / 3f, t, l + (r - l) * i / 3f, b, line)
            canvas.drawLine(l, t + (b - t) * i / 3f, r, t + (b - t) * i / 3f, line)
        }
        line.alpha = 255
        line.strokeWidth = 1.5f * dp / scale
        canvas.drawRect(l, t, r, b, line)
        // corner brackets to take hold of
        val arm = min(20f * dp / scale, min(r - l, b - t) / 3f)
        line.strokeWidth = 4f * dp / scale
        val inset = line.strokeWidth / 2f
        for ((x, sx) in listOf(l + inset to 1f, r - inset to -1f)) {
            for ((y, sy) in listOf(t + inset to 1f, b - inset to -1f)) {
                canvas.drawLine(x - sx * inset, y, x + sx * arm, y, line)
                canvas.drawLine(x, y - sy * inset, x, y + sy * arm, line)
            }
        }
    }

    override fun result(): Bitmap {
        val l = box[0].roundToInt().coerceIn(0, work.width - 1)
        val t = box[1].roundToInt().coerceIn(0, work.height - 1)
        val w = (box[2] - box[0]).roundToInt().coerceIn(1, work.width - l)
        val h = (box[3] - box[1]).roundToInt().coerceIn(1, work.height - t)
        return Bitmap.createBitmap(work, l, t, w, h)
    }

    companion object {
        const val FREE = "Free"
        const val ORIGINAL = "Original"
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
