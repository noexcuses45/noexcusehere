/*
 * Stand-ins, on a computer, for the handful of Android drawing classes the photo editor's tools
 * use (Bitmap, Canvas, Paint, Path and a few small ones).
 *
 * The tools themselves (photo/PhotoTools.kt) are the phone app's own file, compiled into this app
 * unchanged, so a fix made there reaches both. That file asks for "android.graphics.Bitmap" and
 * the rest by name; on a phone Android provides them, here this file does. Android draws with the
 * same engine underneath (Skia) as this app's windows, so each stand-in is a thin wrapper and the
 * pictures come out the same.
 *
 * Only what the tools need is here. It is not Android, and nothing else should lean on it.
 */
@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package android.graphics

import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Gradient
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.VertexMode
import java.io.OutputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import org.jetbrains.skia.Bitmap as SkBitmap
import org.jetbrains.skia.Canvas as SkCanvas
import org.jetbrains.skia.Paint as SkPaint
import org.jetbrains.skia.Path as SkPath
import org.jetbrains.skia.Rect as SkRect
import org.jetbrains.skia.Shader as SkShader

/** Colours as whole numbers: alpha, red, green, blue, a byte each. */
object Color {
    const val BLACK: Int = 0xFF000000.toInt()
    const val WHITE: Int = 0xFFFFFFFF.toInt()
    const val TRANSPARENT: Int = 0
}

class RectF(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
    fun width(): Float = right - left
    fun height(): Float = bottom - top
    internal fun sk(): SkRect = SkRect.makeLTRB(left, top, right, bottom)
}

class Rect(var left: Int = 0, var top: Int = 0, var right: Int = 0, var bottom: Int = 0) {
    fun width(): Int = right - left
    fun height(): Int = bottom - top
    internal fun sk(): SkRect = SkRect.makeLTRB(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}

/** A turn, a stretch and a slide rolled into one: the nine numbers of a 3 by 3 grid, row by row. */
class Matrix {
    private var m = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private fun after(n: FloatArray) {
        // "post": this one happens first, then [n].
        val out = FloatArray(9)
        for (row in 0..2) for (col in 0..2) {
            out[row * 3 + col] = n[row * 3] * m[col] + n[row * 3 + 1] * m[3 + col] + n[row * 3 + 2] * m[6 + col]
        }
        m = out
    }

    /** Turns by [degrees], clockwise as seen on a screen. */
    fun postRotate(degrees: Float): Boolean {
        // Quarter turns come out exact, so a turned picture loses nothing.
        val quarters = degrees / 90f
        val whole = abs(quarters - quarters.roundToInt()) < 1e-6f
        val c: Float
        val s: Float
        if (whole) {
            when (((quarters.roundToInt() % 4) + 4) % 4) {
                0 -> { c = 1f; s = 0f }
                1 -> { c = 0f; s = 1f }
                2 -> { c = -1f; s = 0f }
                else -> { c = 0f; s = -1f }
            }
        } else {
            val r = Math.toRadians(degrees.toDouble())
            c = cos(r).toFloat()
            s = sin(r).toFloat()
        }
        after(floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f))
        return true
    }

    fun postScale(sx: Float, sy: Float): Boolean {
        after(floatArrayOf(sx, 0f, 0f, 0f, sy, 0f, 0f, 0f, 1f))
        return true
    }

    fun postTranslate(dx: Float, dy: Float): Boolean {
        after(floatArrayOf(1f, 0f, dx, 0f, 1f, dy, 0f, 0f, 1f))
        return true
    }

