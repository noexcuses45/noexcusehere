package com.noexcusehere.mashwave.cast

import com.noexcusehere.mashwave.iptv.TvNet
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.SocketException
import java.util.Base64

/**
 * Passes a stream from the internet through the phone to a TV on the home network.
 *
 * The TV only ever sees an address on the phone, so the login inside the real address never
 * leaves it; the phone follows the service's forwarding, and adds the hints TVs and Chromecasts
 * want. Streams made of a list of short pieces (HLS) have the list rewritten so every piece
 * comes through the phone as well.
 */
class StreamRelay(
    private val address: String,
    private val live: Boolean,
    private val mime: String,
    /** The phone-side address of this relay, e.g. "http://192.168.1.5:40123/m/abc123". */
    private val self: () -> String,
) : Responder {
    /** Size of the file behind [address], once an answer has told us. */
    @Volatile
    private var knownLength = -1L

    @Volatile
    private var rangesWork = false

    override fun respond(method: String, rest: String, query: String, headers: Map<String, String>, output: OutputStream) {
        val wrapped = parameter(query, "u")
        val target = if (wrapped != null) unwrap(wrapped) ?: return plain(output, 400, "Bad Request") else address
        val isMain = wrapped == null
        val headOnly = method == "HEAD"

        // Answering "what is this?" must not use up one of the service's connections.
        if (headOnly && isMain && (live || knownLength >= 0)) {
            val head = StringBuilder("HTTP/1.1 200 OK\r\n")
            head.append("Content-Type: $mime\r\n")
            if (!live) {
                head.append("Content-Length: $knownLength\r\n")
                if (rangesWork) head.append("Accept-Ranges: bytes\r\n")
            }
            finish(head, isMain)
            output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            output.flush()
            return
        }

        val request = HashMap<String, String>()
        // Ask for the bytes as they are, so sizes and positions stay true.
        request["Accept-Encoding"] = "identity"
        val range = headers["range"]
        if (range != null && !(live && isMain)) request["Range"] = range

        val upstream = try {
            TvNet.open(target, request, connectMs = 12_000, readMs = 30_000, check = false)
        } catch (e: Exception) {
            return plain(output, 502, "Bad Gateway")
        }
        upstream.use { response ->
            val code = response.code
            if (code !in 200..299) return plain(output, if (code in 400..599) code else 502, "Upstream Error")
            val type = response.header("Content-Type")
            val input = BufferedInputStream(response.stream, 64 * 1024)
            input.mark(64)
            val first = ByteArray(16)
            val got = input.read(first).coerceAtLeast(0)
            input.reset()

            if (Hls.isPlaylist(type, response.url, String(first, 0, got, Charsets.ISO_8859_1))) {
                val text = readText(input, 4 * 1024 * 1024)
                val body = Hls.rewrite(text, response.url) { self() + "/x?u=" + wrap(it) }.toByteArray(Charsets.UTF_8)
                val head = StringBuilder("HTTP/1.1 200 OK\r\n")
                head.append("Content-Type: application/vnd.apple.mpegurl\r\n")
                head.append("Content-Length: ${body.size}\r\n")
                head.append("Cache-Control: no-cache\r\n")
                finish(head, false)
                output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                if (!headOnly) output.write(body)
                output.flush()
                return
            }

            val length = response.connection.contentLengthLong
            val contentRange = response.header("Content-Range")
            val acceptsRanges = code == 206 || response.header("Accept-Ranges").equals("bytes", ignoreCase = true)
            if (isMain && !live) {
                val total = contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: if (code == 200) length else -1
                if (total > 0) knownLength = total
                rangesWork = acceptsRanges
            }
            val head = StringBuilder(if (code == 206) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            head.append("Content-Type: ").append(if (isMain) mime else type ?: "application/octet-stream").append("\r\n")
            if (!(live && isMain)) {
                if (length >= 0) head.append("Content-Length: $length\r\n")
                if (contentRange != null) head.append("Content-Range: $contentRange\r\n")
                if (acceptsRanges) head.append("Accept-Ranges: bytes\r\n")
            }
            finish(head, isMain)
            output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (headOnly) {
                output.flush()
                return
            }
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    // A live channel trickles in; hand each piece on at once rather than waiting to fill a buffer.
                    if (live) output.flush()
                }
                output.flush()
            } catch (e: SocketException) {
                // The TV stopped listening (channel changed, or it is seeking).
            }
        }
    }

    private fun finish(head: StringBuilder, dlnaHints: Boolean) {
        if (dlnaHints) {
            head.append("transferMode.dlna.org: Streaming\r\n")
            head.append("contentFeatures.dlna.org: ").append(if (live) MediaServer.DLNA_LIVE_FEATURES else MediaServer.DLNA_FEATURES).append("\r\n")
        }
        // Chromecasts fetch stream pieces from a web page, which needs this permission slip.
        head.append("Access-Control-Allow-Origin: *\r\n")
        head.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
        head.append("Connection: close\r\n\r\n")
    }

    private fun plain(output: OutputStream, code: Int, reason: String) {
        output.write("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun readText(input: BufferedInputStream, limit: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }

    companion object {
        fun wrap(url: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray(Charsets.UTF_8))

        fun unwrap(text: String): String? = runCatching { String(Base64.getUrlDecoder().decode(text), Charsets.UTF_8) }
            .getOrNull()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }

        fun parameter(query: String, name: String): String? {
            for (pair in query.split('&')) {
                val at = pair.indexOf('=')
                if (at > 0 && pair.substring(0, at) == name) return pair.substring(at + 1)
            }
            return null
        }

        /** What kind of stream an address is, in the words TVs understand. [accepted] is what the TV says it takes. */
        fun mimeFor(address: String, live: Boolean, accepted: Set<String> = emptySet()): String {
            val path = address.substringBefore('?').lowercase()
            val extension = path.substringAfterLast('/').substringAfterLast('.', "")
            return when (extension) {
                "m3u8" -> "application/x-mpegURL"
                "mp4", "m4v", "mov" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "avi" -> "video/x-msvideo"
                "webm" -> "video/webm"
                "flv" -> "video/x-flv"
                "wmv" -> "video/x-ms-wmv"
                "mpg", "mpeg" -> "video/mpeg"
                "ts" -> broadcastMime(accepted)
                else -> if (live) broadcastMime(accepted) else "video/mp4"
            }
        }

        /** Broadcast streams go by three names; use the one this TV lists, if it lists any. */
        private fun broadcastMime(accepted: Set<String>): String =
            listOf("video/mp2t", "video/vnd.dlna.mpeg-tts", "video/mpeg").firstOrNull { it in accepted } ?: "video/mp2t"
    }
}
