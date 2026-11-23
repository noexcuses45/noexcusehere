package com.noexcusehere.mashwave.desktop

import com.noexcusehere.mashwave.data.ResumePoint
import com.noexcusehere.mashwave.desktop.ui.clock
import com.noexcusehere.mashwave.desktop.ui.length
import com.noexcusehere.mashwave.desktop.vlc.VlcPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopTest {
    /** The app ships only the icons its screens name; a missing one would stop that screen from opening. */
    @Test
    fun everyIconTheScreensUseIsPacked() {
        val mention = Regex("""Icons\.(AutoMirrored\.)?Rounded\.(\w+)""")
        val names = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            mention.findAll(file.readText()).map { found ->
                "androidx.compose.material.icons." + (if (found.groupValues[1].isEmpty()) "" else "automirrored.") + "rounded." + found.groupValues[2] + "Kt"
            }
        }.toSet()
        assertTrue(names.size > 20, "the screens' icons were not found in the source")
        val missing = names.filter { runCatching { Class.forName(it) }.isFailure }
        assertEquals(emptyList(), missing)
    }

    /** The list of player parts packed with the app: well formed, and nothing with a licence that forbids it. */
    @Test
    fun onlyAllowedPlayerPartsArePacked() {
        val lines = File("engine/plugins.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(lines.size > 60, "the list of player parts looks too short")
        assertEquals(lines.size, lines.toSet().size, "a player part is listed twice")
        assertEquals(emptyList(), lines.filterNot { Regex("[a-z0-9_]+/[a-z0-9_]+").matches(it) })
        // Parts of VLC that are under the GPL (or pull in a GPL library) and must not ship in a closed app.
        val forbidden = listOf("x264", "x265", "a52", "dca", "dts_", "faad", "mpeg2", "postproc", "dvdnav", "dvdread", "sid", "qt", "skins", "zvbi", "samplerate", "realrtsp", "vcd", "cdda", "goom")
        val names = lines.map { it.substringAfter('/') }
        assertEquals(emptyList(), names.filter { name -> forbidden.any { name == it.trimEnd('_') || name.startsWith(it) } })
    }

    @Test
    fun timesReadTheWayPeopleSayThem() {
        assertEquals("0:00", clock(0))
        assertEquals("4:05", clock(245_000))
        assertEquals("1:02:03", clock(3_723_000))
        assertEquals("0:00", clock(-5))
        assertEquals("", length(0))
        assertEquals("45 min", length(45 * 60))
        assertEquals("2 h", length(2 * 3600))
        assertEquals("1 h 45 min", length(105 * 60))
    }

    @Test
    fun pictureShapeComesFromItsSizeAndItsDots() {
        // Square dots: the shape is simply width over height.
        assertTrue(abs(VlcPlayer.shapeOf(1920, 1080, 1, 1) - 16f / 9f) < 0.001f)
        // Nothing said about the dots counts as square.
        assertTrue(abs(VlcPlayer.shapeOf(1280, 720, 0, 0) - 16f / 9f) < 0.001f)
        // Standard-definition TV: 720 dots across, each a little wider than tall, shown as 16:9.
        assertTrue(abs(VlcPlayer.shapeOf(720, 576, 64, 45) - 16f / 9f) < 0.001f)
        // Unknown size, or a nonsense stretch, is not believed.
        assertEquals(0f, VlcPlayer.shapeOf(0, 576, 1, 1))
        assertTrue(abs(VlcPlayer.shapeOf(720, 576, 1000, 1) - 1.25f) < 0.001f)
    }

    @Test
    fun narrowTvSizesAreGuessedWide() {
        assertTrue(abs(720f / 576f * VlcPlayer.guessPixelShape(720, 576) - 16f / 9f) < 0.001f)
        assertTrue(abs(1440f / 1080f * VlcPlayer.guessPixelShape(1440, 1080) - 16f / 9f) < 0.001f)
        assertEquals(1f, VlcPlayer.guessPixelShape(1920, 1080))
        assertEquals(1f, VlcPlayer.guessPixelShape(640, 360))
    }

    @Test
    fun settingsSurviveAClose() {
        val dir = Files.createTempDirectory("umm-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = DeskStore(dir, scope)
            first.edit { it.copy(volume = 35, muted = true, look = "FILL", resume = mapOf("http://example/1.mp4" to ResumePoint(90_000, 600_000, 5))) }
            first.flush()
            val second = DeskStore(dir, scope)
            assertEquals(35, second.saved.volume)
            assertEquals(true, second.saved.muted)
            assertEquals("FILL", second.saved.look)
            assertEquals(90_000, second.saved.resume["http://example/1.mp4"]?.positionMs)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun brokenSettingsFileStartsFresh() {
        val dir = Files.createTempDirectory("umm-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            File(dir, "desktop.json").writeText("{ not json")
            assertEquals(DeskSaved(), DeskStore(dir, scope).saved)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun selfTestIsOnlyAskedForOnPurpose() {
        assertEquals(null, SelfTest.from(arrayOf()))
        assertEquals(null, SelfTest.from(arrayOf("--self-test", "clip.mp4")))
        val out = Files.createTempDirectory("umm-selftest").toFile()
        try {
            assertEquals(out.absolutePath, SelfTest.from(arrayOf("--self-test", "clip.mp4", out.absolutePath))?.out?.absolutePath)
        } finally {
            out.deleteRecursively()
        }
    }

    /** Windows gives the computer's own number in a line of a longer answer; this is how it is picked out. */
    @Test
    fun theComputersOwnNameIsPickedOutOfWhatWindowsSays() {
        val answer = "\r\nHKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Cryptography\r\n    MachineGuid    REG_SZ    1b2c3d4e-aaaa-bbbb-cccc-0123456789ab\r\n\r\n"
        assertEquals("1b2c3d4e-aaaa-bbbb-cccc-0123456789ab", ThisComputer.lineAfter(answer, "REG_SZ"))
        assertEquals(null, ThisComputer.lineAfter("ERROR: The system was unable to find the specified registry key or value.", "REG_SZ"))
        assertEquals(null, ThisComputer.lineAfter(null, "REG_SZ"))
        // Whatever the computer, there is always a name, and it is the same the next time.
        val folder = Files.createTempDirectory("umm-name").toFile()
        try {
            val first = ThisComputer.name(folder)
            assertTrue(first.length >= 8)
            assertEquals(first, ThisComputer.name(folder))
        } finally {
            folder.deleteRecursively()
        }
        assertTrue(ThisComputer.platform() in setOf("windows", "mac", "linux"))
    }

    /** The self-check plays its clips without a code; a normal start asks for one only when there is a code service. */
    @Test
    fun theSelfCheckIsNeverAskedForACode() {
        val folder = Files.createTempDirectory("umm-lock").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val checking = Desk(scope, folder, asksForCode = false)
            assertEquals(com.noexcusehere.mashwave.licence.Lock.NOT_NEEDED, checking.licence.state.value)
            checking.shutDown()
            val normal = Desk(scope, folder)
            val expected = if (com.noexcusehere.mashwave.licence.LicenceConfig.ENDPOINT.isEmpty()) com.noexcusehere.mashwave.licence.Lock.NOT_NEEDED else com.noexcusehere.mashwave.licence.Lock.LOCKED
            assertEquals(expected, normal.licence.state.value)
            normal.shutDown()
        } finally {
            scope.cancel()
            folder.deleteRecursively()
        }
    }

    /** The music library: which files count, what is read from them, and the order they are shown in. */
    @Test
    fun musicIsFoundAndItsDetailsRead() {
        val folder = Files.createTempDirectory("umm-library").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val library = Library(folder, scope)
            val music = listOf(File("selftest/music"))
            val found = kotlinx.coroutines.runBlocking { library.scan(music, emptyMap()) }
            // Six songs; the text file beside them is not music.
            assertEquals(6, found.size)
            assertTrue(found.none { it.video })
            val first = found.first { it.path.endsWith(".mp3") }
            assertEquals("First Song", first.title)
            assertEquals("Test Band", first.artist)
            assertEquals("Test Album", first.album)
            assertEquals(1, first.track)
            assertTrue(first.durationMs in 2000..4500, "length read as ${first.durationMs}")
            // The picture inside the song was put among the app's own files.
            assertTrue(File(first.cover).isFile && File(first.cover).length() > 0)
            assertEquals("Second Song", found.first { it.path.endsWith(".flac") }.title)
            assertEquals(3, found.first { it.path.endsWith(".m4a") }.track)
            assertEquals("Someone Else", found.first { it.path.endsWith(".ogg") }.artist)
            // A file that says nothing about itself goes by its name, tidied up.
            assertEquals("no details here", found.first { it.path.endsWith(".opus") }.title)
            // Shown like a record collection: by artist, album, then the order on the album; the nameless last.
            assertEquals(
                listOf("Loose Song", "First Song", "Second Song", "Third Song", "no details here", "voice note"),
                found.sortedWith(Library.songOrder).map { it.title },
            )
            // Looking again does not read unchanged files a second time.
            val again = kotlinx.coroutines.runBlocking { library.scan(music, found.associateBy { it.path }) }
            assertTrue(again.size == found.size && again.zip(found).all { (now, before) -> now === before })
            // What the player is handed for a song.
            val entry = first.entry()
            assertTrue(entry.isSong)
            assertEquals(first.path, entry.uri)
        } finally {
            scope.cancel()
            folder.deleteRecursively()
        }
    }

    @Test
    fun filesAreSortedIntoMusicVideoAndNeither() {
        assertEquals(false, MediaKinds.isVideo("Song.MP3"))
        assertEquals(false, MediaKinds.isVideo("a.b.flac"))
        assertEquals(true, MediaKinds.isVideo("Holiday.mkv"))
        assertEquals(true, MediaKinds.isVideo("clip.MP4"))
        assertEquals(null, MediaKinds.isVideo("notes.txt"))
        assertEquals(null, MediaKinds.isVideo("no ending"))
        assertEquals("01 my song", Library.tidyName("01_my__song"))
        val song = LocalFile(path = "/m/x.mp3", title = "Paper Boats", artist = "Mira Vale", album = "Singles")
        assertTrue(com.noexcusehere.mashwave.desktop.ui.songMatches(song, "vale paper"))
        assertTrue(com.noexcusehere.mashwave.desktop.ui.songMatches(song, ""))
        assertTrue(!com.noexcusehere.mashwave.desktop.ui.songMatches(song, "paper planes"))
        // A song starts at its beginning; a video on this computer is not a song.
        assertTrue(!LocalFile(path = "/v/x.mkv", video = true, title = "x").entry().isSong)
    }
}