    /** Replaces [rect] with the smallest upright box around where it ends up. */
    fun mapRect(rect: RectF): Boolean {
        val xs = floatArrayOf(rect.left, rect.right, rect.right, rect.left)
        val ys = floatArrayOf(rect.top, rect.top, rect.bottom, rect.bottom)
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in 0..3) {
            val x = m[0] * xs[i] + m[1] * ys[i] + m[2]
            val y = m[3] * xs[i] + m[4] * ys[i] + m[5]
            l = min(l, x)
            r = max(r, x)
            t = min(t, y)
            b = max(b, y)
        }
        rect.left = l
        rect.top = t
        rect.right = r
        rect.bottom = b
        return true
    }

    /** True when it only turns by quarter turns, mirrors and slides: every dot lands exactly on a dot. */
    internal val keepsDots: Boolean
        get() = (0..5).all { it == 2 || it == 5 || abs(m[it]) < 1e-6f || abs(abs(m[it]) - 1f) < 1e-6f }

    internal fun sk(): Matrix33 = Matrix33(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8])
}

class PorterDuff {
    /** How what is drawn joins what is already there. */
    enum class Mode(internal val sk: BlendMode) {
        CLEAR(BlendMode.CLEAR),
        SRC(BlendMode.SRC),
        DST(BlendMode.DST),
        SRC_OVER(BlendMode.SRC_OVER),
        DST_OVER(BlendMode.DST_OVER),
        SRC_IN(BlendMode.SRC_IN),
        DST_IN(BlendMode.DST_IN),
        SRC_OUT(BlendMode.SRC_OUT),
        DST_OUT(BlendMode.DST_OUT),
        SRC_ATOP(BlendMode.SRC_ATOP),
        DST_ATOP(BlendMode.DST_ATOP),
        XOR(BlendMode.XOR),
        MULTIPLY(BlendMode.MULTIPLY),
        SCREEN(BlendMode.SCREEN),
    }
}

open class Xfermode

class PorterDuffXfermode(val mode: PorterDuff.Mode) : Xfermode()

open class Shader internal constructor(internal val sk: SkShader) {
    enum class TileMode(internal val sk: FilterTileMode) {
        CLAMP(FilterTileMode.CLAMP),
        REPEAT(FilterTileMode.REPEAT),
        MIRROR(FilterTileMode.MIRROR),
    }
}

/** Colours spreading out in rings from a middle point. */
class RadialGradient(centerX: Float, centerY: Float, radius: Float, colors: IntArray, stops: FloatArray?, tile: TileMode) : Shader(
    SkShader.makeRadialGradient(
        centerX,
        centerY,
        radius,
        Gradient(Gradient.Colors(Array(colors.size) { Color4f(colors[it]) }, stops, tile.sk)),
    ),
)

class Paint(flags: Int = 0) {
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Join { MITER, ROUND, BEVEL }

    internal val sk = SkPaint()

    /** Whether a picture drawn with this is smoothed when it is stretched or shrunk. */
    internal val filter: Boolean = flags and FILTER_BITMAP_FLAG != 0

    init {
        sk.isAntiAlias = flags and ANTI_ALIAS_FLAG != 0
    }

    var style: Style = Style.FILL
        set(value) {
            field = value
            sk.mode = when (value) {
                Style.FILL -> PaintMode.FILL
                Style.STROKE -> PaintMode.STROKE
                Style.FILL_AND_STROKE -> PaintMode.STROKE_AND_FILL
            }
        }

    var strokeCap: Cap = Cap.BUTT
        set(value) {
            field = value
            sk.strokeCap = when (value) {
                Cap.BUTT -> PaintStrokeCap.BUTT
                Cap.ROUND -> PaintStrokeCap.ROUND
                Cap.SQUARE -> PaintStrokeCap.SQUARE
            }
        }

    var strokeJoin: Join = Join.MITER
        set(value) {
            field = value
            sk.strokeJoin = when (value) {
                Join.MITER -> PaintStrokeJoin.MITER
                Join.ROUND -> PaintStrokeJoin.ROUND
                Join.BEVEL -> PaintStrokeJoin.BEVEL
            }
        }

    var color: Int
        get() = sk.color
        set(value) {
            sk.color = value
        }

    var alpha: Int
        get() = sk.alpha
        set(value) {
            sk.alpha = value.coerceIn(0, 255)
        }

    var strokeWidth: Float
        get() = sk.strokeWidth
        set(value) {
            sk.strokeWidth = value
        }

