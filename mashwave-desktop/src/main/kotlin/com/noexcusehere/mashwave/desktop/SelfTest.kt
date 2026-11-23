package com.noexcusehere.mashwave.desktop

import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.desktop.vlc.PlayStatus
import com.noexcusehere.mashwave.desktop.vlc.Repeat
import com.noexcusehere.mashwave.iptv.TvAccount
import com.noexcusehere.mashwave.iptv.TvRef
import com.noexcusehere.mashwave.iptv.TvSection
import kotlinx.coroutines.delay
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.imageio.ImageIO

/**
 * A check the app can run on itself: start, find VLC, play a short video file and note whether
 * pictures arrived, taking snapshots of the window on the way. Used when the app is built for a
 * kind of computer nobody has tried it on by hand. With a TV service given as well, it also
 * walks through the main screens.
 *
 *     --self-test <video file> <folder for the results> [--service <address> <user> <password>]
 */
class SelfTest(private val clip: String, val out: File, private val service: List<String>) {
    private val report = File(out, "result.txt")

    private fun note(line: String) {
        println(line)
        report.appendText(line + System.lineSeparator())
    }

    private fun snap(window: Window, name: String): Int {
        return runCatching {
            val bounds = Rectangle(window.locationOnScreen, window.size)
            val shot = Robot().createScreenCapture(bounds)
            ImageIO.write(shot, "png", File(out, "$name.png"))
            // A small copy too, light enough to be printed into a build log and looked at later.
            val width = 640
            val height = (shot.height * width.toDouble() / shot.width).toInt().coerceAtLeast(1)
            val small = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            small.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(shot, 0, 0, width, height, null)
                dispose()
            }
            ImageIO.write(small, "jpg", File(out, "$name-small.jpg"))
            // How many different colours are on screen: a blank window has a handful, a real one thousands.
            val seen = HashSet<Int>()
            var y = 0
            while (y < shot.height) {
                var x = 0
                while (x < shot.width) {
                    seen += shot.getRGB(x, y)
                    x += 3
                }
                y += 3
            }
            seen.size
        }.onFailure { note("snapshot $name failed: ${it.javaClass.simpleName}") }.getOrDefault(-1)
    }

    private suspend fun waitFor(seconds: Int, check: () -> Boolean): Boolean {
        repeat(seconds * 10) {
            if (check()) return true
            delay(100)
        }
        return check()
    }

    suspend fun run(desk: Desk, window: Window): Int {
        report.writeText("")
        note("os: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, java ${System.getProperty("java.version")}")
        // Unlock codes are tied to the computer; this says whether it could be recognised.
        note("computer: " + if (ThisComputer.usedItsOwn) "recognised by its own name" else "gave no name of its own, so one was made up and kept")
        waitFor(20) { !desk.lookingForVlc }
        val player = desk.player
        note("vlc: " + if (player != null) "${player.version} in ${desk.vlcFolder}" + (if (player.builtIn) " (packed with the app)" else " (installed on this computer)") else "missing - ${desk.vlcProblem}")
        delay(1500)
        note("window colours at start: ${snap(window, "1-start")}")
        if (player == null) {
            note("RESULT: FAILED (no player)")
            return 1
        }

        val before = player.frameTick
        player.play(listOf(MediaEntry(uri = File(clip).absolutePath, title = "Self-test clip", isVideo = true)), 0)
        desk.watching = true
        val played = waitFor(25) { player.frameTick - before >= 20 && player.positionMs > 500 }
        note("clip: pictures=${player.frameTick - before} position=${player.positionMs}ms length=${player.durationMs}ms status=${player.status} shape=${player.shape} problem=${player.problem}")
        note("window colours while playing: ${snap(window, "2-playing")}")
        player.toggle()
        delay(700)
        note("after pause: status=${player.status}")
        val pausedOk = player.status == PlayStatus.PAUSED
        desk.stopPlaying()
        delay(500)

        // The kinds of stream a TV service sends, fetched over the network the way a real one is.
        var streamsOk = true
        val folder = File(clip).absoluteFile.parentFile
        if (File(folder, "live.ts").isFile) {
            val server = runCatching { TinyServer(folder) }.onFailure { note("could not start the test server: ${it.message}") }.getOrNull()
            if (server == null) {
                streamsOk = false
            } else {
                try {
                    val at = "http://127.0.0.1:${server.port}"
                    val channel = stream(desk, window, "tv-stream", "$at/live.ts", live = true, subtitles = false)
                    val film = stream(desk, window, "film", "$at/film.mkv", live = false, subtitles = true)
                    val pieces = stream(desk, window, "stream-in-pieces", "$at/hls/index.m3u8", live = true, subtitles = false)
                    streamsOk = channel && film && pieces
                } finally {
                    server.close()
                }
            }
        }

        // Music kept on the computer: looked through like a music folder, then every kind of file is played.
        var musicOk = true
        val songs = File(folder, "music")
        if (songs.isDirectory) musicOk = music(desk, songs)

        // A mashup: two songs with a steady beat are read in, mixed into one, saved and played.
        var mashupOk = true
        val steady = File(folder, "mashup")
        if (steady.isDirectory) mashupOk = mashup(desk, steady)

        var tourOk = true
        if (service.size == 3) tourOk = tour(desk, window)

        val ok = played && pausedOk && streamsOk && musicOk && mashupOk && tourOk
        note(if (ok) "RESULT: PASSED" else "RESULT: FAILED")
        return if (ok) 0 else 1
    }

    /** Reads the details of the test songs, plays each kind, and lets an album run on by itself. */
    private suspend fun music(desk: Desk, folder: File): Boolean {
        val player = desk.player ?: return false
        val found = desk.library.scan(listOf(folder), emptyMap()).filter { !it.video }.sortedWith(Library.songOrder)
        val first = found.firstOrNull { it.path.endsWith(".mp3") }
        val detailsOk = found.size == 6 && first != null && first.title == "First Song" && first.artist == "Test Band" && first.album == "Test Album" &&
            first.track == 1 && first.durationMs in 2000..4500 && first.cover.isNotEmpty() && File(first.cover).length() > 0
        note(
            "music: found=${found.size} first=\"${first?.title}\" by \"${first?.artist}\" on \"${first?.album}\" track=${first?.track} " +
                "length=${first?.durationMs}ms cover=${first?.cover?.isNotEmpty()} with details=${found.count { it.artist.isNotEmpty() }}",
        )
        var allPlayed = true
        for (song in found) {
            player.repeat = Repeat.OFF
            player.play(listOf(song.entry()), 0, 0L)
            val started = waitFor(12) { player.status == PlayStatus.PLAYING && player.positionMs > 300 }
            val counts = player.counts()
            val sound = (counts?.soundDecoded ?: 0) > 0
            note("song ${File(song.path).extension}: status=${player.status} position=${player.positionMs}ms length=${player.durationMs}ms sound decoded=${counts?.soundDecoded} problem=${player.problem}")
            allPlayed = allPlayed && started && sound
            desk.stopPlaying()
            delay(300)
        }
        // An album plays through: the second song starts when the first one ends.
        val album = found.filter { it.album == "Test Album" }
        desk.playSongs(album, 0)
        val moved = album.size >= 2 && waitFor(15) { player.current?.uri == album[1].path && player.status == PlayStatus.PLAYING }
        note("album: second song started by itself=$moved bar for music=${desk.playingMusic}")
        desk.stopPlaying()
        delay(300)
        return detailsOk && allPlayed && moved
    }

    /** Makes a mashup of the two test songs and checks the result is a song of the right length that plays. */
    private suspend fun mashup(desk: Desk, folder: File): Boolean {
        val player = desk.player ?: return false
        val files = folder.listFiles { f -> MediaKinds.isVideo(f.name) == false }.orEmpty().sortedBy { it.name }
        if (files.size < 2) {
            note("mashup: the test songs are missing")
            return false
        }
        val maker = desk.mashup
        files.take(2).forEach { maker.add(LocalFile(path = it.absolutePath, title = Library.tidyName(it.nameWithoutExtension))) }
        maker.lengthSeconds = 60
        val began = System.currentTimeMillis()
        maker.create()
        val finished = waitFor(300) { !maker.working }
        val made = maker.result
        val file = made?.let { File(it.path) }
        val length = file?.let { Tags.read(it)?.durationMs } ?: 0
        note(
            "mashup: finished=$finished in ${(System.currentTimeMillis() - began) / 1000}s problem=${maker.error} kind=${file?.extension} size=${file?.length()} " +
                "length=${length}ms summary=\"${made?.summary}\" moments=${made?.story?.size}",
        )
        made?.story?.take(3)?.forEach { note("  $it") }
        // Making it also starts it playing.
        val plays = made != null && waitFor(15) { player.current?.uri == made.path && player.status == PlayStatus.PLAYING && player.positionMs > 300 }
        val counts = player.counts()
        note("mashup plays: $plays sound decoded=${counts?.soundDecoded}")
        desk.stopPlaying()
        delay(300)
        // Nothing is left behind in the music folder of whoever ran the check.
        if (made != null) maker.delete(made)
        delay(300)
        return finished && made != null && maker.error == null && file != null && length in 40_000..90_000 && plays
    }

    /** Plays one stream and notes whether pictures and sound came out of it. */
    private suspend fun stream(desk: Desk, window: Window, name: String, address: String, live: Boolean, subtitles: Boolean): Boolean {
        val player = desk.player ?: return false
        val before = player.frameTick
        val tv = if (live) TvRef(TvRef.LIVE, "selftest", name).encode() else ""
        player.play(listOf(MediaEntry(uri = address, title = name, isVideo = true, tv = tv)), 0)
        desk.watching = true
        val seen = waitFor(40) { player.frameTick - before >= 15 }
        if (subtitles) {
            // Switch the first set of subtitles on, so drawing them is tried too.
            waitFor(5) { player.subtitles.any { it.id >= 0 } }
            player.subtitles.firstOrNull { it.id >= 0 }?.let { player.chooseSubtitle(it.id) }
        }
        // Subtitles only show once the part read ahead before they were switched on has played.
        delay(if (subtitles) 4800 else 1800)
        val counts = player.counts()
        val sound = (counts?.soundDecoded ?: 0) > 0
        val titles = !subtitles || player.subtitles.any { it.id >= 0 }
        note(
            "$name: pictures=${player.frameTick - before} shape=${player.shape} sound decoded=${counts?.soundDecoded} played=${counts?.soundPlayed} " +
                "sound tracks=${player.audioTracks.count { it.id >= 0 }} subtitles=${player.subtitles.count { it.id >= 0 }} status=${player.status} problem=${player.problem}",
        )
        snap(window, "3-$name")
        desk.stopPlaying()
        delay(600)
        return seen && sound && titles
    }

    /** Signs in to the given service and visits the main screens. */
    private suspend fun tour(desk: Desk, window: Window): Boolean {
        val player = desk.player ?: return false
        val signedIn = runCatching {
            desk.tv.add(TvAccount(id = "", name = "", type = TvAccount.XTREAM, server = service[0], username = service[1], password = service[2]))
        }.onFailure { note("sign-in failed: ${it.message}") }.isSuccess
        if (!signedIn) return false
        val listed = waitFor(20) { desk.tv.state.value.shelf(TvSection.LIVE).catalog != null }
        val channels = desk.tv.state.value.shelf(TvSection.LIVE).catalog?.items.orEmpty()
        note("live: listed=$listed channels=${channels.size}")
        delay(1500)
        snap(window, "tour-03-live")
        if (channels.isEmpty()) return false

        val before = player.frameTick
        desk.playChannel(channels, 0)
        val showing = waitFor(30) { player.frameTick - before >= 20 }
        note("channel: pictures=${player.frameTick - before} status=${player.status} shape=${player.shape} problem=${player.problem}")
        delay(2500)
        snap(window, "tour-04-channel-in-corner")
        desk.watching = true
        delay(1500)
        snap(window, "tour-05-channel-big")
        val stepped = desk.step(1)
        waitFor(20) { player.status == PlayStatus.PLAYING }
        note("next channel: moved=$stepped now=${player.current?.title} status=${player.status}")
        delay(1000)
        snap(window, "tour-06-next-channel")
        desk.watching = false

        desk.tab = Tab.MOVIES
        waitFor(20) { desk.tv.state.value.shelf(TvSection.MOVIES).catalog != null }
        val movies = desk.tv.state.value.shelf(TvSection.MOVIES).catalog?.items.orEmpty()
        note("movies: ${movies.size}")
        delay(2500)
        snap(window, "tour-07-movies")
        movies.firstOrNull()?.let {
            desk.open(it)
            delay(2500)
            snap(window, "tour-08-movie-page")
            desk.page = null
        }

        desk.tab = Tab.SHOWS
        waitFor(20) { desk.tv.state.value.shelf(TvSection.SHOWS).catalog != null }
        val shows = desk.tv.state.value.shelf(TvSection.SHOWS).catalog?.items.orEmpty()
        note("shows: ${shows.size}")
        delay(2000)
        snap(window, "tour-09-shows")
        var episodesOk = true
        shows.firstOrNull()?.let { show ->
            desk.open(show)
            delay(2500)
            snap(window, "tour-10-show-page")
            val details = runCatching { desk.tv.details(show) }.getOrNull()
            val first = details?.episodes?.firstOrNull()
            note("show: episodes=${details?.episodes?.size} seasons=${details?.seasons}")
            if (details != null && first != null) {
                val at = player.frameTick
                desk.playEpisode(show, details, first)
                episodesOk = waitFor(30) { player.frameTick - at >= 20 && player.positionMs > 500 }
                note("episode: pictures=${player.frameTick - at} position=${player.positionMs} length=${player.durationMs} seekable=${player.seekable} queue=${player.queue.size}")
                delay(1500)
                snap(window, "tour-11-episode")
                val wasAt = player.positionMs
                player.seekBy(10_000)
                delay(2500)
                note("skip ahead: from=$wasAt to=${player.positionMs}")
                val title = player.current?.title
                val moved = player.next()
                waitFor(20) { player.status == PlayStatus.PLAYING && player.current?.title != title }
                note("next episode: moved=$moved now=${player.current?.title}")
                delay(1500)
                snap(window, "tour-12-next-episode")
                desk.stopPlaying()
                delay(800)
                note("places kept: ${desk.resume.size}")
            }
            desk.page = null
        }
        desk.tab = Tab.SETTINGS
        delay(1500)
        snap(window, "tour-13-settings")
        desk.tab = Tab.LIVE
        return showing && episodesOk
    }

    companion object {
        fun from(args: Array<String>): SelfTest? {
            val at = args.indexOf("--self-test")
            if (at < 0 || args.size < at + 3) return null
            val service = args.indexOf("--service").takeIf { it >= 0 && args.size >= it + 4 }?.let { args.slice(it + 1..it + 3) }.orEmpty()
            return SelfTest(args[at + 1], File(args[at + 2]).apply { mkdirs() }, service)
        }
    }
}

