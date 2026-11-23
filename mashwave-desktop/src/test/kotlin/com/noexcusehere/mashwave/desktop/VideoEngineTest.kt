package com.noexcusehere.mashwave.desktop

import android.graphics.Bitmap
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.desktop.video.Ffmpeg
import com.noexcusehere.mashwave.desktop.video.RenderJob
import com.noexcusehere.mashwave.desktop.video.RenderPlan
import com.noexcusehere.mashwave.desktop.video.TextPictures
import com.noexcusehere.mashwave.desktop.video.VideoMaths
import com.noexcusehere.mashwave.editor.EditClip
import com.noexcusehere.mashwave.editor.EditMusic
import com.noexcusehere.mashwave.editor.EditProject
import com.noexcusehere.mashwave.editor.EditText
import com.noexcusehere.mashwave.editor.EditorOps
import com.noexcusehere.mashwave.photo.bitmapOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The video editor's engine: the sums that need no engine, and - where one is installed - real videos made and looked at. */
class VideoEngineTest {
    private fun clip(id: String, uri: String, endMs: Long, image: Boolean = false, w: Int = 640, h: Int = 360) =
        EditClip(id = id, uri = uri, isImage = image, sourceDurationMs = if (image) 0 else endMs, startMs = 0, endMs = endMs, width = w, height = h)

    @Test
    fun theFinishedSizeFollowsTheShapeAndIsEven() {
        val wide = EditProject(id = "p", clips = listOf(clip("a", "a.mp4", 1000)))
        assertEquals(640 to 360, VideoMaths.outputSize(wide, 360))
        assertEquals(1920 to 1080, VideoMaths.outputSize(wide.copy(ratio = "16:9"), 1080))
        assertEquals(720 to 1280, VideoMaths.outputSize(wide.copy(ratio = "9:16"), 720))
        assertEquals(720 to 720, VideoMaths.outputSize(wide.copy(ratio = "1:1"), 720))
        val (w, h) = VideoMaths.outputSize(wide.copy(ratio = "4:5"), 721)
        assertTrue(w % 2 == 0 && h % 2 == 0)
    }

    @Test
    fun coloursDoWhatTheirNamesSay() {
        val plain = clip("a", "a.mp4", 1000)
        assertTrue(VideoMaths.changesNothing(VideoMaths.colour(plain)))

        // Black and white: red, green and blue of a dot all come out the same, whatever went in.
        val grey = VideoMaths.colour(plain.copy(filter = "MONO"))
        for (col in 0..3) {
            assertEquals(grey[col], grey[4 + col], 0.0001f)
            assertEquals(grey[col], grey[8 + col], 0.0001f)
        }
        // White stays white.
        assertEquals(1f, grey[0] + grey[1] + grey[2] + grey[3], 0.001f)

        // Brighter adds the same to every colour; more contrast keeps mid-grey where it is.
        val bright = VideoMaths.colour(plain.copy(brightness = 1f))
        assertEquals(0.35f, bright[3], 0.001f)
        assertEquals(0.35f, bright[11], 0.001f)
        val punchy = VideoMaths.colour(plain.copy(contrast = 0.5f))
        assertTrue(punchy[0] > 1.2f)
        assertEquals(0.5f, punchy[0] * 0.5f + punchy[3], 0.001f)

        // Warm has more red than blue.
        val warm = VideoMaths.colour(plain.copy(filter = "WARM"))
        assertTrue(warm[0] > warm[10])
    }

