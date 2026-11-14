package com.noexcusehere.mashwave.cast

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A TV, speaker or streaming box on the home network that accepts "play this address" commands
 * the standard way (UPnP / DLNA). Most smart TVs do, and so do receiver apps for Fire TV.
 */
data class DlnaRenderer(
    /** Address of the description this was read from; doubles as its identity. */
    val location: String,
    val name: String,
    val maker: String,
    val model: String,
    /** Where playback commands go. */
    val transportUrl: String,
    /** Where volume commands go, when the device offers them. */
    val renderingUrl: String?,
)

data class DlnaPosition(val positionMs: Long, val durationMs: Long, val uri: String)

class DlnaException(message: String, val code: Int = 0) : Exception(message)

object Dlna {
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    private const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    // ---- finding devices -------------------------------------------------------------------

    /**
     * Shouts "any media players out there?" on the local network and reports the address of each
     * answer's description as it arrives. Blocks for about [listenMs].
     */
    fun search(bindTo: InetAddress?, listenMs: Int, onFound: (String) -> Unit) {
        val socket = if (bindTo != null) DatagramSocket(InetSocketAddress(bindTo, 0)) else DatagramSocket()
        socket.use {
            val group = InetAddress.getByName("239.255.255.250")
            for (target in listOf(MEDIA_RENDERER, AV_TRANSPORT)) {
                val message = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: $target\r\n\r\n"
                val bytes = message.toByteArray(Charsets.US_ASCII)
                // Sent twice: these little broadcasts are easily lost on busy Wi-Fi.
                repeat(2) { runCatching { socket.send(DatagramPacket(bytes, bytes.size, group, 1900)) } }
            }
            val seen = HashSet<String>()
            val deadline = System.currentTimeMillis() + listenMs
            val buffer = ByteArray(4096)
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                socket.soTimeout = left.toInt().coerceAtLeast(1)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val location = parseSsdp(String(packet.data, 0, packet.length, Charsets.UTF_8))["location"] ?: continue
                if (seen.add(location)) onFound(location)
            }
        }
    }

    /** Header names (lower-cased) to values of one discovery answer. */
    fun parseSsdp(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in text.split("\r\n", "\n")) {
            val at = line.indexOf(':')
            if (at > 0) out[line.substring(0, at).trim().lowercase(Locale.ROOT)] = line.substring(at + 1).trim()
        }
        return out
    }

    /** Fetches a device's description and returns it if it can play media. */
    fun describe(location: String): DlnaRenderer? {
        val text = httpGet(location, 4000)
        return parseDescription(text, location)
    }

    /** Reads a device description. Returns null when the device has no playback control. */
    fun parseDescription(xml: String, location: String): DlnaRenderer? {
        val doc = parse(xml)
        val base = doc.documentElement.child("URLBase")?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: location
        val services = doc.getElementsByTagNameNS("*", "service")
        var transport: String? = null
        var rendering: String? = null
        var owner: Element? = null
        for (i in 0 until services.length) {
            val service = services.item(i) as? Element ?: continue
            val type = service.child("serviceType")?.textContent?.trim().orEmpty()
            val control = service.child("controlURL")?.textContent?.trim().orEmpty()
            if (control.isEmpty()) continue
            if (type.startsWith("urn:schemas-upnp-org:service:AVTransport:") && transport == null) {
                transport = resolve(base, control)
                owner = service.parentNode?.parentNode as? Element
            } else if (type.startsWith("urn:schemas-upnp-org:service:RenderingControl:") && rendering == null) {
                rendering = resolve(base, control)
            }
        }
        if (transport == null) return null
        val device = owner ?: doc.getElementsByTagNameNS("*", "device").item(0) as? Element
        val root = doc.getElementsByTagNameNS("*", "device").item(0) as? Element
        fun field(name: String) = device?.child(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            ?: root?.child(name)?.textContent?.trim().orEmpty()
        return DlnaRenderer(
            location = location,
            name = field("friendlyName").ifBlank { "TV" },
            maker = field("manufacturer"),
            model = field("modelName"),
            transportUrl = transport,
            renderingUrl = rendering,
        )
    }

    private fun resolve(base: String, path: String): String = runCatching { URL(URL(base), path).toString() }.getOrDefault(path)

    // ---- controlling playback --------------------------------------------------------------

    /** Hands the device the address to play. [metadata] describes it (title, kind, length). */
    fun setUri(device: DlnaRenderer, url: String, metadata: String) {
        call(
            device.transportUrl, AV_TRANSPORT, "SetAVTransportURI",
            "InstanceID" to "0", "CurrentURI" to url, "CurrentURIMetaData" to metadata,
        )
    }

    fun play(device: DlnaRenderer) {
        call(device.transportUrl, AV_TRANSPORT, "Play", "InstanceID" to "0", "Speed" to "1")
    }

    fun pause(device: DlnaRenderer) {
        call(device.transportUrl, AV_TRANSPORT, "Pause", "InstanceID" to "0")
    }

    fun stop(device: DlnaRenderer) {
        call(device.transportUrl, AV_TRANSPORT, "Stop", "InstanceID" to "0")
    }

    fun seek(device: DlnaRenderer, positionMs: Long) {
        call(device.transportUrl, AV_TRANSPORT, "Seek", "InstanceID" to "0", "Unit" to "REL_TIME", "Target" to formatTime(positionMs))
    }

    fun position(device: DlnaRenderer): DlnaPosition {
        val reply = call(device.transportUrl, AV_TRANSPORT, "GetPositionInfo", "InstanceID" to "0")
        return DlnaPosition(
            positionMs = parseTime(reply["RelTime"]),
            durationMs = parseTime(reply["TrackDuration"]),
            uri = reply["TrackURI"].orEmpty(),
        )
    }

    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING or NO_MEDIA_PRESENT. */
    fun transportState(device: DlnaRenderer): String =
        call(device.transportUrl, AV_TRANSPORT, "GetTransportInfo", "InstanceID" to "0")["CurrentTransportState"].orEmpty()

    /** Volume from 0 to 100, or null when the device does not say. */
    fun volume(device: DlnaRenderer): Int? {
        val url = device.renderingUrl ?: return null
        return call(url, RENDERING_CONTROL, "GetVolume", "InstanceID" to "0", "Channel" to "Master")["CurrentVolume"]?.toIntOrNull()
    }

    fun setVolume(device: DlnaRenderer, level: Int) {
        val url = device.renderingUrl ?: return
        call(
            url, RENDERING_CONTROL, "SetVolume",
            "InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to level.coerceIn(0, 100).toString(),
        )
    }

    /** The "label" a TV reads to learn what it is about to play. */
    fun metadata(url: String, title: String, artist: String, mime: String, isVideo: Boolean, durationMs: Long, artUrl: String?): String {
        val kind = if (isVideo) "object.item.videoItem" else "object.item.audioItem.musicTrack"
        val duration = if (durationMs > 0) " duration=\"${formatTime(durationMs)}\"" else ""
        return buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
            append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
            append("<dc:title>").append(escape(title.ifBlank { "Media" })).append("</dc:title>")
            if (artist.isNotBlank()) {
                append("<dc:creator>").append(escape(artist)).append("</dc:creator>")
                append("<upnp:artist>").append(escape(artist)).append("</upnp:artist>")
            }
            if (artUrl != null) append("<upnp:albumArtURI>").append(escape(artUrl)).append("</upnp:albumArtURI>")
            append("<upnp:class>").append(kind).append("</upnp:class>")
            append("<res protocolInfo=\"http-get:*:").append(escape(mime)).append(':').append(MediaServer.DLNA_FEATURES).append('"')
            append(duration).append('>').append(escape(url)).append("</res>")
            append("</item></DIDL-Lite>")
        }
    }

    /** Sends one command and returns the values in the reply. */
    fun call(controlUrl: String, service: String, action: String, vararg arguments: Pair<String, String>): Map<String, String> {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:").append(action).append(" xmlns:u=\"").append(service).append("\">")
            for ((name, value) in arguments) append('<').append(name).append('>').append(escape(value)).append("</").append(name).append('>')
            append("</u:").append(action).append("></s:Body></s:Envelope>")
        }.toByteArray(Charsets.UTF_8)
        val connection = URL(controlUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 4000
            connection.readTimeout = 8000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPAction", "\"$service#$action\"")
            connection.setRequestProperty("Connection", "close")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) {
                val reason = runCatching { values(text) }.getOrNull()
                throw DlnaException(
                    reason?.get("errorDescription")?.takeIf { it.isNotBlank() } ?: "The TV refused \"$action\" (error $code)",
                    reason?.get("errorCode")?.toIntOrNull() ?: code,
                )
            }
            return if (text.isBlank()) emptyMap() else runCatching { values(text) }.getOrDefault(emptyMap())
        } finally {
            connection.disconnect()
        }
    }

    /** Every simple "name = text" pair found anywhere in a reply. */
    private fun values(xml: String): Map<String, String> {
        val out = HashMap<String, String>()
        fun walk(node: Node) {
            val children = node.childNodes
            var hasElement = false
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeType == Node.ELEMENT_NODE) {
                    hasElement = true
                    walk(child)
                }
            }
            if (!hasElement && node.nodeType == Node.ELEMENT_NODE) out[node.localName ?: node.nodeName.substringAfter(':')] = node.textContent.orEmpty().trim()
        }
        walk(parse(xml).documentElement)
        return out
    }

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { isExpandEntityReferences = false }
    }.newDocumentBuilder().parse(ByteArrayInputStream(xml.trim().toByteArray(Charsets.UTF_8)))

    private fun Element.child(name: String): Element? {
        val children = childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && (child.localName ?: child.nodeName.substringAfter(':')) == name) return child
        }
        return null
    }

    private fun httpGet(address: String, timeoutMs: Int): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Connection", "close")
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    /** 83 500 ms becomes "0:01:23". */
    fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d:%02d".format(Locale.US, total / 3600, total / 60 % 60, total % 60)
    }

    /** "0:01:23.500" becomes 83 500 ms. Anything unreadable ("NOT_IMPLEMENTED") is 0. */
    fun parseTime(text: String?): Long {
        val parts = text?.trim()?.split(':') ?: return 0
        if (parts.size != 3) return 0
        val h = parts[0].toLongOrNull() ?: return 0
        val m = parts[1].toLongOrNull() ?: return 0
        val s = parts[2].toDoubleOrNull() ?: return 0
        return h * 3_600_000 + m * 60_000 + (s * 1000).toLong()
    }
}