/**
 * The smallest possible web server: hands out the files of one folder on this computer only.
 * The self-check uses it so that test clips arrive over the network, as TV streams do.
 */
private class TinyServer(private val folder: File) : AutoCloseable {
    private val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    val port: Int
        get() = socket.localPort

    init {
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                Thread({ runCatching { client.use { answer(it) } } }, "selftest-request").apply { isDaemon = true }.start()
            }
        }, "selftest-server").apply { isDaemon = true }.start()
    }

    private fun answer(client: Socket) {
        client.soTimeout = 15_000
        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val first = input.readLine() ?: return
        var from = 0L
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) from = line.substringAfter("bytes=", "0").substringBefore('-').trim().toLongOrNull() ?: 0L
        }
        val path = first.split(' ').getOrElse(1) { "/" }.substringBefore('?').trimStart('/')
        val file = File(folder, path).canonicalFile
        val out = client.getOutputStream()
        // Only files inside the folder, whatever the address says.
        if (!file.isFile || !file.path.startsWith(folder.canonicalPath + File.separator)) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val size = file.length()
        val start = from.coerceIn(0, size)
        val kind = when (file.extension.lowercase()) {
            "m3u8" -> "application/vnd.apple.mpegurl"
            "ts" -> "video/mp2t"
            "mkv" -> "video/x-matroska"
            else -> "video/mp4"
        }
        val head = StringBuilder()
        head.append(if (start > 0) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: $kind\r\nAccept-Ranges: bytes\r\nContent-Length: ${size - start}\r\n")
        if (start > 0) head.append("Content-Range: bytes $start-${size - 1}/$size\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        file.inputStream().use { stream ->
            stream.skip(start)
            stream.copyTo(out)
        }
        out.flush()
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
