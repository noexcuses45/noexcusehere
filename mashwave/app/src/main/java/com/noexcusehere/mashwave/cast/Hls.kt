package com.noexcusehere.mashwave.cast

import java.net.URI

/** Text handling for HLS playlists (the ".m3u8" lists of short video pieces a stream is made of). */
object Hls {
    private val uriAttribute = Regex("""URI="([^"]*)"""")

    fun isPlaylist(contentType: String?, url: String, firstBytes: String): Boolean {
        val type = contentType.orEmpty().lowercase()
        return "mpegurl" in type || url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) || firstBytes.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("#EXTM3U")
    }

    /**
     * Rewrites every address in [playlist] (pieces, keys, other playlists) with [wrap], after
     * making it complete against [base] - the address the playlist itself came from.
     * Used to pass a stream through the phone on its way to a TV.
     */
    fun rewrite(playlist: String, base: String, wrap: (String) -> String): String {
        val out = StringBuilder(playlist.length + 512)
        for (raw in playlist.lineSequence()) {
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> out.append(line)
                line.startsWith("#") -> out.append(uriAttribute.replace(line) { m -> "URI=\"" + wrap(resolve(base, m.groupValues[1])) + "\"" })
                else -> out.append(wrap(resolve(base, line.trim())))
            }
            out.append('\n')
        }
        return out.toString()
    }

    fun resolve(base: String, reference: String): String =
        runCatching { URI(base).resolve(URI(reference.replace(" ", "%20"))).toString() }.getOrDefault(reference)
}
