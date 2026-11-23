package com.noexcusehere.mashwave.desktop

import com.noexcusehere.mashwave.cast.CastWhy
import com.noexcusehere.mashwave.cast.MediaServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Playing on a TV from the computer, tried against a stand-in TV that speaks the way real smart TVs do. */
class DeskCastTest {
    /**
     * A smart TV as far as the app can tell: it describes itself, takes "play this address",
     * and - like a real one - then fetches that address itself.
     */
    private class StandInTv : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val address: String get() = "http://127.0.0.1:${server.localPort}/desc.xml"
        val asked = CopyOnWriteArrayList<String>()

        @Volatile var uri = ""
        @Volatile var metadata = ""
        @Volatile var state = "NO_MEDIA_PRESENT"
        @Volatile var volume = 30
        @Volatile var fetched: ByteArray? = null

        init {
            Thread {
                while (!server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (e: Exception) {
                        break
                    }
                    Thread { runCatching { client.use { answer(it) } } }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        private fun answer(client: java.net.Socket) {
            val input = client.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return
                head.append(b.toChar())
            }
            val length = Regex("""(?i)content-length:\s*(\d+)""").find(head)?.groupValues?.get(1)?.toInt() ?: 0
            val body = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val n = input.read(body, filled, length - filled)
                if (n < 0) break
                filled += n
            }
            val text = String(body, 0, filled, Charsets.UTF_8)
            val reply = if (head.startsWith("GET")) description else act(Regex("""#(\w+)""").find(head)?.groupValues?.get(1).orEmpty(), text)
            val bytes = reply.toByteArray()
            client.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes)
                flush()
            }
        }

        private fun field(text: String, name: String): String =
            Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1).orEmpty()
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

        private fun act(action: String, text: String): String {
            asked += action
            val inner = when (action) {
                "SetAVTransportURI" -> {
                    uri = field(text, "CurrentURI")
                    metadata = field(text, "CurrentURIMetaData")
                    state = "STOPPED"
                    ""
                }
                "Play" -> {
                    state = "PLAYING"
                    val from = uri
                    // A TV fetches what it was told to play.
                    Thread { fetched = runCatching { URL(from).openStream().use { it.readBytes() } }.getOrNull() }.apply { isDaemon = true }.start()
                    ""
                }
                "Pause" -> {
                    state = "PAUSED_PLAYBACK"
                    ""
                }
                "Stop" -> {
                    if (state != "NO_MEDIA_PRESENT") state = "STOPPED"
                    ""
                }
                "GetTransportInfo" -> "<CurrentTransportState>$state</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus>"
                "GetPositionInfo" -> "<Track>1</Track><TrackDuration>0:00:30</TrackDuration><TrackURI>${uri.replace("&", "&amp;")}</TrackURI><RelTime>0:00:04</RelTime>"
                "GetVolume" -> "<CurrentVolume>$volume</CurrentVolume>"
                "SetVolume" -> {
                    volume = field(text, "DesiredVolume").toIntOrNull() ?: volume
                    ""
                }
                "GetProtocolInfo" -> "<Source></Source><Sink>http-get:*:audio/mpeg:*,http-get:*:video/mp4:*</Sink>"
                else -> ""
            }
            return """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                """<u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">$inner</u:${action}Response></s:Body></s:Envelope>"""
        }

        private val description = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
