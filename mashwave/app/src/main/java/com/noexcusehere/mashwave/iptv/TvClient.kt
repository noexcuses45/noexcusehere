package com.noexcusehere.mashwave.iptv

import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PushbackInputStream

/** Asks a TV service for its lists and details. Every call blocks, so use it off the main thread. */
object TvClient {
    private const val SMALL_ANSWER = 6 * 1024 * 1024

    /** Checks the login and returns the account with what the service said about the subscription. */
    fun signIn(account: TvAccount): TvAccount {
        val root = ask(Xtream.api(account))
        val session = Xtream.session(root)
        if (!session.ok) throw TvException(session.message.ifBlank { "The service did not accept this login." })
        return account.copy(
            status = session.status,
            expiresAt = session.expiresAt,
            maxConnections = session.maxConnections,
            formats = session.formats,
        )
    }

    /** One section of a login's catalogue: its categories and everything in them. */
    fun catalog(account: TvAccount, section: TvSection, now: Long): TvCatalog {
        val (categoriesAction, itemsAction) = when (section) {
            TvSection.LIVE -> "get_live_categories" to "get_live_streams"
            TvSection.MOVIES -> "get_vod_categories" to "get_vod_streams"
            TvSection.SHOWS -> "get_series_categories" to "get_series"
        }
        val categories = TvNet.open(Xtream.api(account, categoriesAction), readMs = 60_000).use { Xtream.categories(Xtream.elements(clean(it.stream))) }
        val items = TvNet.open(Xtream.api(account, itemsAction), readMs = 90_000).use { Xtream.items(Xtream.elements(clean(it.stream)), section) }
        // Channels filed under a category the service forgot to list still need a home.
        val known = categories.mapTo(HashSet()) { it.id }
        val extra = items.asSequence().map { it.category }.filter { it !in known }.distinct()
            .map { TvCategory(it, if (it.isEmpty()) "Uncategorised" else "Category $it") }.toList()
        return TvCatalog(categories + extra, items, now)
    }

    /** Downloads a playlist and sorts it into the three sections. */
    fun playlist(account: TvAccount, now: Long): Map<TvSection, TvCatalog> =
        TvNet.open(account.playlistUrl, readMs = 90_000).use { response ->
            M3u.catalogs(response.stream.bufferedReader(Charsets.UTF_8), now)
        }

    fun details(account: TvAccount, item: TvItem): TvDetails = when (item.section) {
        TvSection.SHOWS -> Xtream.series(ask(Xtream.api(account, "get_series_info", "series_id" to item.id)))
        TvSection.MOVIES -> Xtream.movie(ask(Xtream.api(account, "get_vod_info", "vod_id" to item.id)))
        TvSection.LIVE -> TvDetails()
    }

    /** What is on [channelId] now and for the next few programmes. */
    fun guide(account: TvAccount, channelId: String, count: Int = 6): List<TvProgramme> =
        Xtream.guide(ask(Xtream.api(account, "get_short_epg", "stream_id" to channelId, "limit" to count.toString())))

    private fun ask(address: String): JsonObject = TvNet.open(address).use { response ->
        val text = readLimited(clean(response.stream), SMALL_ANSWER).toString(Charsets.UTF_8).trim()
        val element = runCatching { Xtream.json.parseToJsonElement(text) }.getOrNull()
        element as? JsonObject ?: throw TvException(
            if (text.isEmpty()) "The server answered with nothing. Check the address, user name and password." else "The server answered, but not like a TV service.",
        )
    }

    /** Skips the invisible marker some servers put in front of their answer. */
    private fun clean(stream: InputStream): InputStream {
        val input = PushbackInputStream(stream.buffered(64 * 1024), 3)
        val head = ByteArray(3)
        val n = input.read(head, 0, 3)
        val isMarker = n == 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        if (n > 0 && !isMarker) input.unread(head, 0, n)
        return input
    }

    private fun readLimited(stream: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = stream.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