    var xfermode: Xfermode? = null
        set(value) {
            field = value
            sk.blendMode = (value as? PorterDuffXfermode)?.mode?.sk ?: BlendMode.SRC_OVER
        }

    var shader: Shader? = null
        set(value) {
            field = value
            sk.shader = value?.sk
        }

    companion object {
        const val ANTI_ALIAS_FLAG = 1
        const val FILTER_BITMAP_FLAG = 2
    }
}

/** A line drawn as a run of straight and curved pieces. */
class Path {
    private val builder = PathBuilder()
    private var made: SkPath? = null
    private var pieces = 0

    val isEmpty: Boolean
        get() = pieces == 0

    fun reset() {
        builder.reset()
        pieces = 0
        drop()
    }

    fun moveTo(x: Float, y: Float) {
        builder.moveTo(x, y)
        touched()
    }

    fun lineTo(x: Float, y: Float) {
        builder.lineTo(x, y)
        touched()
    }

    fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) {
        builder.quadTo(x1, y1, x2, y2)
        touched()
    }

    private fun touched() {
        pieces++
        drop()
    }

    private fun drop() {
        made?.close()
        made = null
    }

    internal fun sk(): SkPath = made ?: builder.snapshot().also { made = it }
}

/**
 * A picture held as dots: four bytes a dot for a colour picture, one for a see-through mask.
 * As on Android, one made from ready dots (or cut or turned out of another) can only be looked
 * at; a blank one, or a copy asked for as changeable, can be drawn on.
 */
class Bitmap internal constructor(internal val sk: SkBitmap, val config: Config, mutable: Boolean) {
    var isMutable: Boolean = mutable
        private set

    enum class Config { ALPHA_8, ARGB_8888 }
    enum class CompressFormat { JPEG, PNG, WEBP }

    val width: Int get() = sk.width
    val height: Int get() = sk.height
    val rowBytes: Int get() = sk.rowBytes

    private var picture: Image? = null

    init {
        // A picture that will never change can be drawn straight from its own dots.
        if (!isMutable) sk.setImmutable()
        made(sk.computeByteSize().toLong())
    }

    /** The form the drawing engine takes: made once, and again only after the dots change. */
    internal fun image(): Image = synchronized(this) { picture ?: Image.makeFromBitmap(sk).also { picture = it } }

    internal fun changed() {
        synchronized(this) {
            picture?.close()
            picture = null
        }
        sk.notifyPixelsChanged()
    }

    private fun changeable() = check(isMutable) { "this picture cannot be changed" }

    /**
     * Says the picture is finished: from here on it can only be looked at, and is drawn straight
     * from its own dots instead of from a second copy of them.
     */
    internal fun freeze(): Bitmap {
        if (isMutable) {
            isMutable = false
            sk.setImmutable()
            changed()
        }
        return this
    }

    /** A copy with the same dots. */
    fun copy(config: Config, isMutable: Boolean): Bitmap {
        val out = SkBitmap()
        check(out.allocPixels(infoFor(width, height, config))) { "no room for the picture" }
        SkCanvas(out).use { it.writePixels(sk, 0, 0) }
        return Bitmap(out, config, isMutable)
    }