<friendlyName>Stand-in TV</friendlyName><manufacturer>Tests</manufacturer><modelName>One</modelName>
<UDN>uuid:99999999-2222-3333-4444-555555555555</UDN>
<serviceList>
<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><controlURL>/ctl/avt</controlURL><eventSubURL>/evt/avt</eventSubURL><SCPDURL>/avt.xml</SCPDURL></service>
<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId><controlURL>/ctl/rc</controlURL><eventSubURL>/evt/rc</eventSubURL><SCPDURL>/rc.xml</SCPDURL></service>
<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><controlURL>/ctl/cm</controlURL><eventSubURL>/evt/cm</eventSubURL><SCPDURL>/cm.xml</SCPDURL></service>
</serviceList></device></root>"""

        override fun close() {
            runCatching { server.close() }
        }
    }

    private fun waitUntil(seconds: Int, what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(50)
        }
        assertTrue(check(), "waited $seconds s for: $what")
    }

    @Test
    fun aSongOnThisComputerPlaysOnATv() {
        // The TV has to be able to reach this computer; a machine with no network cannot be tried.
        if (MediaServer.localAddress() == null) {
            println("no home network on this machine: playing on a TV is not tried")
            return
        }
        val tv = StandInTv()
        val folder = Files.createTempDirectory("umm-cast").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val desk = Desk(scope, folder, asksForCode = false)
        try {
            val cast = desk.cast
            cast.addByAddress(tv.address)
            waitUntil(10, "the TV to be listed") { cast.devices.isNotEmpty() }
            assertEquals("Stand-in TV", cast.devices.single().name)
            cast.connect(cast.devices.single())
            waitUntil(10, "connecting") { cast.connected }

            // Connected: a song that is started goes to the TV instead of playing here.
            val file = File("selftest/music/Test Album/01 first song.mp3").absoluteFile
            desk.playSongs(listOf(LocalFile(path = file.path, title = "First Song", artist = "Test Band", durationMs = 3000)), 0)
            waitUntil(30, "the TV to fetch the song and say it is playing") { tv.fetched != null && cast.playing }
            // The TV was told what it is, was given an address on this computer, and got the very file.
            assertTrue(tv.uri.startsWith("http://" + MediaServer.localAddress()!!.hostAddress + ":"), "given ${tv.uri}")
            assertTrue(tv.uri.endsWith(".mp3"))
            assertTrue("First Song" in tv.metadata && "audio/mpeg" in tv.metadata, tv.metadata)
            assertContentEquals(file.readBytes(), tv.fetched)
            assertEquals("First Song", cast.entry?.title)
            assertTrue(desk.player?.current == null, "nothing should be playing on the computer itself")

            cast.togglePlay()
            waitUntil(10, "pause") { tv.state == "PAUSED_PLAYBACK" && !cast.playing }
            cast.togglePlay()
            waitUntil(10, "play again") { tv.state == "PLAYING" && cast.playing }
            cast.changeVolume(0.5f)
            waitUntil(10, "the TV's loudness to change") { tv.volume == 50 }

            cast.disconnect()
            waitUntil(10, "the TV to be told to stop") { tv.state == "STOPPED" && !cast.connected }
            assertTrue("SetAVTransportURI" in tv.asked && "Play" in tv.asked && "Pause" in tv.asked && "Stop" in tv.asked)
        } finally {
            desk.shutDown()
            scope.cancel()
            tv.close()
            folder.deleteRecursively()
        }
    }

    @Test
    fun filesAreDescribedToTheTvByWhatTheyReallyAre() {
        assertEquals("audio/mpeg", DeskCast.mimeByName("Song.MP3", false))
        assertEquals("video/x-matroska", DeskCast.mimeByName("film.mkv", true))
        assertEquals("video/mp4", DeskCast.mimeByName("no ending", true))
        assertEquals("audio/mpeg", DeskCast.mimeByName("no ending", false))
        // Named as one thing but really another: the beginning of the file decides.
        val folder = Files.createTempDirectory("umm-kind").toFile()
        try {
            val wrong = File(folder, "really-flac.mp3")
            File("selftest/music/Test Album/02 second song.flac").copyTo(wrong)
            assertEquals("audio/flac", DeskCast.mimeOf(wrong, false))
            assertEquals("audio/mpeg", DeskCast.mimeOf(File("selftest/music/Test Album/01 first song.mp3"), false))
        } finally {
            folder.deleteRecursively()
        }
        // What is said when something will not start is worded for a computer, not a phone.
        val words = DeskCast.wordsFor(CastWhy.NOT_COLLECTED, "Lounge TV", "First Song", null)
        assertTrue("this computer" in words && "phone" !in words && "firewall" in words, words)
        assertTrue("phone" !in DeskCast.wordsFor(CastWhy.NO_WIFI, "Lounge TV", "First Song", null))
    }
}