    @Test
    fun theInstructionsNameEveryClipTheCaptionsAndTheSong() {
        val project = EditProject(
            id = "p",
            clips = listOf(
                clip("a", "first.mp4", 4000).copy(startMs = 1000, endMs = 3000, speed = 2f, volume = 0.5f),
                clip("b", "tall.mp4", 2000, w = 360, h = 640).copy(rotation = 90, mirrored = true, filter = "NOIR"),
                clip("c", "photo.jpg", 2000, image = true, w = 800, h = 600),
            ),
            music = EditMusic(MediaEntry(uri = "song.m4a", title = "Song", durationMs = 10_000), offsetMs = 2000, volume = 0.6f),
            texts = listOf(EditText(id = "t", text = "Hello", startMs = 500, endMs = 2500)),
            ratio = "16:9",
            background = "BLUR",
        )
        assertEquals(5000, project.durationMs)
        val texts = listOf(com.noexcusehere.mashwave.desktop.video.TextPicture("text.png", 10, 20, 500, 2500))
        val job = RenderPlan.whole(project, 640, 360, texts, "libopenh264", false, "out.mp4", silent = setOf("tall.mp4"))
        assertEquals(5000, job.totalMs)
        val line = job.args.joinToString(" ")
        // each clip is read from its own cut
        assertTrue(line.contains("-ss 1 -t 2 -i first.mp4"), line)
        assertTrue(line.contains("-ss 0 -t 2 -i tall.mp4"), line)
        // a photo is read once and held for as long as it shows
        assertTrue(line.contains(" -i photo.jpg"), line)
        assertTrue(line.contains("loop=loop=-1:size=1:start=0,setpts=N/(30*TB),fps=30,trim=duration=2"), line)
        // twice the speed: the picture's clock is halved and the sound is sped up without changing pitch
        assertTrue(line.contains("setpts=(PTS-STARTPTS)/2"), line)
        assertTrue(line.contains("atempo=2"), line)
        assertTrue(line.contains("volume=0.5"), line)
        // the clip with no sound of its own, and the photo, get silence
        assertEquals(2, Regex("anullsrc").findAll(line).count())
        // mirrored, then turned, then made black and white
        assertTrue(line.contains("hflip,transpose=1,format=rgba,colorchannelmixer="), line)
        // a soft copy behind clips of another shape
        assertTrue(line.contains("gblur"), line)
        assertTrue(line.contains("concat=n=3:v=1:a=1"), line)
        assertTrue(line.contains("overlay=x=10:y=20:enable='between(t,0.5,2.5)'"), line)
        // the song: from two seconds in, then round again, under everything
        assertTrue(line.contains("-ss 2 -i song.m4a -stream_loop -1 -i song.m4a"), line)
        assertTrue(line.contains("amix=inputs=2"), line)
        assertTrue(line.contains("-c:v libopenh264"), line)
        assertTrue(line.endsWith("-t 5 -y out.mp4"), line)

        // One frame from inside the second clip.
        val still = assertNotNull(RenderPlan.still(project, 1500, 640, 360, emptyList())).joinToString(" ")
        assertTrue(still.contains("-ss 0.5 -i tall.mp4"), still)
        assertTrue(still.contains("-frames:v 1"), still)
        // Very slow sound is done in steps the engine allows.
        val slow = RenderPlan.whole(project.copy(clips = listOf(project.clips[0].copy(speed = 0.2f))), 640, 360, emptyList(), "mpeg4", true, "o.mp4", emptySet())
        assertTrue(slow.args.joinToString(" ").contains("atempo=0.5,atempo=0.5,atempo=0.8"))
    }

