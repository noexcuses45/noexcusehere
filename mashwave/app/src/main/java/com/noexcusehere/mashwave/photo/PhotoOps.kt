package com.noexcusehere.mashwave.photo

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The picture arithmetic behind the photo editor. Everything works on plain arrays of pixels
 * (one Int per pixel: alpha, red, green, blue), so it runs the same on a phone and in a test.
 */
object PhotoOps {
    /** Fine tuning. Brightness to shadows run from -1 (less) to +1 (more); the rest from 0 to 1. */
    data class Adjust(
        val brightness: Float = 0f,
        val contrast: Float = 0f,
        val saturation: Float = 0f,
        val warmth: Float = 0f,
        val highlights: Float = 0f,
        val shadows: Float = 0f,
        val fade: Float = 0f,
        val sharpen: Float = 0f,
        val vignette: Float = 0f,
        /** 0 keeps the colours, 1 is black and white. */
        val mono: Float = 0f,
        /** 0 to 1: an old-photo brown, on top of [mono]. */
        val sepia: Float = 0f,
    ) {
        val isNeutral: Boolean
            get() = this == NEUTRAL

        /** The same change at part strength: 0 changes nothing, 1 is all of it. */
        fun scaled(amount: Float): Adjust = Adjust(
            brightness * amount, contrast * amount, saturation * amount, warmth * amount, highlights * amount,
            shadows * amount, fade * amount, sharpen * amount, vignette * amount, mono * amount, sepia * amount,
        )

        companion object {
            val NEUTRAL = Adjust()
        }
    }

    /** Ready-made colour looks, each a recipe of the same adjustments. */
    enum class Look(val label: String, val recipe: Adjust) {
        NONE("Original", Adjust()),
        VIVID("Vivid", Adjust(contrast = 0.22f, saturation = 0.35f, sharpen = 0.2f)),
        BRIGHT("Bright", Adjust(brightness = 0.18f, shadows = 0.35f, saturation = 0.1f)),
        WARM("Warm", Adjust(warmth = 0.45f, saturation = 0.1f, brightness = 0.04f)),
        GOLDEN("Golden", Adjust(warmth = 0.7f, contrast = 0.12f, highlights = -0.2f, fade = 0.12f)),
        COOL("Cool", Adjust(warmth = -0.45f, contrast = 0.08f)),
        FILM("Film", Adjust(fade = 0.5f, contrast = 0.1f, saturation = -0.18f, warmth = 0.12f, vignette = 0.25f)),
        PUNCH("Punch", Adjust(contrast = 0.4f, saturation = 0.2f, shadows = -0.15f, sharpen = 0.35f)),
        SOFT("Soft", Adjust(contrast = -0.2f, highlights = -0.15f, shadows = 0.3f, fade = 0.2f)),
        MONO("B&W", Adjust(mono = 1f, contrast = 0.12f)),
        NOIR("Noir", Adjust(mono = 1f, contrast = 0.5f, shadows = -0.25f, vignette = 0.45f)),
        SEPIA("Sepia", Adjust(mono = 1f, sepia = 1f, fade = 0.15f)),
    }

