package com.noexcusehere.mashwave

import com.noexcusehere.mashwave.cast.Dlna
import com.noexcusehere.mashwave.cast.DlnaException
import com.noexcusehere.mashwave.cast.MediaServer
import com.noexcusehere.mashwave.cast.ServedSource
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Collections
import kotlin.concurrent.thread

class CastTest {
    private val server = MediaServer()
    private val closers = ArrayList<() -> Unit>()

    @After
    fun tearDown() {
        server.stop()
        closers.forEach { runCatching { it() } }
    }

    private class Bytes(private val data: ByteArray, override val mime: String = "audio/mpeg", private val known: Boolean = true) : ServedSource {
        override val length: Long get() = if (known) data.size.toLong() else -1
        override fun open(offset: Long): InputStream = ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
    }

    private val song = ByteArray(200_000) { (it * 31 % 251).toByte() }

    private fun get(path: String, range: String? = null, method: String = "GET"): Triple<Int, Map<String, String>, ByteArray> {
        val connection = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        if (range != null) connection.setRequestProperty("Range", range)
        val code = connection.responseCode
        val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
        val body = (if (code < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        connection.disconnect()
        return Triple(code, headers, body)
    }

    // ---- the phone's media server ----

    @Test
    fun servesTheWholeFile() {
        server.start()
        val path = server.publish(Bytes(song), "My Song (live).mp3")
        assertTrue(path.startsWith("/m/"))
        val (code, headers, body) = get(path)
        assertEquals(200, code)
        assertEquals("audio/mpeg", headers["content-type"])
        assertEquals("bytes", headers["accept-ranges"])
        assertEquals(song.size.toString(), headers["content-length"])
        assertNotNull(headers["contentfeatures.dlna.org"])
        assertArrayEquals(song, body)
    }

    @Test
    fun servesPartsSoTheTvCanSeek() {
        server.start()
        val path = server.publish(Bytes(song), "a.mp3")
        val (code, headers, body) = get(path, "bytes=1000-1999")
        assertEquals(206, code)
        assertEquals("bytes 1000-1999/${song.size}", headers["content-range"])
        assertArrayEquals(song.copyOfRange(1000, 2000), body)

        val (code2, headers2, tail) = get(path, "bytes=199000-")
        assertEquals(206, code2)
        assertEquals("bytes 199000-199999/${song.size}", headers2["content-range"])
        assertArrayEquals(song.copyOfRange(199_000, 200_000), tail)

        val (_, _, last) = get(path, "bytes=-10")
        assertArrayEquals(song.copyOfRange(song.size - 10, song.size), last)

        assertEquals(416, get(path, "bytes=900000-").first)
    }

    @Test
    fun headAnswersWithoutTheFile() {
        server.start()
        val path = server.publish(Bytes(song, "video/mp4"), "clip.mp4")
        val (code, headers, body) = get(path, method = "HEAD")
        assertEquals(200, code)
        assertEquals("video/mp4", headers["content-type"])
        assertEquals(song.size.toString(), headers["content-length"])
        assertEquals(0, body.size)
    }

    @Test
    fun onlyPublishedFilesAreReachable() {
        server.start()
        val path = server.publish(Bytes(song), "a.mp3")
        assertEquals(404, get("/m/0000/a.mp3").first)
        assertEquals(404, get("/etc/passwd").first)
        assertEquals(405, get(path, method = "POST").first)
        server.clear()
        assertEquals(404, get(path).first)
    }

    @Test
    fun unknownLengthIsSentUntilTheEnd() {
        server.start()
        val path = server.publish(Bytes(song, known = false), "stream.mp3")
        val (code, headers, body) = get(path, "bytes=5-")
        assertEquals(200, code)
        assertNull(headers["content-length"])
        assertArrayEquals(song, body)
    }

    @Test
    fun rangeHeaders() {
        assertEquals(0L to 99L, MediaServer.parseRange("bytes=0-99", 1000))
        assertEquals(500L to 999L, MediaServer.parseRange("bytes=500-", 1000))
        assertEquals(990L to 999L, MediaServer.parseRange("bytes=-10", 1000))
        assertEquals(0L to 999L, MediaServer.parseRange("bytes=0-5000", 1000))
        assertNull(MediaServer.parseRange("bytes=1000-", 1000))
        assertNull(MediaServer.parseRange("bytes=9-3", 1000))
        assertNull(MediaServer.parseRange("nonsense", 1000))
    }

    @Test
    fun fileTypeFollowsTheFileNotTheLabel() {
        fun head(text: String, offset: Int = 0) = ByteArray(16).also { b -> text.forEachIndexed { i, c -> b[offset + i] = c.code.toByte() } }
        val refine = com.noexcusehere.mashwave.cast.CastController.Companion::refineMime
        // An .m4a that the phone calls MP3.
        assertEquals("audio/mp4", refine("audio/mpeg", head("ftypmp42", 4), 16, false))
        assertEquals("video/mp4", refine("application/octet-stream", head("ftypisom", 4), 16, true))
        // Labels that already fit are kept exactly.
        assertEquals("video/quicktime", refine("video/quicktime", head("ftypqt  ", 4), 16, true))
        assertEquals("audio/x-m4a", refine("audio/x-m4a", head("ftypM4A ", 4), 16, false))
        assertEquals("audio/flac", refine("audio/mpeg", head("fLaC"), 16, false))
        assertEquals("audio/mpeg", refine("audio/mp4", head("ID3"), 16, false))
        assertEquals("audio/ogg", refine("audio/mpeg", head("OggS"), 16, false))
        val wave = head("RIFF").also { b -> "WAVE".forEachIndexed { i, c -> b[8 + i] = c.code.toByte() } }
        assertEquals("audio/wav", refine("audio/mpeg", wave, 16, false))
        val mkv = ByteArray(16).also { it[0] = 0x1A; it[1] = 0x45; it[2] = 0xDF.toByte(); it[3] = 0xA3.toByte() }
        assertEquals("video/x-matroska", refine("video/mp4", mkv, 16, true))
        assertEquals("video/webm", refine("video/webm", mkv, 16, true))
        // Unknown beginnings and unreadable files leave the label alone.
        assertEquals("audio/mpeg", refine("audio/mpeg", ByteArray(16), 16, false))
        assertEquals("audio/mpeg", refine("audio/mpeg", head("fLaC"), 3, false))
    }

    // ---- smart-TV (DLNA) control ----

    private val description = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Living Room &amp; Den TV</friendlyName>
            <manufacturer>Acme</manufacturer>
            <modelName>Vision 55</modelName>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/upnp/control/rc</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>upnp/control/avt</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun readsATvDescription() {
        val tv = Dlna.parseDescription(description, "http://192.168.1.20:9197/dmr/desc.xml")!!
        assertEquals("Living Room & Den TV", tv.name)
        assertEquals("Acme", tv.maker)
        assertEquals("Vision 55", tv.model)
        assertEquals("http://192.168.1.20:9197/dmr/upnp/control/avt", tv.transportUrl)
        assertEquals("http://192.168.1.20:9197/upnp/control/rc", tv.renderingUrl)
    }

    @Test
    fun honoursABaseAddressAndSkipsNonPlayers() {
        val based = description.replace("<device>", "<URLBase>http://10.0.0.5:8080/</URLBase><device>")
        assertEquals("http://10.0.0.5:8080/upnp/control/avt", Dlna.parseDescription(based, "http://10.0.0.5:1/x.xml")!!.transportUrl)
        val router = description.replace("AVTransport", "WANIPConnection")
        assertNull(Dlna.parseDescription(router, "http://10.0.0.1/x.xml"))
    }

    @Test
    fun readsDiscoveryAnswers() {
        val answer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.20:9197/dmr\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertEquals("http://192.168.1.20:9197/dmr", Dlna.parseSsdp(answer)["location"])
    }

    @Test
    fun times() {
        assertEquals("0:01:23", Dlna.formatTime(83_500))
        assertEquals("1:00:00", Dlna.formatTime(3_600_000))
        assertEquals(83_500, Dlna.parseTime("0:01:23.500"))
        assertEquals(3_723_000, Dlna.parseTime("01:02:03"))
        assertEquals(0, Dlna.parseTime("NOT_IMPLEMENTED"))
        assertEquals(0, Dlna.parseTime(null))
    }

    @Test
    fun labelIsValidAndEscaped() {
        val label = Dlna.metadata("http://1.2.3.4:5/m/x/a.mp3?a=1&b=2", "Rock & <Roll>", "AC/DC \"live\"", "audio/mpeg", false, 83_000, null)
        assertTrue(label.contains("Rock &amp; &lt;Roll&gt;"))
        assertTrue(label.contains("a=1&amp;b=2"))
        assertTrue(label.contains("object.item.audioItem.musicTrack"))
        assertTrue(label.contains("duration=\"0:01:23\""))
        // It must be well-formed, or TVs reject it.
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(label.byteInputStream())
        assertTrue(Dlna.metadata("u", "t", "", "video/mp4", true, 0, null).contains("object.item.videoItem"))
    }

    /** A pretend TV: remembers each command and answers the way real ones do. */
    private class FakeTv {
        val socket = ServerSocket(0)
        val commands: MutableList<Pair<String, String>> = Collections.synchronizedList(ArrayList())
        var state = "STOPPED"
        var failPlay = 0

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true) { runCatching { handle(client) }; runCatching { client.close() } }
                }
            }
        }

        private fun handle(client: Socket) {
            val input = client.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return
                head.append(b.toChar())
            }
            val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) read += input.read(body, read, length - read).also { if (it < 0) return }
            val action = Regex("(?i)soapaction: *\"[^#]*#([A-Za-z]+)\"").find(head)?.groupValues?.get(1).orEmpty()
            commands += action to String(body, Charsets.UTF_8)
            var code = "200 OK"
            val inner = when (action) {
                "SetAVTransportURI" -> { state = "STOPPED"; "" }
                "Play" -> if (failPlay > 0) { failPlay--; code = "500 Internal Server Error"; "" } else { state = "PLAYING"; "" }
                "Pause" -> { state = "PAUSED_PLAYBACK"; "" }
                "Stop" -> { state = "STOPPED"; "" }
                "GetTransportInfo" -> "<CurrentTransportState>$state</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus>"
                "GetPositionInfo" -> "<Track>1</Track><TrackDuration>0:03:20</TrackDuration><TrackURI>http://x/a.mp3</TrackURI><RelTime>0:00:42</RelTime>"
                "GetVolume" -> "<CurrentVolume>37</CurrentVolume>"
                else -> ""
            }
            val reply = if (code.startsWith("200")) {
                "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
                    "<u:${action}Response xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">$inner</u:${action}Response></s:Body></s:Envelope>"
            } else {
                "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><detail>" +
                    "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>701</errorCode><errorDescription>Transition not available</errorDescription>" +
                    "</UPnPError></detail></s:Fault></s:Body></s:Envelope>"
            }.toByteArray()
            client.getOutputStream().write(("HTTP/1.1 $code\r\nContent-Type: text/xml\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n").toByteArray())
            client.getOutputStream().write(reply)
            client.getOutputStream().flush()
        }
    }

    @Test
    fun drivesATv() {
        val tv = FakeTv()
        closers += { tv.socket.close() }
        val base = "http://127.0.0.1:${tv.socket.localPort}"
        val device = Dlna.parseDescription(description, "$base/desc.xml")!!

        val url = "http://192.168.1.7:4000/m/abc/Song%20One.mp3"
        Dlna.setUri(device, url, Dlna.metadata(url, "Song <One>", "Me & You", "audio/mpeg", false, 200_000, null))
        Dlna.play(device)
        assertEquals("PLAYING", Dlna.transportState(device))
        val position = Dlna.position(device)
        assertEquals(42_000, position.positionMs)
        assertEquals(200_000, position.durationMs)
        assertEquals(37, Dlna.volume(device))
        Dlna.seek(device, 95_000)
        Dlna.setVolume(device, 140)
        Dlna.pause(device)
        assertEquals("PAUSED_PLAYBACK", Dlna.transportState(device))

        val names = tv.commands.map { it.first }
        assertEquals(listOf("SetAVTransportURI", "Play", "GetTransportInfo", "GetPositionInfo", "GetVolume", "Seek", "SetVolume", "Pause", "GetTransportInfo"), names)
        val set = tv.commands[0].second
        assertTrue(set.contains("<CurrentURI>$url</CurrentURI>"))
        // The label travels inside the command, so it is escaped a second time.
        assertTrue(set.contains("&lt;dc:title&gt;Song &amp;lt;One&amp;gt;&lt;/dc:title&gt;"))
        assertTrue(tv.commands[5].second.contains("<Unit>REL_TIME</Unit><Target>0:01:35</Target>"))
        assertTrue(tv.commands[6].second.contains("<DesiredVolume>100</DesiredVolume>"))
    }

    @Test
    fun reportsWhyATvRefused() {
        val tv = FakeTv()
        closers += { tv.socket.close() }
        val device = Dlna.parseDescription(description, "http://127.0.0.1:${tv.socket.localPort}/desc.xml")!!
        tv.failPlay = 1
        try {
            Dlna.play(device)
            fail("expected a refusal")
        } catch (e: DlnaException) {
            assertEquals(701, e.code)
            assertEquals("Transition not available", e.message)
        }
        Dlna.play(device)
        assertEquals("PLAYING", tv.state)
    }
}
