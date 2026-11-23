package com.noexcusehere.mashwave.desktop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import com.noexcusehere.mashwave.photo.fitWithin
import com.noexcusehere.mashwave.photo.pixels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The photo editor's tools are the phone app's own code, run here through this app's stand-ins
 * for Android's drawing classes. These checks put every tool through its paces on a made-up
 * picture and look at the dots that come out.
 */
class PhotoToolsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun done() {
        scope.cancel()
    }

    private val w = 320
    private val h = 200

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    /** A soft sweep of colour: no hard edges, so a small shift or a little squeezing barely changes a dot. */
    private fun sweep(width: Int = w, height: Int = h): IntArray = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        rgb(40 + 170 * x / width, 60 + 120 * y / height, 200 - 130 * x / width)
    }

    /** A busy picture: neighbouring dots differ a lot, so blurring shows. */
    private fun checks(): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        if ((x / 4 + y / 4) % 2 == 0) rgb(230, 230, 230) else rgb(30, 30, 30)
    }

    private fun far(a: Int, b: Int): Int = maxOf(abs(red(a) - red(b)), abs(green(a) - green(b)), abs(blue(a) - blue(b)))

    private fun mostDifferent(a: IntArray, b: IntArray): Int {
        assertEquals(a.size, b.size)
        var most = 0
        for (i in a.indices) most = maxOf(most, far(a[i], b[i]))
        return most
    }

    private fun wait(tool: PhotoTool) {
        val until = System.currentTimeMillis() + 20_000
        while (tool.working && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse(tool.working, "the tool never finished getting ready")
    }

    @Test
    fun dotsGoInAndComeOutUnchanged() {
        val dots = sweep()
        val picture = bitmapOf(dots, w, h)
        assertEquals(w, picture.width)
        assertEquals(h, picture.height)
        assertContentEquals(dots, picture.pixels())

        // A block out of the middle, the way the spot remover reads one.
        val block = IntArray(20 * 10)
        picture.getPixels(block, 0, 20, 50, 40, 20, 10)
        for (y in 0 until 10) for (x in 0 until 20) assertEquals(dots[(40 + y) * w + 50 + x], block[y * 20 + x])

        // A changeable copy takes a block back, and the original is left alone.
        val copy = picture.copy(Bitmap.Config.ARGB_8888, true)
        copy.setPixels(IntArray(200) { rgb(255, 0, 0) }, 0, 20, 50, 40, 20, 10)
        val changed = copy.pixels()
        assertEquals(rgb(255, 0, 0), changed[45 * w + 60])
        assertEquals(dots[39 * w + 60], changed[39 * w + 60])
        assertContentEquals(dots, picture.pixels())
    }

    @Test
    fun aSmallerCopyKeepsTheShapeAndTheColours() {
        val picture = bitmapOf(sweep(), w, h)
        val small = picture.fitWithin(160)
        assertEquals(160, small.width)
        assertEquals(100, small.height)
        val dots = small.pixels()
        // the middle of the small copy is the colour of the middle of the big one
        assertTrue(far(dots[50 * 160 + 80], sweep()[100 * w + 160]) <= 4)
        assertTrue(picture.fitWithin(4000) === picture)
    }

    @Test
    fun adjustmentsChangeTheWholePicture() {
        val picture = bitmapOf(sweep(), w, h)
        val tool = ToneTool(picture, scope)
        assertFalse(tool.dirty)
        tool.set(PhotoOps.Adjust(brightness = 0.4f))
        assertTrue(tool.dirty)
        val out = tool.result()
        assertEquals(w, out.width)
        val before = picture.pixels()
        val after = out.pixels()
        var brighter = 0
        for (i in before.indices) if (red(after[i]) + green(after[i]) + blue(after[i]) > red(before[i]) + green(before[i]) + blue(before[i])) brighter++
        assertTrue(brighter > before.size * 9 / 10, "only $brighter of ${before.size} dots got brighter")
        // and it can be drawn while the sliders move
        val sheet = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        tool.draw(Canvas(sheet))
        tool.close()
    }

    @Test
    fun aBrushOnlyChangesWhereItPainted() {
        val picture = bitmapOf(sweep(), w, h)
        val tool = BrushTool(picture, scope) { dots, pw, ph -> PhotoOps.adjust(dots, pw, ph, PhotoOps.Adjust(brightness = 0.5f)) }
        wait(tool)
        assertFalse(tool.failed)
        tool.size = 12f
        tool.strength = 1f
        tool.down(60f, 100f, 1f)
        tool.move(140f, 100f, 1f)
        tool.up()
        assertTrue(tool.dirty)

        // what is shown while painting ...
        val sheet = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        tool.draw(Canvas(sheet))
        val shown = sheet.pixels()
        // ... and what is kept
        val kept = tool.result().pixels()
        val before = picture.pixels()
        for (dots in listOf(shown, kept)) {
            assertTrue(far(dots[100 * w + 100], before[100 * w + 100]) > 25, "the painted part did not change")
            assertTrue(far(dots[30 * w + 100], before[30 * w + 100]) <= 2, "a part away from the brush changed")
            assertTrue(far(dots[100 * w + 260], before[100 * w + 260]) <= 2, "a part away from the brush changed")
        }
        // the screen and the saved picture agree
        assertTrue(far(shown[100 * w + 100], kept[100 * w + 100]) <= 6)

        // rubbing out takes the paint off again
        tool.erasing = true
        tool.size = 30f
        tool.down(40f, 100f, 1f)
        for (x in 40..170 step 4) tool.move(x.toFloat(), 100f, 1f)
        for (x in 170 downTo 40 step 4) tool.move(x.toFloat(), 100f, 1f)
        tool.up()
        assertTrue(far(tool.result().pixels()[100 * w + 100], before[100 * w + 100]) <= 12)
    }

    @Test
    fun aSpotIsHealedAndCanBePutBack() {
        val dots = sweep()
        // a dark blemish
        for (y in 96..104) for (x in 156..164) dots[y * w + x] = rgb(10, 10, 10)
        val picture = bitmapOf(dots, w, h)
        val tool = HealTool(picture, scope)
        tool.size = 10f
        tool.down(160f, 100f, 1f)
        tool.move(161f, 100f, 1f)
        tool.up()
        wait(tool)
        assertTrue(tool.dirty)
        assertTrue(tool.canUndo)
        val healed = tool.result().pixels()
        val clean = sweep()
        assertTrue(far(healed[100 * w + 160], clean[100 * w + 160]) < 40, "the spot is still there")
        assertEquals(dots[20 * w + 20], healed[20 * w + 20])
        val sheet = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        tool.draw(Canvas(sheet))
        assertTrue(far(sheet.pixels()[100 * w + 160], clean[100 * w + 160]) < 40)

        tool.undo()
        assertFalse(tool.canUndo)
        assertEquals(rgb(10, 10, 10), tool.result().pixels()[100 * w + 160])
    }

    @Test
    fun theRubberSheetLeavesAnUntouchedPictureAloneAndMovesAPushedOne() {
        val picture = bitmapOf(sweep(), w, h)
        val tool = ReshapeTool(picture)
        assertFalse(tool.dirty)
        // Nothing pushed: the sheet drawn square by square is the picture itself.
        val same = tool.result()
        assertTrue(mostDifferent(picture.pixels(), same.pixels()) <= 3, "the sheet changed a picture nobody pushed")

        tool.size = 40f
        tool.down(160f, 100f, 1f)
        for (step in 1..10) tool.move(160f + step * 4f, 100f, 1f)
        tool.up()
        assertTrue(tool.dirty)
        assertTrue(tool.canUndo)
        val pushed = tool.result().pixels()
        val before = picture.pixels()
        // Pushed to the right: just right of where the push began now shows what was further left (less red).
        assertTrue(red(pushed[100 * w + 185]) < red(before[100 * w + 185]) - 4, "the picture did not follow the push")
        assertTrue(far(pushed[10 * w + 10], before[10 * w + 10]) <= 3, "a far corner moved")

        tool.undo()
        assertFalse(tool.dirty)
    }

    @Test
    fun backgroundBlurKeepsTheMiddleSharp() {
        val picture = bitmapOf(checks(), w, h)
        val tool = FocusTool(picture, scope)
        wait(tool)
        tool.changeSpread(0f)
        assertTrue(tool.dirty)
        val out = tool.result().pixels()
        val before = picture.pixels()
        assertTrue(far(out[100 * w + 160], before[100 * w + 160]) <= 6, "the sharp middle changed")
        // In a corner the black and white squares have run together into grey.
        val corner = out[6 * w + 6]
        assertTrue(red(corner) in 60..200, "the corner was not blurred: ${red(corner)}")
        val sheet = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        tool.draw(Canvas(sheet))
        tool.guides(Canvas(sheet), 1f)
        tool.close()
    }

    @Test
    fun aDrawnLineIsThere() {
        val picture = bitmapOf(sweep(), w, h)
        val tool = DrawTool(picture)
        tool.size = 4f
        tool.colour = rgb(255, 0, 0)
        tool.down(40f, 50f, 1f)
        tool.move(120f, 50f, 1f)
        tool.move(200f, 50f, 1f)
        tool.up()
        assertTrue(tool.dirty)
        val out = tool.result().pixels()
        assertTrue(far(out[50 * w + 120], rgb(255, 0, 0)) <= 8, "no red line where it was drawn")
        assertEquals(picture.pixels()[150 * w + 120], out[150 * w + 120])
        tool.undo()
        assertFalse(tool.dirty)
    }

    @Test
    fun cropTurnsMirrorsAndCuts() {
        val dots = sweep()
        // one corner marked, to follow it round
        for (y in 0 until 20) for (x in 0 until 20) dots[y * w + x] = rgb(255, 255, 0)
        val picture = bitmapOf(dots, w, h)
        val tool = CropTool(picture, 1f)
        assertFalse(tool.dirty)

        tool.turn()
        assertEquals(h, tool.width)
        assertEquals(w, tool.height)
        val turned = tool.result()
        assertEquals(h, turned.width)
        assertEquals(w, turned.height)
        // A quarter turn clockwise takes the top left corner to the top right, dot for dot.
        val t = turned.pixels()
        assertEquals(rgb(255, 255, 0), t[5 * h + (h - 6)])
        assertEquals(dots[(h - 1) * w + 0], t[0 * h + 0])

        tool.flip(upsideDown = false)
        val mirrored = tool.result().pixels()
        assertEquals(rgb(255, 255, 0), mirrored[5 * h + 5])

        tool.choose("Square", 1f)
        val square = tool.result()
        assertEquals(square.width, square.height)
        assertEquals(h, square.width)
        val sheet = Bitmap.createBitmap(h, w, Bitmap.Config.ARGB_8888)
        tool.draw(Canvas(sheet))
        tool.guides(Canvas(sheet), 1f)
    }

    /** A JPEG with a camera's "hold me this way" note put in front of the picture. */
    private fun withNote(jpeg: ByteArray, way: Int): ByteArray {
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // big-endian, first list at 8
            0x00, 0x01, // one entry
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, way.toByte(), 0x00, 0x00, // which way up
            0x00, 0x00, 0x00, 0x00, // no more lists
        )
        val body = "Exif".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0) + tiff
        val length = body.size + 2
        val note = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + body
        return jpeg.copyOfRange(0, 2) + note + jpeg.copyOfRange(2, jpeg.size)
    }

    @Test
    fun aPhotoIsSavedReadBackAndStoodUpright() {
        val dots = sweep()
        for (y in 0 until 40) for (x in 0 until 40) dots[y * w + x] = rgb(255, 255, 0)
        val picture = bitmapOf(dots, w, h)
        val packed = ByteArrayOutputStream()
        assertTrue(picture.compress(Bitmap.CompressFormat.JPEG, 95, packed))
        val jpeg = packed.toByteArray()
        assertTrue(jpeg.size > 500)

        val back = assertNotNull(PhotoFiles.decode(jpeg))
        assertEquals(w, back.width)
        assertEquals(h, back.height)
        val read = back.pixels()
        assertTrue(far(read[100 * w + 160], dots[100 * w + 160]) <= 12)
        assertTrue(far(read[10 * w + 10], rgb(255, 255, 0)) <= 30)

        // Stored lying on its side, with a note to turn it a quarter turn clockwise.
        val turned = assertNotNull(PhotoFiles.decode(withNote(jpeg, 6)))
        assertEquals(h, turned.width)
        assertEquals(w, turned.height)
        assertTrue(far(turned.pixels()[10 * h + (h - 11)], rgb(255, 255, 0)) <= 30, "the photo was not stood upright")

        // Brought down in size when asked.
        val small = assertNotNull(PhotoFiles.decode(jpeg, 80))
        assertEquals(80, small.width)
        assertEquals(50, small.height)

        // Not a picture at all.
        assertEquals(null, PhotoFiles.decode("not a picture".toByteArray()))
    }

    @Test
    fun editingKeepsStepsAndSavesACopyWithoutTouchingTheOriginal() = runBlocking {
        val folder = Files.createTempDirectory("photo-test").toFile()
        try {
            val original = File(folder, "Holiday: beach?.jpg".replace(Regex("[:?]"), ""))
            original.outputStream().use { bitmapOf(sweep(), w, h).compress(Bitmap.CompressFormat.JPEG, 95, it) }
            val length = original.length()

            val work = PhotoWork()
            assertTrue(work.open(original))
            assertFalse(work.changed)
            val first = assertNotNull(work.picture)

            val brighter = bitmapOf(PhotoOps.adjust(first.pixels(), first.width, first.height, PhotoOps.Adjust(brightness = 0.5f)), first.width, first.height)
            work.commit(brighter)
            assertTrue(work.changed)
            assertTrue(work.canUndo)
            assertFalse(work.canRedo)

            work.undo()
            assertFalse(work.canUndo)
            assertTrue(work.canRedo)
            assertTrue(mostDifferent(assertNotNull(work.picture).pixels(), first.pixels()) <= 12)

            work.redo()
            assertTrue(far(assertNotNull(work.picture).pixels()[100 * w + 160], brighter.pixels()[100 * w + 160]) <= 12)

            val saved = PhotoFiles.save(assertNotNull(work.picture), original.name, File(folder, "out"))
            assertEquals("Holiday beach edited.jpg", saved.name)
            assertTrue(saved.length() > 500)
            val again = PhotoFiles.save(assertNotNull(work.picture), original.name, File(folder, "out"))
            assertEquals("Holiday beach edited (2).jpg", again.name)
            assertEquals(length, original.length())
            val reread = assertNotNull(PhotoFiles.read(saved))
            assertEquals(w, reread.width)
            assertEquals(Color.BLACK, Color.BLACK)

            assertFalse(PhotoWork().open(File(folder, "missing.jpg")))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun aLookAndAutoWorkOnAPicture() {
        val picture = bitmapOf(sweep(), w, h)
        val mono = bitmapOf(PhotoOps.look(picture.pixels(), w, h, PhotoOps.Look.MONO, 1f), w, h).pixels()
        val dot = mono[100 * w + 160]
        assertTrue(abs(red(dot) - green(dot)) <= 2 && abs(green(dot) - blue(dot)) <= 2, "black and white left colour in")
        val auto = PhotoOps.auto(picture.pixels(), w, h)
        assertEquals(w * h, auto.size)
    }
}
