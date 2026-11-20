package com.noexcusehere.mashwave.iptv

import java.io.BufferedReader
import java.util.Locale

/**
 * Reads playlist files (M3U / "M3U Plus"), the simplest way a TV service lists its channels:
 *
 *     #EXTM3U
 *     #EXTINF:-1 tvg-id="abc.au" tvg-logo="http://.../abc.png" group-title="Australia",ABC News
 *     http://host/live/user/pass/123.ts
 *
 * Read line by line, so a playlist with a hundred thousand entries does not need to fit in
 * memory as text.
 */
object M3u {
    class Entry(
        val name: String,
        val url: String,
        val logo: String,
        val group: String,
        val tvgId: String,
        val country: String,
        val number: Int,
    )

    private val attribute = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")
    private val episode = Regex("""\bS\d{1,2}\s?E\d{1,3}\b""", RegexOption.IGNORE_CASE)
    private val videoFiles = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm", "mpg", "mpeg", "divx")

    /** Calls [onEntry] for every stream in the playlist. Returns how many lines were not understood. */
    fun parse(reader: BufferedReader, onEntry: (Entry) -> Unit): Int {
        var pending: Map<String, String>? = null
        var name = ""
        var group = ""
        var skipped = 0
        var sawHeader = false
        var lineNumber = 0
        while (true) {
            var line = reader.readLine() ?: break
            if (lineNumber++ == 0) line = line.removePrefix("\uFEFF")
            line = line.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> sawHeader = true
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val comma = nameComma(line)
                    val head = if (comma >= 0) line.substring(0, comma) else line
                    name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    pending = attributes(head)
                    group = pending["group-title"].orEmpty()
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substring(8).trim()
                line.startsWith("#") -> Unit
                else -> {
                    val info = pending
                    if (info == null && !looksLikeAddress(line)) {
                        skipped++
                    } else {
                        val title = name.ifBlank { info?.get("tvg-name").orEmpty() }.ifBlank { line.substringAfterLast('/').substringBefore('?') }
                        onEntry(
                            Entry(
                                name = title,
                                url = line,
                                logo = info?.get("tvg-logo").orEmpty(),
                                group = group.ifBlank { "Other" },
                                tvgId = info?.get("tvg-id").orEmpty(),
                                country = info?.get("tvg-country").orEmpty().substringBefore(';').trim().uppercase(Locale.ROOT),
                                number = info?.get("tvg-chno")?.toIntOrNull() ?: 0,
                            ),
                        )
                    }
                    pending = null
                    name = ""
                    group = ""
                }
            }
            // A web page or an error message instead of a playlist: stop early rather than list rubbish.
            if (!sawHeader && lineNumber > 50 && skipped > 40) throw TvException("That address did not return a playlist.")
        }
        return skipped
    }

    /** The key="value" pairs of an #EXTINF line, keys in lower case. */
    fun attributes(head: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (match in attribute.findAll(head)) out[match.groupValues[1].lowercase(Locale.ROOT)] = match.groupValues[2].trim()
        return out
    }

    /** Where the display name starts: the first comma that is not inside a quoted value. */
    private fun nameComma(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return i
            }
        }
        // Unbalanced quotes: fall back to the last comma.
        return line.lastIndexOf(',')
    }

    private fun looksLikeAddress(line: String): Boolean =
        line.startsWith("http://", true) || line.startsWith("https://", true) || line.startsWith("rtsp://", true) ||
            line.startsWith("rtmp://", true) || line.startsWith("udp://", true) || line.startsWith("mms://", true)

    /** Playlists mix channels, movies and episodes in one list; the address tells them apart. */
    fun sectionOf(url: String, name: String): TvSection {
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        if ("/series/" in path) return TvSection.SHOWS
        if ("/movie/" in path || "/movies/" in path || "/vod/" in path) return if (episode.containsMatchIn(name)) TvSection.SHOWS else TvSection.MOVIES
        val extension = path.substringAfterLast('/').substringAfterLast('.', "")
        if (extension in videoFiles) return if (episode.containsMatchIn(name)) TvSection.SHOWS else TvSection.MOVIES
        return TvSection.LIVE
    }

    /** Sorts a playlist into the three sections, each with its groups as categories. */
    fun catalogs(reader: BufferedReader, now: Long): Map<TvSection, TvCatalog> {
        val items = TvSection.entries.associateWith { ArrayList<TvItem>() }
        val groups = TvSection.entries.associateWith { LinkedHashMap<String, TvCategory>() }
        val seen = HashSet<String>()
        parse(reader) { entry ->
            // The address is the identity; a playlist that lists it twice gets it once.
            if (!seen.add(entry.url)) return@parse
            val section = sectionOf(entry.url, entry.name)
            groups.getValue(section).getOrPut(entry.group) { TvCategory(entry.group, entry.group) }
            val list = items.getValue(section)
            list += TvItem(
                section = section,
                id = entry.url,
                name = entry.name,
                logo = entry.logo,
                category = entry.group,
                url = entry.url,
                number = if (entry.number > 0) entry.number else list.size + 1,
                epgId = entry.tvgId,
                country = entry.country,
            )
        }
        return TvSection.entries.associateWith { TvCatalog(groups.getValue(it).values.toList(), items.getValue(it), now) }
    }
}
