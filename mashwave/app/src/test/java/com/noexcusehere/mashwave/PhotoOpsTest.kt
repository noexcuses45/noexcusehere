package com.noexcusehere.mashwave

import com.noexcusehere.mashwave.photo.PhotoOps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PhotoOpsTest {
    private fun rgb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF
    private fun light(p: Int) = (red(p) * 299 + green(p) * 587 + blue(p) * 114) / 1000

    private fun flat(w: Int, h: Int, colour: Int) = IntArray(w * h) { colour }

    /** Left to right from dark to light, with a little colour. */
    private fun ramp(w: Int, h: Int, from: Int = 0, to: Int = 255) = IntArray(w * h) { i ->
        val v = from + (to - from) * (i % w) / (w - 1)
        rgb(v, (v * 0.9f).toInt(), (v * 0.8f).toInt())
    }

    // ---- adjust and looks ------------------------------------------------------------------

    @Test
    fun noAdjustmentLeavesThePictureAlone() {
        val src = ramp(16, 4)
        val out = PhotoOps.adjust(src, 16, 4, PhotoOps.Adjust())
        assertArrayEquals(src, out)
        assertFalse("a copy, not the same array", src === out)
        assertArrayEquals(src, PhotoOps.look(src, 16, 4, PhotoOps.Look.NONE, 1f))
        assertArrayEquals(src, PhotoOps.look(src, 16, 4, PhotoOps.Look.NOIR, 0f))
    }

    @Test
    fun brightnessGoesBothWays() {
        val src = flat(4, 4, rgb(100, 110, 120))
        assertTrue(light(PhotoOps.adjust(src, 4, 4, PhotoOps.Adjust(brightness = 0.5f))[5]) > light(src[5]) + 20)
        assertTrue(light(PhotoOps.adjust(src, 4, 4, PhotoOps.Adjust(brightness = -0.5f))[5]) < light(src[5]) - 20)
    }

    @Test
    fun contrastSpreadsLightAndDarkApart() {
        val src = ramp(32, 2, 60, 200)
        val out = PhotoOps.adjust(src, 32, 2, PhotoOps.Adjust(contrast = 0.6f))
        assertTrue(light(out[0]) < light(src[0]))
        assertTrue(light(out[31]) > light(src[31]))
    }

    @Test
    fun colourSliderRemovesOrAddsColour() {
        val src = flat(2, 2, rgb(200, 100, 50))
        val grey = PhotoOps.adjust(src, 2, 2, PhotoOps.Adjust(saturation = -1f))[0]
        assertTrue(abs(red(grey) - green(grey)) <= 1 && abs(green(grey) - blue(grey)) <= 1)
        val vivid = PhotoOps.adjust(src, 2, 2, PhotoOps.Adjust(saturation = 0.5f))[0]
        assertTrue(red(vivid) - blue(vivid) > 150)
    }

    @Test
    fun warmthShiftsTowardsRedOrBlue() {
        val src = flat(2, 2, rgb(120, 120, 120))
        val warm = PhotoOps.adjust(src, 2, 2, PhotoOps.Adjust(warmth = 1f))[0]
        val cool = PhotoOps.adjust(src, 2, 2, PhotoOps.Adjust(warmth = -1f))[0]
        assertTrue(red(warm) > blue(warm) + 30)
        assertTrue(blue(cool) > red(cool) + 30)
    }

    @Test
    fun shadowsLiftDarkPartsAndLeaveLightOnes() {
        val src = ramp(32, 2, 10, 245)
        val out = PhotoOps.adjust(src, 32, 2, PhotoOps.Adjust(shadows = 1f))
        assertTrue(light(out[1]) > light(src[1]) + 40)
        assertTrue(abs(light(out[31]) - light(src[31])) < 6)
    }

    @Test
    fun vignetteDarkensTheCornersOnly() {
        val src = flat(41, 41, rgb(200, 200, 200))
        val out = PhotoOps.adjust(src, 41, 41, PhotoOps.Adjust(vignette = 1f))
        assertEquals(200, red(out[20 * 41 + 20]))
        assertTrue(red(out[0]) < 90)
    }

    @Test
    fun blackAndWhiteLooksHaveNoColour() {
        val src = ramp(16, 2)
        for (look in listOf(PhotoOps.Look.MONO, PhotoOps.Look.NOIR)) {
            val out = PhotoOps.look(src, 16, 2, look, 1f)
            // away from the corners, which Noir darkens unevenly
            val p = out[8]
            assertTrue(look.name, abs(red(p) - green(p)) <= 2 && abs(green(p) - blue(p)) <= 2)
        }
        val sepia = PhotoOps.look(flat(4, 4, rgb(128, 128, 128)), 4, 4, PhotoOps.Look.SEPIA, 1f)[5]
        assertTrue(red(sepia) > green(sepia) && green(sepia) > blue(sepia))
    }

    @Test
    fun halfStrengthLookSitsBetweenOriginalAndFull() {
        val src = flat(4, 4, rgb(180, 90, 60))
        val full = PhotoOps.look(src, 4, 4, PhotoOps.Look.MONO, 1f)[5]
        val half = PhotoOps.look(src, 4, 4, PhotoOps.Look.MONO, 0.5f)[5]
        assertTrue(red(half) < red(src[5]) && red(half) > red(full))
    }

    @Test
    fun seeThroughnessIsKept() {
        val src = flat(4, 4, rgb(10, 200, 30, a = 77))
        val out = PhotoOps.adjust(src, 4, 4, PhotoOps.Adjust(brightness = 0.3f, sharpen = 0.5f, vignette = 0.5f))
        assertTrue(out.all { it ushr 24 == 77 })
    }

    // ---- auto ------------------------------------------------------------------------------

    @Test
    fun autoStretchesAFlatLookingPicture() {
        val src = ramp(64, 4, 80, 150)
        val out = PhotoOps.auto(src, 64, 4)
        assertTrue(light(out[0]) < light(src[0]) - 15)
        assertTrue(light(out[63]) > light(src[63]) + 15)
    }

    @Test
    fun autoBarelyTouchesAGoodPicture() {
        val src = IntArray(256 * 2) { i -> (i % 256).let { rgb(it, it, it) } }
        val out = PhotoOps.auto(src, 256, 2)
        for (i in src.indices) assertTrue("pixel $i", abs(light(out[i]) - light(src[i])) <= 4)
    }

    @Test
    fun autoTakesOutAColourCast() {
        val src = flat(8, 8, rgb(150, 120, 100)).also { it[0] = rgb(0, 0, 0); it[1] = rgb(255, 255, 255) }
        val out = PhotoOps.auto(src, 8, 8)[20]
        assertTrue(red(out) - blue(out) < 50 - 8)
    }

    @Test
    fun autoLeavesAMostlyBlueSceneItsColourAndItsWhites() {
        // sky with some white cloud: the cloud must stay white, not turn yellow
        val src = IntArray(40 * 40) { i -> if (i % 10 == 0) rgb(255, 255, 255) else rgb(40, 90, 160) }
        val out = PhotoOps.auto(src, 40, 40)
        val cloud = out[0]
        assertTrue(abs(red(cloud) - blue(cloud)) <= 2 && red(cloud) >= 250)
        val sky = out[1]
        assertTrue(blue(sky) > green(sky) && green(sky) > red(sky))
    }

    // ---- blur, sharpen, smooth, whiten ------------------------------------------------------

    @Test
    fun blurKeepsFlatColourAndSpreadsASpeck() {
        val colour = rgb(40, 90, 200)
        assertArrayEquals(flat(9, 9, colour), PhotoOps.blur(flat(9, 9, colour), 9, 9, 2))
        val src = flat(15, 15, rgb(0, 0, 0)).also { it[7 * 15 + 7] = rgb(255, 255, 255) }
        val out = PhotoOps.blur(src, 15, 15, 2)
        assertTrue(red(out[7 * 15 + 7]) < 60)
        assertTrue(red(out[7 * 15 + 9]) > 0)
        assertEquals(0, red(out[0]))
        assertArrayEquals(src, PhotoOps.blur(src, 15, 15, 0))
    }

    @Test
    fun sharpenMakesAnEdgeSteeper() {
        val src = IntArray(20 * 3) { i -> if (i % 20 < 10) rgb(100, 100, 100) else rgb(160, 160, 160) }
        val out = PhotoOps.sharpen(src, 20, 3, 1f, radius = 2)
        assertTrue(red(out[20 + 9]) < 100)
        assertTrue(red(out[20 + 10]) > 160)
        assertEquals(100, red(out[20 + 2]))
        assertArrayEquals(flat(6, 6, rgb(9, 9, 9)), PhotoOps.sharpen(flat(6, 6, rgb(9, 9, 9)), 6, 6, 1f))
    }

    @Test
    fun smoothEvensOutFineTextureButKeepsEdges() {
        val w = 240
        val h = 240
        val random = java.util.Random(7)
        // skin-like: gentle speckle on the left half, then a hard edge to something dark
        val src = IntArray(w * h) { i ->
            val x = i % w
            if (x < 120) (180 + random.nextInt(11) - 5).let { rgb(it, it - 30, it - 50) } else rgb(40, 30, 30)
        }
        val out = PhotoOps.smooth(src, w, h)
        fun roughness(pixels: IntArray): Double {
            var sum = 0.0
            for (y in 50 until 190) for (x in 20 until 100) sum += abs(red(pixels[y * w + x]) - red(pixels[y * w + x + 1]))
            return sum
        }
        assertTrue("texture should drop by more than half", roughness(out) < roughness(src) * 0.5)
        // the edge is still an edge
        assertTrue(red(out[120 * w + 117]) - red(out[120 * w + 122]) > 110)
    }

    @Test
    fun whitenTakesOutYellowAndBrightens() {
        val out = PhotoOps.whiten(intArrayOf(rgb(200, 190, 130)))[0]
        assertTrue(red(out) - blue(out) < 40)
        assertTrue(light(out) > light(rgb(200, 190, 130)))
    }

    // ---- heal ------------------------------------------------------------------------------

    @Test
    fun healReplacesASpotWithItsSurroundings() {
        val w = 40
        val h = 40
        val skin = rgb(210, 170, 150)
        val src = flat(w, h, skin)
        val mask = ByteArray(w * h)
        for (y in 15..24) for (x in 15..24) {
            src[y * w + x] = rgb(60, 20, 20)
            mask[y * w + x] = 1
        }
        PhotoOps.heal(src, w, h, mask)
        assertArrayEquals(flat(w, h, skin), src)
    }

    @Test
    fun healFollowsAGradientAcrossABigArea() {
        val w = 200
        val h = 160
        val src = IntArray(w * h) { i -> (i % w).let { rgb(20 + it, 30 + it / 2, 200 - it) } }
        val wanted = src.copyOf()
        val mask = ByteArray(w * h)
        for (y in 30..129) for (x in 40..159) {
            src[y * w + x] = rgb(255, 0, 255)
            mask[y * w + x] = 1
        }
        PhotoOps.heal(src, w, h, mask)
        var worst = 0
        for (i in src.indices) {
            worst = maxOf(worst, abs(red(src[i]) - red(wanted[i])), abs(green(src[i]) - green(wanted[i])), abs(blue(src[i]) - blue(wanted[i])))
            if (mask[i].toInt() == 0) assertEquals(wanted[i], src[i])
        }
        assertTrue("worst difference $worst", worst <= 12)
    }

    @Test
    fun healedPatchPicksUpTheGrainAroundIt() {
        val w = 60
        val h = 60
        val random = java.util.Random(3)
        val src = IntArray(w * h) { (150 + random.nextInt(21) - 10).let { rgb(it, it, it) } }
        val mask = ByteArray(w * h)
        for (y in 20..39) for (x in 20..39) mask[y * w + x] = 1
        PhotoOps.heal(src, w, h, mask)
        var different = 0
        for (y in 22..37) for (x in 22..36) if (src[y * w + x] != src[y * w + x + 1]) different++
        assertTrue("the patch should not be perfectly flat", different > 100)
        for (y in 20..39) for (x in 20..39) assertTrue(abs(red(src[y * w + x]) - 150) < 30)
    }

    @Test
    fun healWithNothingToGoOnChangesNothing() {
        val src = ramp(10, 10)
        val before = src.copyOf()
        PhotoOps.heal(src, 10, 10, ByteArray(100) { 1 })
        assertArrayEquals(before, src)
        PhotoOps.heal(src, 10, 10, ByteArray(100))
        assertArrayEquals(before, src)
    }

    @Test
    fun strokeMaskMarksWhereTheFingerWent() {
        // a single dab: a disc
        val dot = PhotoOps.strokeMask(floatArrayOf(10f, 10f), 3f, 0, 0, 20, 20)
        assertEquals(1, dot[10 * 20 + 10].toInt())
        assertEquals(1, dot[10 * 20 + 13].toInt())
        assertEquals(0, dot[10 * 20 + 14].toInt())
        assertEquals(0, dot[13 * 20 + 13].toInt())
        // a line: everything close to it, nothing further away; the patch may start anywhere in the picture
        val line = PhotoOps.strokeMask(floatArrayOf(105f, 210f, 125f, 210f), 2f, 100, 200, 30, 20)
        assertEquals(1, line[10 * 30 + 15].toInt())
        assertEquals(1, line[12 * 30 + 15].toInt())
        assertEquals(0, line[13 * 30 + 15].toInt())
        assertEquals(1, line[10 * 30 + 3].toInt())
        assertEquals(0, line[10 * 30 + 2].toInt())
        assertEquals(0, line[10 * 30 + 28].toInt())
    }

    // ---- brush -----------------------------------------------------------------------------

    @Test
    fun brushIsStrongestInTheMiddleAndBuildsUp() {
        val w = 40
        val h = 40
        val stride = 44 // rows can be longer than the picture is wide
        val mask = ByteArray(stride * h)
        fun at(x: Int, y: Int) = mask[y * stride + x].toInt() and 0xFF
        PhotoOps.dab(mask, stride, w, h, 20f, 20f, 8f, 0.5f, erase = false)
        assertEquals(128, at(20, 20))
        assertTrue(at(26, 20) in 1 until at(20, 20))
        assertEquals(0, at(29, 20))
        assertEquals(0, at(20, 30))
        repeat(8) { PhotoOps.dab(mask, stride, w, h, 20f, 20f, 8f, 0.5f, erase = false) }
        assertTrue(at(20, 20) >= 253)
        PhotoOps.dab(mask, stride, w, h, 20f, 20f, 8f, 1f, erase = true)
        assertEquals(0, at(20, 20))
        // near the edge of the picture nothing is written outside it
        PhotoOps.dab(mask, stride, w, h, 39f, 0f, 6f, 1f, erase = false)
        for (y in 0 until h) for (x in w until stride) assertEquals(0, at(x, y))
    }

    @Test
    fun brushStrokeHasNoGaps() {
        val w = 120
        val h = 30
        val mask = ByteArray(w * h)
        PhotoOps.dabLine(mask, w, w, h, 10f, 15f, 110f, 15f, 6f, 0.5f, erase = false)
        for (x in 12..110) assertTrue("gap at $x", (mask[15 * w + x].toInt() and 0xFF) > 100)
        assertEquals(0, mask[15 * w + 118].toInt())
    }

    @Test
    fun mixShowsTheEffectOnlyWherePainted() {
        val base = flat(4, 1, rgb(100, 100, 100))
        val effect = flat(4, 1, rgb(200, 0, 50))
        val mask = byteArrayOf(0, 255.toByte(), 128.toByte(), 255.toByte(), 9, 9) // stride 6 > width 4
        val full = base.copyOf()
        PhotoOps.mix(full, effect, mask, 6, 4, 1, 1f)
        assertEquals(rgb(100, 100, 100), full[0])
        assertEquals(rgb(200, 0, 50), full[1])
        assertTrue(red(full[2]) in 148..152 && green(full[2]) in 48..52)
        val half = base.copyOf()
        PhotoOps.mix(half, effect, mask, 6, 4, 1, 0.5f)
        assertTrue(red(half[1]) in 148..152)
        val none = base.copyOf()
        PhotoOps.mix(none, effect, mask, 6, 4, 1, 0f)
        assertArrayEquals(base, none)
    }

    // ---- crop ------------------------------------------------------------------------------

    private fun box(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, b)

    @Test
    fun centredCropFitsTheShape() {
        assertArrayEquals(box(50f, 0f, 150f, 100f), PhotoOps.centredCrop(200f, 100f, 1f), 0.01f)
        assertArrayEquals(box(0f, 25f, 100f, 75f), PhotoOps.centredCrop(100f, 100f, 2f), 0.01f)
        assertArrayEquals(box(0f, 0f, 200f, 100f), PhotoOps.centredCrop(200f, 100f, null), 0.01f)
    }

    @Test
    fun freeCropFollowsTheFingerAndStaysInside() {
        val start = box(100f, 100f, 300f, 300f)
        // the right edge, pulled left
        assertArrayEquals(box(100f, 100f, 250f, 300f), PhotoOps.resizeCrop(start, false, false, true, false, -50f, 0f, 400f, 400f, null, 40f), 0.01f)
        // the top-left corner, pulled far out of the picture
        assertArrayEquals(box(0f, 0f, 300f, 300f), PhotoOps.resizeCrop(start, true, true, false, false, -500f, -500f, 400f, 400f, null, 40f), 0.01f)
        // pushed past the opposite side: stops at the smallest size
        assertArrayEquals(box(260f, 100f, 300f, 300f), PhotoOps.resizeCrop(start, true, false, false, false, 900f, 0f, 400f, 400f, null, 40f), 0.01f)
    }

    @Test
    fun lockedCropKeepsItsShape() {
        val start = box(100f, 100f, 300f, 300f)
        for ((dx, dy) in listOf(80f to 10f, 10f to 80f, -60f to -60f, 500f to 500f, 30f to -90f)) {
            for (corner in 0 until 4) {
                val left = corner % 2 == 0
                val top = corner < 2
                val out = PhotoOps.resizeCrop(start, left, top, !left, !top, dx, dy, 400f, 400f, 1f, 40f)
                assertEquals("square stays square ($dx, $dy, corner $corner)", out[2] - out[0], out[3] - out[1], 0.01f)
                assertTrue(out[0] >= -0.01f && out[1] >= -0.01f && out[2] <= 400.01f && out[3] <= 400.01f)
                // the corner opposite the finger does not move
                assertEquals(if (left) 300f else 100f, if (left) out[2] else out[0], 0.01f)
                assertEquals(if (top) 300f else 100f, if (top) out[3] else out[1], 0.01f)
            }
        }
        // one edge only: the other direction grows around its middle
        val wide = PhotoOps.resizeCrop(box(100f, 150f, 300f, 250f), false, false, true, false, 100f, 0f, 400f, 400f, 2f, 40f)
        assertArrayEquals(box(100f, 125f, 400f, 275f), wide, 0.01f)
        // ... and stops growing when it reaches the picture's edge
        val tall = PhotoOps.resizeCrop(box(100f, 0f, 200f, 50f), false, false, true, false, 300f, 0f, 400f, 100f, 2f, 40f)
        assertEquals(2f, (tall[2] - tall[0]) / (tall[3] - tall[1]), 0.01f)
        assertTrue(tall[1] >= -0.01f && tall[3] <= 100.01f && tall[2] <= 400.01f)
    }

    // ---- zooming and moving the picture --------------------------------------------------------

    @Test
    fun pinchKeepsThePictureUnderTheFingers() {
        // a 2000 x 1000 picture in a 1000 x 1000 area with a margin of 50: it fits at 0.45
        assertEquals(0.45f, PhotoOps.fitScale(1000f, 1000f, 2000, 1000, 50f), 0.0001f)
        val start = floatArrayOf(1f, 0f, 0f)
        // fingers at (700, 500), over picture point 444.4 right of the middle; zoom in three times
        val zoomed = PhotoOps.viewAfter(start, 1000f, 1000f, 2000, 1000, 50f, 700f, 500f, 0f, 0f, 3f)
        assertEquals(3f, zoomed[0], 0.0001f)
        val scale = 0.45f * 3f
        // the same picture point is still under (700, 500)
        assertEquals(700f, 500f + zoomed[1] + (200f / 0.45f) * scale, 0.01f)
        assertEquals(0f, zoomed[2], 0.01f)
        // moving both fingers 40 to the left drags the picture with them
        val dragged = PhotoOps.viewAfter(zoomed, 1000f, 1000f, 2000, 1000, 50f, 700f, 500f, -40f, 0f, 1f)
        assertEquals(zoomed[1] - 40f, dragged[1], 0.01f)
    }

    @Test
    fun zoomHasLimitsAndThePictureStaysOnScreen() {
        val start = floatArrayOf(1f, 0f, 0f)
        // cannot zoom out past "fits", and a picture that fits cannot be moved at all
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), PhotoOps.viewAfter(start, 1000f, 1000f, 2000, 1000, 50f, 500f, 500f, 300f, -200f, 0.2f), 0.001f)
        assertEquals(8f, PhotoOps.viewAfter(start, 1000f, 1000f, 2000, 1000, 50f, 500f, 500f, 0f, 0f, 100f)[0], 0.001f)
        // zoomed in twice: 1800 wide in 900 of room, so it can move 450 either way and no further
        val twice = PhotoOps.viewAfter(start, 1000f, 1000f, 2000, 1000, 50f, 500f, 500f, 0f, 0f, 2f)
        val far = PhotoOps.viewAfter(twice, 1000f, 1000f, 2000, 1000, 50f, 500f, 500f, 5000f, 5000f, 1f)
        assertEquals(450f, far[1], 0.01f)
        assertEquals(0f, far[2], 0.01f) // 900 high in 900 of room: no room to move up or down
    }

    // ---- reshape -----------------------------------------------------------------------------

    @Test
    fun pushMovesWhatIsUnderTheFingerAndNothingFarAway() {
        val columns = 10
        val rows = 8
        val points = PhotoOps.grid(columns, rows, 100f, 80f)
        assertEquals((columns + 1) * (rows + 1) * 2, points.size)
        assertEquals(100f, points[columns * 2], 0.001f)
        assertEquals(80f, points[points.size - 1], 0.001f)
        val home = points.copyOf()
        PhotoOps.push(points, columns, rows, 50f, 40f, 6f, -4f, 25f, 100f, 80f)
        val middle = (4 * (columns + 1) + 5) * 2
        assertEquals(56f, points[middle], 0.001f)
        assertEquals(36f, points[middle + 1], 0.001f)
        // a neighbour moves less than the middle, the far corner not at all
        val neighbour = (4 * (columns + 1) + 6) * 2
        assertTrue(points[neighbour] - home[neighbour] in 0.5f..5.5f)
        assertEquals(home[0], points[0], 0f)
        assertEquals(home[points.size - 2], points[points.size - 2], 0f)
    }

    @Test
    fun pushNeverPullsThePictureEdgeInwards() {
        val columns = 10
        val rows = 8
        val points = PhotoOps.grid(columns, rows, 100f, 80f)
        PhotoOps.push(points, columns, rows, 2f, 40f, 30f, 10f, 40f, 100f, 80f)
        for (row in 0..rows) {
            assertEquals("left edge, row $row", 0f, points[(row * (columns + 1)) * 2], 0f)
            assertEquals("right edge, row $row", 100f, points[(row * (columns + 1) + columns) * 2], 0f)
        }
        for (column in 0..columns) {
            assertEquals(0f, points[column * 2 + 1], 0f)
            assertEquals(80f, points[(rows * (columns + 1) + column) * 2 + 1], 0f)
        }
        // but points on the left edge may slide along it
        val slid = (4 * (columns + 1)) * 2 + 1
        assertTrue(points[slid] > 40f)
    }
}
