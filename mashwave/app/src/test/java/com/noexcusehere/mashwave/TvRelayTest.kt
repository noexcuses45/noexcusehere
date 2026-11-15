package com.noexcusehere.mashwave

import com.noexcusehere.mashwave.cast.Dlna
import com.noexcusehere.mashwave.cast.MediaServer
import com.noexcusehere.mashwave.cast.StreamRelay
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends real requests through the phone-side pass-through to a pretend TV service running in
 * the test, the way a TV would: whole files, pieces of files, forwarded addresses, and streams
 * made of a list of short pieces.
 */
class TvRelayTest {
    /** A few lines of web server standing in for the TV service. */
    private class Reply(val code: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0), val sized: Boolean = true)

    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    private val server = MediaServer()
    private val movie = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val gets = AtomicInteger()
    private val heads = AtomicInteger()
    private val servicePort = listener.localPort

    private fun answer(method: String, path: String, headers: Map<String, String>): Reply {
        val plain = path.substringBefore('?')
        fun count() = if (method == "HEAD") heads.incrementAndGet() else gets.incrementAndGet()
        return when (plain) {
            "/movie/u/p/7.mkv" -> {
                count()
                val range = headers["range"]
                if (range != null) {
                    val (first, last) = MediaServer.parseRange(range, movie.size.toLong())!!
                    Reply(
                        206,
                        mapOf("Content-Type" to "application/octet-stream", "Accept-Ranges" to "bytes", "Content-Range" to "bytes $first-$last/${movie.size}"),
                        movie.copyOfRange(first.toInt(), last.toInt() + 1),
                    )
                } else {
                    Reply(200, mapOf("Content-Type" to "application/octet-stream", "Accept-Ranges" to "bytes"), movie)
                }
            }
            // The login server forwards to another machine, as real services do.
            "/live/u/p/1.ts" -> Reply(302, mapOf("Location" to "/balancer/1.ts?token=abc"))
            "/balancer/1.ts" -> {
                count()
                // No size given: it simply keeps coming until the line is closed.
                Reply(200, mapOf("Content-Type" to "video/mp2t"), ByteArray(50 * 1880) { 0x47 }, sized = false)
            }
            "/live/u/p/2.m3u8" -> Reply(
                200,
                mapOf("Content-Type" to "application/vnd.apple.mpegurl"),
                "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\n/hls/2/seg-10.ts\n#EXTINF:6.0,\nseg-11.ts\n".toByteArray(),
            )
            "/hls/2/seg-10.ts" -> Reply(200, mapOf("Content-Type" to "video/mp2t"), ByteArray(376) { 0x47 })
            "/gone" -> Reply(403)
            else -> Reply(404)
        }
    }

    @Before
    fun start() {
        workers.execute {
            while (!listener.isClosed) {
                val client = try {
                    listener.accept()
                } catch (e: Exception) {
                    break
                }
                workers.execute {
                    client.use { socket ->
                        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val first = reader.readLine() ?: return@use
                        val headers = HashMap<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
                        }
                        val parts = first.split(' ')
                        val reply = answer(parts[0], parts[1], headers)
                        val out = socket.getOutputStream()
                        val head = StringBuilder("HTTP/1.1 ${reply.code} X\r\n")
                        reply.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
                        if (reply.sized) head.append("Content-Length: ${reply.body.size}\r\n")
                        head.append("Connection: close\r\n\r\n")
                        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                        if (parts[0] != "HEAD") out.write(reply.body)
                        out.flush()
                    }
                }
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop()
        listener.close()
        workers.shutdownNow()
    }

    private fun publish(path: String, live: Boolean, mime: String): String {
        var base = ""
        val relay = StreamRelay("http://127.0.0.1:$servicePort$path", live, mime) { base }
        val published = server.publish(relay, "stream")
        base = "http://127.0.0.1:${server.port}" + published.substringBeforeLast('/')
        return "http://127.0.0.1:${server.port}$published"
    }

    private fun open(url: String, method: String = "GET", range: String? = null): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        if (range != null) connection.setRequestProperty("Range", range)
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        return connection
    }

    @Test
    fun aMovieComesThroughWholeAndInPieces() {
        val url = publish("/movie/u/p/7.mkv", live = false, mime = "video/x-matroska")
        assertFalse("the TV must never be handed the login", url.contains("/u/p/"))

        val whole = open(url)
        assertEquals(200, whole.responseCode)
        assertEquals("video/x-matroska", whole.contentType)
        assertEquals(movie.size.toLong(), whole.contentLengthLong)
        assertTrue(whole.getHeaderField("contentFeatures.dlna.org").contains("DLNA.ORG_OP=01"))
        assertArrayEquals(movie, whole.inputStream.readBytes())

        val piece = open(url, range = "bytes=1000-1999")
        assertEquals(206, piece.responseCode)
        assertEquals("bytes 1000-1999/${movie.size}", piece.getHeaderField("Content-Range"))
        assertEquals("bytes", piece.getHeaderField("Accept-Ranges"))
        assertArrayEquals(movie.copyOfRange(1000, 2000), piece.inputStream.readBytes())

        val tail = open(url, range = "bytes=199000-")
        assertEquals(206, tail.responseCode)
        assertArrayEquals(movie.copyOfRange(199000, movie.size), tail.inputStream.readBytes())

        // Once the size is known, "what is this?" is answered without bothering the service again.
        val before = gets.get() + heads.get()
        val head = open(url, method = "HEAD")
        assertEquals(200, head.responseCode)
        assertEquals(movie.size.toLong(), head.contentLengthLong)
        assertEquals(before, gets.get() + heads.get())
    }

    @Test
    fun aLiveChannelFollowsTheForwardingAndIsMarkedAsLive() {
        val url = publish("/live/u/p/1.ts", live = true, mime = "video/mp2t")
        val head = open(url, method = "HEAD")
        assertEquals(200, head.responseCode)
        assertEquals("video/mp2t", head.contentType)
        assertEquals("asking about a channel must not open a stream", 0, gets.get() + heads.get())

        val stream = open(url)
        assertEquals(200, stream.responseCode)
        assertEquals("video/mp2t", stream.contentType)
        assertTrue(stream.getHeaderField("contentFeatures.dlna.org").contains("DLNA.ORG_OP=00"))
        assertEquals("*", stream.getHeaderField("Access-Control-Allow-Origin"))
        val bytes = stream.inputStream.readBytes()
        assertEquals(50 * 1880, bytes.size)
        assertTrue(bytes.all { it == 0x47.toByte() })
        assertEquals(1, gets.get())
    }

    @Test
    fun aStreamMadeOfPiecesHasEveryPieceRoutedThroughThePhone() {
        val url = publish("/live/u/p/2.m3u8", live = true, mime = "application/x-mpegURL")
        val list = open(url)
        assertEquals(200, list.responseCode)
        assertTrue(list.contentType.contains("mpegurl"))
        assertEquals("*", list.getHeaderField("Access-Control-Allow-Origin"))
        val lines = list.inputStream.readBytes().decodeToString().lines().filter { it.isNotBlank() }
        val pieces = lines.filterNot { it.startsWith("#") }
        assertEquals(2, pieces.size)
        val self = "http://127.0.0.1:${server.port}/m/"
        assertTrue(pieces.all { it.startsWith(self) && "/x?u=" in it })
        assertEquals("http://127.0.0.1:$servicePort/hls/2/seg-10.ts", StreamRelay.unwrap(pieces[0].substringAfter("u=")))
        assertEquals("http://127.0.0.1:$servicePort/live/u/p/seg-11.ts", StreamRelay.unwrap(pieces[1].substringAfter("u=")))

        val piece = open(pieces[0])
        assertEquals(200, piece.responseCode)
        assertEquals("video/mp2t", piece.contentType)
        assertEquals(376, piece.inputStream.readBytes().size)
    }

    @Test
    fun refusalsAndUnknownAddressesAreReportedNotHidden() {
        assertEquals(403, open(publish("/gone", live = false, mime = "video/mp4")).responseCode)
        assertEquals(404, open("http://127.0.0.1:${server.port}/m/notatoken/x").responseCode)
        // Only web addresses may be fetched through the phone.
        val url = publish("/movie/u/p/7.mkv", live = false, mime = "video/mp4")
        val base = url.substringBeforeLast('/')
        assertEquals(400, open(base + "/x?u=" + StreamRelay.wrap("file:///etc/passwd")).responseCode)
        assertEquals(204, open(url, method = "OPTIONS").responseCode)
    }

    @Test
    fun namesTheStreamTheWayTheTvExpects() {
        assertEquals("video/mp2t", StreamRelay.mimeFor("http://h/live/u/p/1.ts", live = true))
        assertEquals("video/mpeg", StreamRelay.mimeFor("http://h/live/u/p/1.ts", live = true, accepted = setOf("video/mpeg", "video/mp4")))
        assertEquals("video/vnd.dlna.mpeg-tts", StreamRelay.mimeFor("http://h/play/abc", live = true, accepted = setOf("video/vnd.dlna.mpeg-tts", "video/mpeg")))
        assertEquals("application/x-mpegURL", StreamRelay.mimeFor("http://h/live/u/p/1.m3u8?x=1", live = true))
        assertEquals("video/x-matroska", StreamRelay.mimeFor("http://h/movie/u/p/1.MKV", live = false))
        assertEquals("video/mp4", StreamRelay.mimeFor("http://h/movie/u/p/1", live = false))
        assertEquals(
            setOf("video/mp4", "audio/mpeg", "video/mp2t"),
            Dlna.parseSinkTypes("http-get:*:video/mp4:*,http-get:*:audio/mpeg:DLNA.ORG_PN=MP3, http-get:*:video/MP2T:*,rtsp-rtp-udp:*:audio/x:*,junk"),
        )
        assertNull(StreamRelay.unwrap("!!!"))
        assertEquals("v", StreamRelay.parameter("a=1&u=v&b=2", "u"))
        assertNull(StreamRelay.parameter("a=1", "u"))
    }
}