    /** Reads a block of dots as whole numbers (alpha, red, green, blue). */
    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        val bytes = sk.readPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), width * 4, x, y)
            ?: throw IllegalStateException("the picture could not be read")
        val numbers = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        if (stride == width) {
            numbers.get(pixels, offset, width * height)
        } else {
            for (row in 0 until height) {
                numbers.position(row * width)
                numbers.get(pixels, offset + row * stride, width)
            }
        }
    }

    /** Writes a block of dots. They are taken to be solid, as every dot of a photo here is. */
    fun setPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        changeable()
        val part = SkBitmap()
        try {
            check(part.installPixels(infoFor(width, height, Config.ARGB_8888), bytesOf(pixels, offset, stride, width, height), width * 4)) { "no room for the picture" }
            SkCanvas(sk).use { it.writePixels(part, x, y) }
        } finally {
            part.close()
        }
        changed()
    }

    /** Replaces every dot with the bytes in [source], from the start of its array. */
    fun copyPixelsFromBuffer(source: Buffer) {
        changeable()
        val bytes = (source as ByteBuffer).array()
        check(sk.installPixels(sk.imageInfo, bytes, sk.rowBytes)) { "no room for the picture" }
        changed()
    }

    /** Writes the picture out as a JPEG, PNG or WebP file. */
    fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean {
        val kind = when (format) {
            CompressFormat.JPEG -> EncodedImageFormat.JPEG
            CompressFormat.PNG -> EncodedImageFormat.PNG
            CompressFormat.WEBP -> EncodedImageFormat.WEBP
        }
        val data = image().encodeToData(kind, quality.coerceIn(0, 100)) ?: return false
        return try {
            stream.write(data.bytes)
            true
        } finally {
            data.close()
        }
    }

    /** Lets go of the memory now rather than whenever the tidying-up gets round to it. */
    fun recycle() {
        synchronized(this) {
            picture?.close()
            picture = null
        }
        if (!sk.isClosed) sk.close()
    }

    companion object {
        private val sinceTidy = AtomicLong()

        /**
         * Pictures live outside the memory Java keeps count of, so Java does not notice them
         * piling up. After a few hundred megabytes of them it is told to clear out what is no
         * longer used.
         */
        private fun made(bytes: Long) {
            if (sinceTidy.addAndGet(bytes) > TIDY_AFTER) {
                sinceTidy.set(0)
                System.gc()
            }
        }

        private const val TIDY_AFTER = 400L * 1024 * 1024

        internal fun infoFor(width: Int, height: Int, config: Config): ImageInfo = when (config) {
            Config.ARGB_8888 -> ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
            Config.ALPHA_8 -> ImageInfo.makeA8(width, height)
        }

        private fun bytesOf(pixels: IntArray, offset: Int, stride: Int, width: Int, height: Int): ByteArray {
            val bytes = ByteArray(width * height * 4)
            val numbers = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            if (stride == width) {
                numbers.put(pixels, offset, width * height)
            } else {
                for (row in 0 until height) numbers.put(pixels, offset + row * stride, width)
            }
            return bytes
        }

        /** A blank, see-through picture that can be drawn on. */
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap {
            val out = SkBitmap()
            check(out.allocPixels(infoFor(width, height, config))) { "no room for the picture" }
            out.erase(0)
            return Bitmap(out, config, true)
        }

        /** A picture made of [colors], row by row. The dots are taken to be solid. */
        fun createBitmap(colors: IntArray, width: Int, height: Int, config: Config): Bitmap {
            val out = SkBitmap()
            check(out.installPixels(infoFor(width, height, config), bytesOf(colors, 0, width, width, height), width * 4)) { "no room for the picture" }
            return Bitmap(out, config, false)
        }

        /** The part of [source] starting at [x], [y]. */
        fun createBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
            if (x == 0 && y == 0 && width == source.width && height == source.height && !source.isMutable) return source
            val info = infoFor(width, height, source.config)
            val rowBytes = if (source.config == Config.ALPHA_8) width else width * 4
            val bytes = source.sk.readPixels(info, rowBytes, x, y) ?: throw IllegalStateException("the picture could not be read")
            val out = SkBitmap()
            check(out.installPixels(info, bytes, rowBytes)) { "no room for the picture" }
            return Bitmap(out, source.config, false)
        }

        /** The part of [source] starting at [x], [y], turned, mirrored or stretched by [matrix]. */
        fun createBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int, matrix: Matrix?, filter: Boolean): Bitmap {
            if (matrix == null) return createBitmap(source, x, y, width, height)
            val frame = RectF(0f, 0f, width.toFloat(), height.toFloat())
            matrix.mapRect(frame)
            val w = frame.width().roundToInt().coerceAtLeast(1)
            val h = frame.height().roundToInt().coerceAtLeast(1)
            val out = SkBitmap()
            check(out.allocPixels(infoFor(w, h, source.config))) { "no room for the picture" }
            out.erase(0)
            SkCanvas(out).use { canvas ->
                canvas.translate(-frame.left, -frame.top)
                canvas.concat(matrix.sk())
                val sampling = if (filter && !matrix.keepsDots) SMOOTH else SamplingMode.DEFAULT
                canvas.drawImageRect(
                    source.image(),
                    SkRect.makeXYWH(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat()),
                    SkRect.makeWH(width.toFloat(), height.toFloat()),
                    sampling,
                    null,
                    true,
                )
            }
            return Bitmap(out, source.config, false)
        }

        /** [source] stretched or shrunk to [width] by [height]. */
        fun createScaledBitmap(source: Bitmap, width: Int, height: Int, filter: Boolean): Bitmap {
            if (width == source.width && height == source.height) return source
            val out = SkBitmap()
            check(out.allocPixels(infoFor(width, height, source.config))) { "no room for the picture" }
            out.erase(0)
            SkCanvas(out).use { canvas ->
                canvas.drawImageRect(
                    source.image(),
                    SkRect.makeWH(source.width.toFloat(), source.height.toFloat()),
                    SkRect.makeWH(width.toFloat(), height.toFloat()),
                    if (filter) SMOOTH else SamplingMode.DEFAULT,
                    null,
                    true,
                )
            }
            return Bitmap(out, source.config, false)
        }

        /** Smoothed, and smoothed properly when the picture is shown much smaller than it is. */
        internal val SMOOTH: SamplingMode = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)
    }
}

