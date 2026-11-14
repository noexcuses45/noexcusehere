package com.noexcusehere.mashwave.cast

import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One file the TV may fetch. */
interface ServedSource {
    val mime: String

    /** Size in bytes, or -1 when it cannot be known in advance. */
    val length: Long

    /** Opens the file positioned [offset] bytes in. */
    fun open(offset: Long): InputStream
}

/**
 * A very small web server that lets a TV on the same Wi-Fi fetch the songs and videos being cast.
 * Only files that were explicitly published are reachable, each behind an unguessable address,
 * and TVs can jump around inside a file (needed for seeking).
 */
class MediaServer {
    private var server: ServerSocket? = null
    private var workers: ExecutorService? = null
    private val published = ConcurrentHashMap<String, ServedSource>()
    private val random = SecureRandom()

    val port: Int
        get() = server?.localPort ?: 0

    val isRunning: Boolean
        get() = server?.isClosed == false

    @Synchronized
    fun start(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val socket = ServerSocket(0, 16)
        val pool = Executors.newCachedThreadPool { runnable -> Thread(runnable, "cast-server").apply { isDaemon = true } }
        server = socket
        workers = pool
        pool.execute {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                runCatching { pool.execute { handle(client) } }.onFailure { runCatching { client.close() } }
            }
        }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        workers?.shutdownNow()
        workers = null
        published.clear()
    }

    /** Makes [source] fetchable and returns its path (starting with "/"). [name] only makes the address readable. */
    fun publish(source: ServedSource, name: String): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        published[token] = source
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "media" }
        return "/m/$token/" + URLEncoder.encode(safe, "UTF-8")
    }

    /** Forgets everything published so far (old addresses stop working). */
    fun clear() = published.clear()

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 20_000
            val input = client.getInputStream()
            val output = BufferedOutputStream(client.getOutputStream(), 64 * 1024)
            val request = readHead(input) ?: return
            val lines = request.split("\r\n")
            val parts = lines.first().split(" ")
            if (parts.size < 2) return respond(output, 400, "Bad Request")
            val method = parts[0].uppercase(Locale.ROOT)
            val path = parts[1].substringBefore('?')
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val at = line.indexOf(':')
                if (at > 0) headers[line.substring(0, at).trim().lowercase(Locale.ROOT)] = line.substring(at + 1).trim()
            }
            if (method != "GET" && method != "HEAD") return respond(output, 405, "Method Not Allowed")
            val segments = path.trim('/').split('/')
            val source = if (segments.size >= 2 && segments[0] == "m") published[segments[1]] else null
            if (source == null) return respond(output, 404, "Not Found")
            serve(output, source, headers["range"], method == "HEAD")
        } catch (e: Exception) {
            // The TV hung up (it does this whenever it seeks or stops) - nothing to do.
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readHead(input: InputStream): String? {
        val buffer = StringBuilder()
        var matched = 0
        while (buffer.length < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            buffer.append(b.toChar())
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return buffer.substring(0, buffer.length - 4)
        }
        return null
    }

    private fun serve(output: OutputStream, source: ServedSource, rangeHeader: String?, headOnly: Boolean) {
        val total = source.length
        var start = 0L
        var end = total - 1
        var partial = false
        if (total >= 0 && rangeHeader != null) {
            val range = parseRange(rangeHeader, total)
            if (range == null) {
                val head = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                output.write(head.toByteArray(Charsets.ISO_8859_1))
                output.flush()
                return
            }
            start = range.first
            end = range.second
            partial = true
        }
        val head = StringBuilder()
        head.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: ${source.mime}\r\n")
        if (total >= 0) {
            head.append("Content-Length: ${end - start + 1}\r\n")
            head.append("Accept-Ranges: bytes\r\n")
            if (partial) head.append("Content-Range: bytes $start-$end/$total\r\n")
        }
        // Extra hints many smart TVs look for before they agree to play or seek.
        head.append("transferMode.dlna.org: Streaming\r\n")
        head.append("contentFeatures.dlna.org: $DLNA_FEATURES\r\n")
        head.append("Connection: close\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (headOnly) {
            output.flush()
            return
        }
        source.open(start).use { stream ->
            val buffer = ByteArray(64 * 1024)
            var left = if (total >= 0) end - start + 1 else Long.MAX_VALUE
            while (left > 0) {
                val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) break
                try {
                    output.write(buffer, 0, n)
                } catch (e: SocketException) {
                    return
                }
                left -= n
            }
        }
        output.flush()
    }

    private fun respond(output: OutputStream, code: Int, reason: String) {
        output.write("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    companion object {
        /** "You may seek by position in the file; it is the original, streamed in the background". */
        const val DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

        /** Understands "bytes=a-b", "bytes=a-" and "bytes=-n". Returns first and last byte, or null if impossible. */
        fun parseRange(header: String, total: Long): Pair<Long, Long>? {
            val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash < 0 || total <= 0) return null
            val from = spec.substring(0, dash).trim()
            val to = spec.substring(dash + 1).trim()
            return if (from.isEmpty()) {
                val count = to.toLongOrNull() ?: return null
                if (count <= 0) return null
                (total - count).coerceAtLeast(0) to total - 1
            } else {
                val first = from.toLongOrNull() ?: return null
                if (first >= total) return null
                val last = (to.toLongOrNull() ?: (total - 1)).coerceAtMost(total - 1)
                if (last < first) return null
                first to last
            }
        }

        /** The phone's address on the home network (Wi-Fi first), or null when it is not on one. */
        fun localAddress(): InetAddress? {
            val candidates = ArrayList<Pair<Int, InetAddress>>()
            runCatching {
                for (network in NetworkInterface.getNetworkInterfaces()) {
                    if (!network.isUp || network.isLoopback) continue
                    val name = network.name.lowercase(Locale.ROOT)
                    val rank = when {
                        name.startsWith("wlan") -> 0
                        name.startsWith("eth") -> 1
                        name.startsWith("ap") || name.startsWith("swlan") -> 2
                        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") -> 9
                        else -> 5
                    }
                    for (address in network.inetAddresses) {
                        if (address.isLoopbackAddress || address.hostAddress?.contains(':') == true) continue
                        candidates += (if (address.isSiteLocalAddress) rank else rank + 3) to address
                    }
                }
            }
            return candidates.minByOrNull { it.first }?.takeIf { it.first < 9 }?.second
        }
    }
}