    @Test
    fun realVideosComeOutRight() = runBlocking {
        // Only where a video engine is installed on the computer running the checks.
        val engine = Ffmpeg.find() ?: return@runBlocking
        val folder = Files.createTempDirectory("video-test").toFile()
        try {
            suspend fun make(vararg args: String) {
                val problem = engine.render(RenderJob(listOf("-hide_banner", "-nostdin", "-loglevel", "error", "-progress", "pipe:1") + args, 0)) { }
                assertNull(problem, problem)
            }
            val first = File(folder, "first.mp4")
            val tall = File(folder, "tall.mp4")
            val song = File(folder, "song.m4a")
            val photo = File(folder, "photo.jpg")
            make("-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30", "-f", "lavfi", "-i", "sine=frequency=440", "-t", "4", "-c:v", "mpeg4", "-q:v", "5", "-c:a", "aac", "-y", first.path)
            // a tall clip with no sound at all
            make("-f", "lavfi", "-i", "testsrc=size=360x640:rate=25", "-t", "3", "-c:v", "mpeg4", "-q:v", "5", "-y", tall.path)
            make("-f", "lavfi", "-i", "sine=frequency=220", "-t", "3", "-c:a", "aac", "-y", song.path)
            photo.outputStream().use { bitmapOf(IntArray(800 * 600) { 0xFF2060C0.toInt() }, 800, 600).compress(Bitmap.CompressFormat.JPEG, 90, it) }

            val firstFacts = assertNotNull(engine.facts(first))
            assertEquals(640, firstFacts.width)
            assertEquals(360, firstFacts.height)
            assertTrue(firstFacts.hasPicture && firstFacts.hasSound)
            assertTrue(abs(firstFacts.durationMs - 4000) < 200, "length ${firstFacts.durationMs}")
            val tallFacts = assertNotNull(engine.facts(tall))
            assertTrue(tallFacts.hasPicture && !tallFacts.hasSound)
            val songFacts = assertNotNull(engine.facts(song))
            assertTrue(!songFacts.hasPicture && songFacts.hasSound)
            val photoFacts = assertNotNull(engine.facts(photo))
            assertEquals(800, photoFacts.width)
            assertNull(engine.facts(File(folder, "missing.mp4")))

            var project = EditProject(id = "p", ratio = "16:9", background = "BLUR")
            project = EditorOps.addClips(
                project,
                listOf(
                    EditClip(id = "a", uri = first.path, sourceDurationMs = 4000, startMs = 1000, endMs = 3000, speed = 2f, width = 640, height = 360),
                    EditClip(id = "b", uri = tall.path, sourceDurationMs = 3000, startMs = 0, endMs = 2000, rotation = 90, mirrored = true, filter = "NOIR", width = 360, height = 640),
                    EditClip(id = "c", uri = photo.path, isImage = true, startMs = 0, endMs = 2000, width = 800, height = 600, brightness = 0.3f),
                ),
            )
            project = project.copy(
                music = EditMusic(MediaEntry(uri = song.path, title = "Song", durationMs = songFacts.durationMs), offsetMs = 1000, volume = 0.6f),
                texts = listOf(EditText(id = "t", text = "Hello there 😀", startMs = 500, endMs = 4000)),
            )
            assertEquals(5000, project.durationMs)
            val (w, h) = VideoMaths.outputSize(project, 360)
            val texts = TextPictures.make(project, w, h, File(folder, "texts"))
            assertEquals(1, texts.size)
            assertTrue(File(texts[0].file).length() > 100)

            // Every way of packing the picture this engine has must give a video of the right size and length, with sound.
            for (encoder in engine.videoEncoders()) {
                val out = File(folder, "out-$encoder.mp4")
                var furthest = 0f
                val problem = engine.render(RenderPlan.whole(project, w, h, texts, encoder, false, out.path, silent = setOf(tall.path))) { furthest = maxOf(furthest, it) }
                if (problem != null && encoder.startsWith("h264_")) continue // a chip this computer may not have
                assertNull(problem, "$encoder: $problem")
                val made = assertNotNull(engine.facts(out), encoder)
                assertEquals(w, made.width, encoder)
                assertEquals(h, made.height, encoder)
                assertTrue(made.hasPicture && made.hasSound, encoder)
                assertTrue(abs(made.durationMs - 5000) < 250, "$encoder: length ${made.durationMs}")
                assertTrue(furthest > 0.5f, "$encoder: no word of progress")
            }

            // One frame, as the editor shows it under the playhead: a JPEG of the right size.
            for (at in listOf(200L, 1500L, 4000L)) {
                val showing = TextPictures.make(project, w, h, File(folder, "texts")) { at >= it.startMs && at < it.endMs }
                val bytes = assertNotNull(engine.picture(assertNotNull(RenderPlan.still(project, at, w, h, showing))), "frame at $at")
                val frame = assertNotNull(PhotoFiles.decode(bytes))
                assertEquals(w, frame.width)
                assertEquals(h, frame.height)
            }
        } finally {
            folder.deleteRecursively()
        }
    }
}