/**
 * Something to draw on: a picture ([Bitmap]), or - through the second constructor - part of the
 * app's window.
 */
class Canvas private constructor(private val sk: SkCanvas, private val target: Bitmap?) {
    constructor(bitmap: Bitmap) : this(SkCanvas(bitmap.also { check(it.isMutable) { "this picture cannot be drawn on" } }.sk), bitmap)

    /** Draws on whatever [window] draws on. */
    constructor(window: SkCanvas) : this(window, null)

    private fun drawn() {
        target?.changed()
    }

    private fun sampling(bitmap: Bitmap, paint: Paint?): SamplingMode = when {
        paint?.filter != true -> SamplingMode.DEFAULT
        // A mask changes with every movement of the brush; the extra smoothing is not worth redoing each time.
        bitmap.config == Bitmap.Config.ALPHA_8 -> SamplingMode.LINEAR
        else -> Bitmap.SMOOTH
    }

    fun save(): Int = sk.save()

    fun restore() {
        sk.restore()
    }

    fun restoreToCount(count: Int) {
        sk.restoreToCount(count)
    }

    fun translate(dx: Float, dy: Float) {
        sk.translate(dx, dy)
    }

    fun scale(sx: Float, sy: Float) {
        sk.scale(sx, sy)
    }

    fun concat(matrix: Matrix?) {
        if (matrix != null) sk.concat(matrix.sk())
    }

    /** Starts a see-through sheet; what is drawn until the matching restore is laid on at [alpha] (0 to 255). */
    fun saveLayerAlpha(left: Float, top: Float, right: Float, bottom: Float, alpha: Int): Int {
        val fade = SkPaint()
        try {
            fade.alpha = alpha.coerceIn(0, 255)
            return sk.saveLayer(left, top, right, bottom, fade)
        } finally {
            fade.close()
        }
    }

    fun saveLayer(bounds: RectF?, paint: Paint?): Int = sk.saveLayer(bounds?.sk(), paint?.sk)

    fun drawColor(color: Int) {
        sk.clear(color)
        drawn()
    }

    fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
        sk.drawImageRect(
            bitmap.image(),
            SkRect.makeWH(bitmap.width.toFloat(), bitmap.height.toFloat()),
            SkRect.makeXYWH(left, top, bitmap.width.toFloat(), bitmap.height.toFloat()),
            sampling(bitmap, paint),
            paint?.sk,
            true,
        )
        drawn()
    }

    fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
        sk.drawImageRect(
            bitmap.image(),
            src?.sk() ?: SkRect.makeWH(bitmap.width.toFloat(), bitmap.height.toFloat()),
            dst.sk(),
            sampling(bitmap, paint),
            paint?.sk,
            true,
        )
        drawn()
    }

    fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
        sk.save()
        sk.concat(matrix.sk())
        sk.drawImageRect(
            bitmap.image(),
            SkRect.makeWH(bitmap.width.toFloat(), bitmap.height.toFloat()),
            SkRect.makeWH(bitmap.width.toFloat(), bitmap.height.toFloat()),
            if (paint?.filter == true && !matrix.keepsDots) Bitmap.SMOOTH else SamplingMode.DEFAULT,
            paint?.sk,
            true,
        )
        sk.restore()
        drawn()
    }

    /**
     * Draws [bitmap] as a rubber sheet: it is cut into [meshWidth] by [meshHeight] squares and
     * each corner is put where [verts] says (x then y, row by row, starting at [vertOffset]).
     */
    fun drawBitmapMesh(bitmap: Bitmap, meshWidth: Int, meshHeight: Int, verts: FloatArray, vertOffset: Int, colors: IntArray?, colorOffset: Int, paint: Paint?) {
        val count = (meshWidth + 1) * (meshHeight + 1)
        val places = if (vertOffset == 0 && verts.size == count * 2) verts else verts.copyOfRange(vertOffset, vertOffset + count * 2)
        val sheet = Sheet.of(meshWidth, meshHeight, bitmap.width, bitmap.height)
        val shader = bitmap.image().makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, if (paint?.filter == true) SamplingMode.LINEAR else SamplingMode.DEFAULT, null)
        val fill = SkPaint()
        try {
            fill.shader = shader
            sk.drawVertices(VertexMode.TRIANGLES, places, null, sheet.from, sheet.corners, BlendMode.MODULATE, fill)
        } finally {
            fill.close()
            shader.close()
        }
        drawn()
    }

    fun drawRect(rect: RectF, paint: Paint) {
        sk.drawRect(rect.sk(), paint.sk)
        drawn()
    }

    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        sk.drawRect(SkRect.makeLTRB(left, top, right, bottom), paint.sk)
        drawn()
    }

    fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
        sk.drawLine(startX, startY, stopX, stopY, paint.sk)
        drawn()
    }

    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        sk.drawCircle(cx, cy, radius, paint.sk)
        drawn()
    }

    fun drawPath(path: Path, paint: Paint) {
        sk.drawPath(path.sk(), paint.sk)
        drawn()
    }

    /** Where each corner of a rubber sheet comes from in the flat picture, and which corners make which triangles. */
    private class Sheet(val from: FloatArray, val corners: ShortArray) {
        companion object {
            private var last: Pair<List<Int>, Sheet>? = null

            @Synchronized
            fun of(columns: Int, rows: Int, width: Int, height: Int): Sheet {
                val key = listOf(columns, rows, width, height)
                last?.let { if (it.first == key) return it.second }
                require((columns + 1) * (rows + 1) <= Short.MAX_VALUE) { "too many squares" }
                val from = FloatArray((columns + 1) * (rows + 1) * 2)
                var i = 0
                for (row in 0..rows) {
                    for (column in 0..columns) {
                        from[i++] = width.toFloat() * column / columns
                        from[i++] = height.toFloat() * row / rows
                    }
                }
                val corners = ShortArray(columns * rows * 6)
                var c = 0
                for (row in 0 until rows) {
                    for (column in 0 until columns) {
                        val topLeft = row * (columns + 1) + column
                        val bottomLeft = topLeft + columns + 1
                        corners[c++] = topLeft.toShort()
                        corners[c++] = (topLeft + 1).toShort()
                        corners[c++] = bottomLeft.toShort()
                        corners[c++] = (topLeft + 1).toShort()
                        corners[c++] = (bottomLeft + 1).toShort()
                        corners[c++] = bottomLeft.toShort()
                    }
                }
                return Sheet(from, corners).also { last = key to it }
            }
        }
    }
}
