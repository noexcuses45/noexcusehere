package com.noexcusehere.mashwave.desktop

import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.desktop.vlc.PlayStatus
import com.noexcusehere.mashwave.iptv.TvAccount
import com.noexcusehere.mashwave.iptv.TvSection
import kotlinx.coroutines.delay
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
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
        waitFor(20) { !desk.lookingForVlc }
        val player = desk.player
        note("vlc: " + if (player != null) "${player.version} in ${desk.vlcFolder}" else "missing - ${desk.vlcProblem}")
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

        var tourOk = true
        if (service.size == 3) tourOk = tour(desk, window)

        val ok = played && pausedOk && tourOk
        note(if (ok) "RESULT: PASSED" else "RESULT: FAILED")
        return if (ok) 0 else 1
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
        snap(window, "3-live")
        if (channels.isEmpty()) return false

        val before = player.frameTick
        desk.playChannel(channels, 0)
        val showing = waitFor(30) { player.frameTick - before >= 20 }
        note("channel: pictures=${player.frameTick - before} status=${player.status} shape=${player.shape} problem=${player.problem}")
        delay(2500)
        snap(window, "4-channel-in-corner")
        desk.watching = true
        delay(1500)
        snap(window, "5-channel-big")
        val stepped = desk.step(1)
        waitFor(20) { player.status == PlayStatus.PLAYING }
        note("next channel: moved=$stepped now=${player.current?.title} status=${player.status}")
        delay(1000)
        snap(window, "6-next-channel")
        desk.watching = false

        desk.tab = Tab.MOVIES
        waitFor(20) { desk.tv.state.value.shelf(TvSection.MOVIES).catalog != null }
        val movies = desk.tv.state.value.shelf(TvSection.MOVIES).catalog?.items.orEmpty()
        note("movies: ${movies.size}")
        delay(2500)
        snap(window, "7-movies")
        movies.firstOrNull()?.let {
            desk.open(it)
            delay(2500)
            snap(window, "8-movie-page")
            desk.page = null
        }

        desk.tab = Tab.SHOWS
        waitFor(20) { desk.tv.state.value.shelf(TvSection.SHOWS).catalog != null }
        val shows = desk.tv.state.value.shelf(TvSection.SHOWS).catalog?.items.orEmpty()
        note("shows: ${shows.size}")
        delay(2000)
        snap(window, "9-shows")
        var episodesOk = true
        shows.firstOrNull()?.let { show ->
            desk.open(show)
            delay(2500)
            snap(window, "10-show-page")
            val details = runCatching { desk.tv.details(show) }.getOrNull()
            val first = details?.episodes?.firstOrNull()
            note("show: episodes=${details?.episodes?.size} seasons=${details?.seasons}")
            if (details != null && first != null) {
                val at = player.frameTick
                desk.playEpisode(show, details, first)
                episodesOk = waitFor(30) { player.frameTick - at >= 20 && player.positionMs > 500 }
                note("episode: pictures=${player.frameTick - at} position=${player.positionMs} length=${player.durationMs} seekable=${player.seekable} queue=${player.queue.size}")
                delay(1500)
                snap(window, "11-episode")
                val wasAt = player.positionMs
                player.seekBy(10_000)
                delay(2500)
                note("skip ahead: from=$wasAt to=${player.positionMs}")
                val title = player.current?.title
                val moved = player.next()
                waitFor(20) { player.status == PlayStatus.PLAYING && player.current?.title != title }
                note("next episode: moved=$moved now=${player.current?.title}")
                delay(1500)
                snap(window, "12-next-episode")
                desk.stopPlaying()
                delay(800)
                note("places kept: ${desk.resume.size}")
            }
            desk.page = null
        }
        desk.tab = Tab.SETTINGS
        delay(1500)
        snap(window, "13-settings")
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