    private fun clamp(v: Float): Int = if (v <= 0f) 0 else if (v >= 255f) 255 else (v + 0.5f).toInt()

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Applies [a] to a picture [w] by [h]. Returns a new array; [src] is left alone. */
    fun adjust(src: IntArray, w: Int, h: Int, a: Adjust): IntArray {
        if (a.isNeutral) return src.copyOf()
        // The same setting has to look the same on the small preview and on the full picture.
        val base = if (a.sharpen > 0.001f) sharpen(src, w, h, a.sharpen * 1.4f, radius = max(1, (min(w, h) + 300) / 600)) else src
        val out = IntArray(src.size)
        val contrast = if (a.contrast >= 0f) 1f + a.contrast * 0.9f else 1f + a.contrast * 0.7f
        val saturation = 1f + a.saturation
        val brightness = a.brightness * 70f
        val warm = a.warmth * 28f
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val reach = sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = base[i]
                var r = ((p shr 16) and 0xFF).toFloat()
                var g = ((p shr 8) and 0xFF).toFloat()
                var b = (p and 0xFF).toFloat()

                // light and dark areas first, measured on the untouched pixel
                val light = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                val lift = brightness + a.shadows * 75f * (1f - light) * (1f - light) + a.highlights * 75f * light * light
                r += lift
                g += lift
                b += lift

                r = (r - 128f) * contrast + 128f
                g = (g - 128f) * contrast + 128f
                b = (b - 128f) * contrast + 128f

                r += warm
                b -= warm

                val grey = 0.299f * r + 0.587f * g + 0.114f * b
                r = grey + (r - grey) * saturation
                g = grey + (g - grey) * saturation
                b = grey + (b - grey) * saturation

                if (a.mono > 0f) {
                    val m = 0.299f * r + 0.587f * g + 0.114f * b
                    r += (m - r) * a.mono
                    g += (m - g) * a.mono
                    b += (m - b) * a.mono
                }
                if (a.sepia > 0f) {
                    val m = 0.299f * r + 0.587f * g + 0.114f * b
                    r += (m * 1.07f + 14f - r) * a.sepia
                    g += (m * 0.95f + 4f - g) * a.sepia
                    b += (m * 0.74f - 8f - b) * a.sepia
                }
                if (a.fade > 0f) {
                    r += (r * 0.82f + 38f - r) * a.fade
                    g += (g * 0.82f + 38f - g) * a.fade
                    b += (b * 0.82f + 40f - b) * a.fade
                }
                if (a.vignette > 0f) {
                    val dx = x - cx
                    val dy = y - cy
                    val edge = smoothstep(0.35f, 1f, sqrt(dx * dx + dy * dy) / reach)
                    val keep = 1f - a.vignette * 0.75f * edge
                    r *= keep
                    g *= keep
                    b *= keep
                }
                out[i] = (p and -0x1000000) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
                i++
            }
        }
        return out
    }

    /** A look at [amount] strength (0 to 1). */
    fun look(src: IntArray, w: Int, h: Int, look: Look, amount: Float): IntArray = adjust(src, w, h, look.recipe.scaled(amount.coerceIn(0f, 1f)))

    /**
     * One-tap tidy-up: stretches the picture so its darkest parts are dark and its lightest are
     * light, takes out a colour cast, and adds a touch of colour. A picture that is already
     * well exposed barely changes.
     */
    fun auto(src: IntArray, w: Int, h: Int): IntArray {
        if (src.isEmpty()) return src.copyOf()
        val histogram = IntArray(256)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        for (p in src) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            histogram[(r * 299 + g * 587 + b * 114) / 1000]++
            sumR += r
            sumG += g
            sumB += b
        }
        val cut = src.size / 200 // half a percent at each end is treated as specks
        var low = 0
        var seen = 0
        while (low < 255 && seen + histogram[low] <= cut) seen += histogram[low++]
        var high = 255
        seen = 0
        while (high > 0 && seen + histogram[high] <= cut) seen += histogram[high--]
        // Don't blow a deliberately dark or pale picture up to full range: the stretch is capped,
        // and goes most of the way rather than all of it.
        val offset = min(low, 60) * 0.8f
        val top = max(high, 180).let { it + (255 - it) * 0.2f }
        val scale = 255f / (top - offset).coerceAtLeast(1f)

        // Colour cast: a mild lean towards one colour is evened out a little. A strong lean is
        // left alone - a picture that is mostly sky, sea or grass is meant to be that colour,
        // and "correcting" it would tint everything white in it.
        val mean = (sumR + sumG + sumB) / 3f
        fun lean(sum: Long): Float = if (sum <= 0) 0f else mean / sum - 1f
        val strongest = max(abs(lean(sumR)), max(abs(lean(sumG)), abs(lean(sumB))))
        fun gain(sum: Long): Float = if (strongest > 0.25f) 1f else (1f + lean(sum) * 0.4f).coerceIn(0.95f, 1.05f)
        val gainR = gain(sumR)
        val gainG = gain(sumG)
        val gainB = gain(sumB)

        val out = IntArray(src.size)
        for (i in src.indices) {
            val p = src[i]
            var r = (((p shr 16) and 0xFF) - offset) * scale * gainR
            var g = (((p shr 8) and 0xFF) - offset) * scale * gainG
            var b = ((p and 0xFF) - offset) * scale * gainB
            val grey = 0.299f * r + 0.587f * g + 0.114f * b
            r = grey + (r - grey) * 1.08f
            g = grey + (g - grey) * 1.08f
            b = grey + (b - grey) * 1.08f
            out[i] = (p and -0x1000000) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
        }
        return out
    }

    /**
     * A soft blur. [radius] is in pixels; three quick passes of a simple average look almost
     * exactly like a proper Gaussian blur and cost a fraction of the time.
     */
    fun blur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius <= 0 || w <= 0 || h <= 0) return src.copyOf()
        var a = src.copyOf()
        var b = IntArray(src.size)
        repeat(3) {
            boxPass(a, b, w, h, radius, horizontal = true)
            boxPass(b, a, w, h, radius, horizontal = false)
        }
        return a
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            val start = if (horizontal) line * w else line
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            // prime the window, repeating the edge pixel beyond the border
            for (k in -radius..radius) {
                val p = src[start + k.coerceIn(0, length - 1) * step]
                sa += p ushr 24
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (i in 0 until length) {
                dst[start + i * step] = ((sa / window) shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
                val leaving = src[start + (i - radius).coerceIn(0, length - 1) * step]
                val entering = src[start + (i + radius + 1).coerceIn(0, length - 1) * step]
                sa += (entering ushr 24) - (leaving ushr 24)
                sr += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                sg += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                sb += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    /** Crisper edges: the difference between the picture and a blurred copy, added back. */
    fun sharpen(src: IntArray, w: Int, h: Int, amount: Float, radius: Int = 2): IntArray {
        if (amount <= 0f) return src.copyOf()
        val soft = blur(src, w, h, radius)
        val out = IntArray(src.size)
        for (i in src.indices) {
            val p = src[i]
            val q = soft[i]
            val r = ((p shr 16) and 0xFF)
            val g = ((p shr 8) and 0xFF)
            val b = (p and 0xFF)
            out[i] = (p and -0x1000000) or
                (clamp(r + (r - ((q shr 16) and 0xFF)) * amount) shl 16) or
                (clamp(g + (g - ((q shr 8) and 0xFF)) * amount) shl 8) or
                clamp(b + (b - (q and 0xFF)) * amount)
        }
        return out
    }

    /**
     * Skin smoothing. Fine texture (pores, small blemishes) is evened out while real edges -
     * eyes, lips, hair, the outline of a face - are kept, so the result does not look blurred.
     */
    fun smooth(src: IntArray, w: Int, h: Int): IntArray {
        val radius = max(2, min(w, h) / 110)
        val soft = blur(src, w, h, radius)
        val out = IntArray(src.size)
        for (i in src.indices) {
            val p = src[i]
            val q = soft[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val dr = r - ((q shr 16) and 0xFF)
            val dg = g - ((q shr 8) and 0xFF)
            val db = b - (q and 0xFF)
            // How much this pixel stands out from its surroundings decides how much of it is kept.
            // The strongest of the three colours counts, so a border between two things of the
            // same brightness but different colour (a cheek against the sky) is still a border.
            val detail = max(abs(dr), max(abs(dg), abs(db))).toFloat()
            val keep = 0.12f + 0.88f * smoothstep(8f, 30f, detail)
            out[i] = (p and -0x1000000) or
                (clamp(r - dr * (1f - keep)) shl 16) or
                (clamp(g - dg * (1f - keep)) shl 8) or
                clamp(b - db * (1f - keep))
        }
        return out
    }

    /** Teeth and eye whitening: takes the yellow out and lifts the brightness a little. */
    fun whiten(src: IntArray): IntArray {
        val out = IntArray(src.size)
        for (i in src.indices) {
            val p = src[i]
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            val grey = 0.299f * r + 0.587f * g + 0.114f * b
            val lift = 1.12f
            out[i] = (p and -0x1000000) or
                (clamp((grey + (r - grey) * 0.35f) * lift) shl 16) or
                (clamp((grey + (g - grey) * 0.35f) * lift) shl 8) or
                clamp((grey + (b - grey) * 0.35f) * lift + 3f)
        }
        return out
    }

    /**
     * Removes spots. Every marked pixel ([mask] non-zero) is rebuilt from what surrounds the
     * marked area, colour flowing in from the edges until it settles - which leaves smooth skin
     * where a blemish was. Works in place. Marked areas touching nothing unmarked are left alone.
     */
    fun heal(pixels: IntArray, w: Int, h: Int, mask: ByteArray) {
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mask[y * w + x].toInt() != 0) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < 0) return
        // a rim of surroundings on every side, where there is any: the patch is rebuilt from it,
        // and it is wide enough to tell how grainy the photo is
        left = max(0, left - 4)
        top = max(0, top - 4)
        right = min(w - 1, right + 4)
        bottom = min(h - 1, bottom + 4)
        val bw = right - left + 1
        val bh = bottom - top + 1
        val r = FloatArray(bw * bh)
        val g = FloatArray(bw * bh)
        val b = FloatArray(bw * bh)
        val marked = BooleanArray(bw * bh)
        var known = 0
        for (y in 0 until bh) {
            for (x in 0 until bw) {
                val i = y * bw + x
                val p = pixels[(y + top) * w + x + left]
                marked[i] = mask[(y + top) * w + x + left].toInt() != 0
                r[i] = ((p shr 16) and 0xFF).toFloat()
                g[i] = ((p shr 8) and 0xFF).toFloat()
                b[i] = (p and 0xFF).toFloat()
                if (!marked[i]) known++
            }
        }
        if (known == 0) return
        // How grainy the surroundings are, measured where nothing is marked: the difference
        // between a pixel and the average of the four around it.
        var roughness = 0f
        var measured = 0
        for (y in 1 until bh - 1) {
            for (x in 1 until bw - 1) {
                val i = y * bw + x
                if (marked[i] || marked[i - 1] || marked[i + 1] || marked[i - bw] || marked[i + bw]) continue
                val d = g[i] - (g[i - 1] + g[i + 1] + g[i - bw] + g[i + bw]) * 0.25f
                roughness += d * d
                measured++
            }
        }
        settle(r, g, b, marked, bw, bh)
        // A perfectly smooth patch stands out on a grainy photo, so the patch gets a little of
        // the same grain. (Capped: an edge nearby is not grain.)
        val grain = if (measured > 0) min(5f, sqrt(roughness / measured) * 0.7f) else 0f
        if (grain > 0.4f) {
            val random = java.util.Random(bw * 31L + bh)
            for (i in marked.indices) {
                if (!marked[i]) continue
                val speck = random.nextGaussian().toFloat() * grain
                r[i] += speck
                g[i] += speck
                b[i] += speck
            }
        }
        for (y in 0 until bh) {
            for (x in 0 until bw) {
                val i = y * bw + x
                if (!marked[i]) continue
                val at = (y + top) * w + x + left
                pixels[at] = (pixels[at] and -0x1000000) or (clamp(r[i]) shl 16) or (clamp(g[i]) shl 8) or clamp(b[i])
            }
        }
    }

    /**
     * Fills the marked pixels of a patch so they blend into the unmarked ones around them. A big
     * patch is first solved at half size (and that at half size again), so colour reaches the
     * middle of a large area quickly instead of creeping in one pixel a round.
     */
    private fun settle(r: FloatArray, g: FloatArray, b: FloatArray, marked: BooleanArray, w: Int, h: Int) {
        var rounds = (max(w, h) * 2).coerceIn(24, 200)
        if (max(w, h) > 32 && min(w, h) >= 4) {
            val hw = (w + 1) / 2
            val hh = (h + 1) / 2
            val sr = FloatArray(hw * hh)
            val sg = FloatArray(hw * hh)
            val sb = FloatArray(hw * hh)
            val smallMarked = BooleanArray(hw * hh)
            var anyKnown = false
            for (y in 0 until hh) {
                for (x in 0 until hw) {
                    var count = 0
                    var tr = 0f
                    var tg = 0f
                    var tb = 0f
                    for (yy in y * 2..min(h - 1, y * 2 + 1)) {
                        for (xx in x * 2..min(w - 1, x * 2 + 1)) {
                            val i = yy * w + xx
                            if (!marked[i]) {
                                tr += r[i]
                                tg += g[i]
                                tb += b[i]
                                count++
                            }
                        }
                    }
                    val at = y * hw + x
                    if (count == 0) {
                        smallMarked[at] = true
                    } else {
                        sr[at] = tr / count
                        sg[at] = tg / count
                        sb[at] = tb / count
                        anyKnown = true
                    }
                }
            }
            if (!anyKnown) return
            settle(sr, sg, sb, smallMarked, hw, hh)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!marked[i]) continue
                    val from = (y / 2) * hw + x / 2
                    r[i] = sr[from]
                    g[i] = sg[from]
                    b[i] = sb[from]
                }
            }
            rounds = 16
        } else {
            var count = 0
            var tr = 0f
            var tg = 0f
            var tb = 0f
            for (i in marked.indices) {
                if (!marked[i]) {
                    tr += r[i]
                    tg += g[i]
                    tb += b[i]
                    count++
                }
            }
            if (count == 0) return
            for (i in marked.indices) {
                if (marked[i]) {
                    r[i] = tr / count
                    g[i] = tg / count
                    b[i] = tb / count
                }
            }
        }
        repeat(rounds) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!marked[i]) continue
                    val up = if (y > 0) i - w else i
                    val down = if (y < h - 1) i + w else i
                    val before = if (x > 0) i - 1 else i
                    val after = if (x < w - 1) i + 1 else i
                    r[i] = (r[up] + r[down] + r[before] + r[after]) * 0.25f
                    g[i] = (g[up] + g[down] + g[before] + g[after]) * 0.25f
                    b[i] = (b[up] + b[down] + b[before] + b[after]) * 0.25f
                }
            }
        }
    }

    /**
     * One touch of a soft round brush on a painting mask. [mask] holds one byte per pixel, [stride]
     * bytes to a row: 0 is untouched, 255 fully painted. The touch is strongest in the middle
     * ([flow], 0 to 1) and fades to nothing at [radius]; going over a spot again builds it up.
     * With [erase] the brush takes paint away instead.
     */
    fun dab(mask: ByteArray, stride: Int, w: Int, h: Int, x: Float, y: Float, radius: Float, flow: Float, erase: Boolean) {
        if (radius <= 0f) return
        val fromX = max(0, (x - radius).toInt())
        val toX = min(w - 1, (x + radius).toInt() + 1)
        val fromY = max(0, (y - radius).toInt())
        val toY = min(h - 1, (y + radius).toInt() + 1)
        val reach = radius * radius
        for (py in fromY..toY) {
            val dy = py - y
            for (px in fromX..toX) {
                val dx = px - x
                val d2 = dx * dx + dy * dy
                if (d2 >= reach) continue
                val edge = 1f - d2 / reach
                // full in the inner part, fading smoothly to the rim
                val touch = flow * min(1f, edge * edge * 2.2f)
                val i = py * stride + px
                val now = mask[i].toInt() and 0xFF
                val next = if (erase) now - now * touch else now + (255 - now) * touch
                mask[i] = (next + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
    }

    /** Touches of the brush along the finger's path from ([x0], [y0]) to ([x1], [y1]), close enough together to look like one stroke. */
    fun dabLine(mask: ByteArray, stride: Int, w: Int, h: Int, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, flow: Float, erase: Boolean) {
        val distance = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
        val spacing = max(1f, radius * 0.3f)
        val steps = (distance / spacing).toInt()
        for (step in 1..max(1, steps)) {
            val t = if (steps <= 0) 1f else step.toFloat() / steps
            dab(mask, stride, w, h, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, radius, flow, erase)
        }
    }

    /**
     * Lays [effect] over [base] where the brush painted: fully where [mask] is 255, not at all
     * where it is 0, all of it scaled by [strength] (0 to 1). The result is written into [base].
     */
    fun mix(base: IntArray, effect: IntArray, mask: ByteArray, stride: Int, w: Int, h: Int, strength: Float) {
        val s = (strength.coerceIn(0f, 1f) * 256f).toInt()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val m = (mask[y * stride + x].toInt() and 0xFF) * s / 255
                if (m <= 0) continue
                val i = y * w + x
                val p = base[i]
                val q = effect[i]
                val r = ((p shr 16) and 0xFF) + ((((q shr 16) and 0xFF) - ((p shr 16) and 0xFF)) * m shr 8)
                val g = ((p shr 8) and 0xFF) + ((((q shr 8) and 0xFF) - ((p shr 8) and 0xFF)) * m shr 8)
                val b = (p and 0xFF) + (((q and 0xFF) - (p and 0xFF)) * m shr 8)
                base[i] = (p and -0x1000000) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /**
     * The area a finger covered: every pixel within [radius] of the line through [points]
     * (x, y pairs in picture pixels) is marked, in a patch [bw] by [bh] whose corner sits at
     * ([left], [top]) of the picture.
     */
    fun strokeMask(points: FloatArray, radius: Float, left: Int, top: Int, bw: Int, bh: Int): ByteArray {
        val mask = ByteArray(bw * bh)
        val count = points.size / 2
        if (count == 0 || bw <= 0 || bh <= 0) return mask
        val reach = radius * radius
        for (i in 0 until max(1, count - 1)) {
            val ax = points[i * 2] - left
            val ay = points[i * 2 + 1] - top
            val bx = if (count > 1) points[i * 2 + 2] - left else ax
            val by = if (count > 1) points[i * 2 + 3] - top else ay
            val fromX = max(0, (min(ax, bx) - radius).toInt())
            val toX = min(bw - 1, (max(ax, bx) + radius).toInt() + 1)
            val fromY = max(0, (min(ay, by) - radius).toInt())
            val toY = min(bh - 1, (max(ay, by) + radius).toInt() + 1)
            val dx = bx - ax
            val dy = by - ay
            val length = dx * dx + dy * dy
            for (y in fromY..toY) {
                for (x in fromX..toX) {
                    // distance from this pixel to the nearest point of the line
                    val along = if (length <= 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / length).coerceIn(0f, 1f)
                    val nx = ax + dx * along - x
                    val ny = ay + dy * along - y
                    if (nx * nx + ny * ny <= reach) mask[y * bw + x] = 1
                }
            }
        }
        return mask
    }

    /**
     * The crop frame after a corner or an edge is dragged. [box] is left, top, right, bottom in
     * picture pixels; [moveLeft] etc. say which sides the finger has hold of; [dx], [dy] is how
     * far it moved. The frame stays inside the picture, never gets smaller than [smallest], and
     * keeps the shape [aspect] (width / height) when one is given.
     */
    fun resizeCrop(
        box: FloatArray,
        moveLeft: Boolean,
        moveTop: Boolean,
        moveRight: Boolean,
        moveBottom: Boolean,
        dx: Float,
        dy: Float,
        w: Float,
        h: Float,
        aspect: Float?,
        smallest: Float,
    ): FloatArray {
        var l = box[0]
        var t = box[1]
        var r = box[2]
        var b = box[3]
        if (moveLeft) l = (l + dx).coerceIn(0f, r - smallest)
        if (moveRight) r = (r + dx).coerceIn(l + smallest, w)
        if (moveTop) t = (t + dy).coerceIn(0f, b - smallest)
        if (moveBottom) b = (b + dy).coerceIn(t + smallest, h)
        if (aspect != null && aspect > 0f) {
            val sideways = moveLeft || moveRight
            val upright = moveTop || moveBottom
            if (sideways && (!upright || abs(dx) >= abs(dy))) {
                // the width was set by the finger: fit the height to it, anchored on the side not held
                var width = r - l
                var height = width / aspect
                val room = if (moveTop) b else if (moveBottom) h - t else h
                if (height > room) {
                    height = room
                    width = height * aspect
                    if (moveLeft) l = r - width else r = l + width
                }
                when {
                    moveTop -> t = b - height
                    moveBottom -> b = t + height
                    else -> {
                        val middle = ((t + b) / 2f).coerceIn(height / 2f, h - height / 2f)
                        t = middle - height / 2f
                        b = middle + height / 2f
                    }
                }
            } else if (upright) {
                var height = b - t
                var width = height * aspect
                val room = if (moveLeft) r else if (moveRight) w - l else w
                if (width > room) {
                    width = room
                    height = width / aspect
                    if (moveTop) t = b - height else b = t + height
                }
                when {
                    moveLeft -> l = r - width
                    moveRight -> r = l + width
                    else -> {
                        val middle = ((l + r) / 2f).coerceIn(width / 2f, w - width / 2f)
                        l = middle - width / 2f
                        r = middle + width / 2f
                    }
                }
            }
        }
        return floatArrayOf(l, t, r, b)
    }

    /** The biggest frame of shape [aspect] (width / height) that fits a picture [w] by [h], centred. */
    fun centredCrop(w: Float, h: Float, aspect: Float?): FloatArray {
        if (aspect == null || aspect <= 0f) return floatArrayOf(0f, 0f, w, h)
        var width = w
        var height = w / aspect
        if (height > h) {
            height = h
            width = h * aspect
        }
        return floatArrayOf((w - width) / 2f, (h - height) / 2f, (w + width) / 2f, (h + height) / 2f)
    }

    /**
     * Pushes the points of a stretchy grid laid over the picture: every point within [radius] of
     * the finger at ([x], [y]) moves by part of ([dx], [dy]) - most near the middle, none at the
     * rim. [points] holds x, y pairs, row by row, [columns] + 1 points per row; points on the
     * outer border stay on their edge so the picture's outline never pulls inwards.
     */
    fun push(points: FloatArray, columns: Int, rows: Int, x: Float, y: Float, dx: Float, dy: Float, radius: Float, w: Float, h: Float) {
        if (radius <= 0f) return
        val perRow = columns + 1
        for (row in 0..rows) {
            for (column in 0..columns) {
                val i = (row * perRow + column) * 2
                val px = points[i]
                val py = points[i + 1]
                val distance = sqrt((px - x) * (px - x) + (py - y) * (py - y))
                if (distance >= radius) continue
                val pull = 1f - distance / radius
                val weight = pull * pull * (3f - 2f * pull)
                if (column != 0 && column != columns) points[i] = (px + dx * weight).coerceIn(0f, w)
                if (row != 0 && row != rows) points[i + 1] = (py + dy * weight).coerceIn(0f, h)
            }
        }
    }

    /** Screen pixels per picture pixel when a picture [w] by [h] just fits an area [areaW] by [areaH] with [margin] around it. */
    fun fitScale(areaW: Float, areaH: Float, w: Int, h: Int, margin: Float): Float =
        min((areaW - 2 * margin) / w, (areaH - 2 * margin) / h).coerceAtLeast(0.0001f)

    /**
     * Zooming and moving the picture on screen. [view] holds the zoom (1 = just fits) and how far
     * the picture's middle has been moved from the middle of the area, in screen pixels. Whatever
     * was under the fingers at ([fromX], [fromY]) follows them by ([panX], [panY]) while the zoom
     * changes by [zoomBy]. The zoom stays between 1 and 8, and the picture cannot be pushed off
     * the screen. Returns the new zoom and position.
     */
    fun viewAfter(
        view: FloatArray,
        areaW: Float,
        areaH: Float,
        w: Int,
        h: Int,
        margin: Float,
        fromX: Float,
        fromY: Float,
        panX: Float,
        panY: Float,
        zoomBy: Float,
    ): FloatArray {
        val fit = fitScale(areaW, areaH, w, h, margin)
        val before = fit * view[0]
        // the spot of the picture under the fingers, measured from the picture's middle
        val pictureX = (fromX - areaW / 2f - view[1]) / before
        val pictureY = (fromY - areaH / 2f - view[2]) / before
        val zoom = (view[0] * zoomBy).coerceIn(1f, 8f)
        val after = fit * zoom
        val limitX = max(0f, (w * after - (areaW - 2 * margin)) / 2f)
        val limitY = max(0f, (h * after - (areaH - 2 * margin)) / 2f)
        return floatArrayOf(
            zoom,
            (fromX + panX - pictureX * after - areaW / 2f).coerceIn(-limitX, limitX),
            (fromY + panY - pictureY * after - areaH / 2f).coerceIn(-limitY, limitY),
        )
    }

    /** A fresh grid for [push]: evenly spaced points covering a picture [w] by [h]. */
    fun grid(columns: Int, rows: Int, w: Float, h: Float): FloatArray {
        val points = FloatArray((columns + 1) * (rows + 1) * 2)
        var i = 0
        for (row in 0..rows) {
            for (column in 0..columns) {
                points[i++] = w * column / columns
                points[i++] = h * row / rows
            }
        }
        return points
    }
}
