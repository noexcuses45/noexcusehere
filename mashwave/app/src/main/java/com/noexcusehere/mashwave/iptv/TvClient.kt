package com.noexcusehere.mashwave.iptv

import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PushbackInputStream

/** Asks a TV service for its lists and details. Every call blocks, so use it off the main thread. */
object TvClient {
    private const val SMALL_ANSWER = 6 * 1024 * 1024

    /** Shown when a service that can be reached still says no, since the usual cause is on its side. */
    const val OTHER_PLAYER_HINT = "If the same details work in another player on this phone, open \"More\" and choose how the app introduces itself."

    private fun agent(account: TvAccount): String? = account.userAgent.ifBlank { null }

    /** Checks the login and returns the account with what the service said about the subscription. */
    fun signIn(account: TvAccount): TvAccount {
        val root = ask(Xtream.api(account), agent(account))
        val session = Xtream.session(root)
        if (!session.ok) throw TvException(session.message.ifBlank { "The service did not accept this login." })
        return account.copy(
            status = session.status,
            expiresAt = session.expiresAt,
            maxConnections = session.maxConnections,
            formats = session.formats,
        )
    }

    /**
     * Signs in, working around the two things that stop a correct login:
     *
     * - the address cannot be reached at all: the same address over the other kind of connection
     *   (http / https) is tried, then the [spares] (other addresses of the same service);
     * - the server is there but turns this app away: some services only answer players they
     *   recognise, so the standard Android introductions are tried as well.
     *
     * Returns the account with the address and the introduction that worked.
     */
    fun signIn(account: TvAccount, spares: List<String>): TvAccount {
        val servers = Xtream.candidates(account.server, spares)
        var unreachable: TvException? = null
        var refused: TvException? = null
        for (server in servers) {
            for (agent in TvNet.agents(account.userAgent)) {
                try {
                    return signIn(account.copy(server = server, userAgent = if (agent == TvNet.userAgent) "" else agent))
                } catch (e: TvException) {
                    if (e.network && !e.forwarded) {
                        // Nothing answers at this address; saying a different name changes nothing.
                        if (unreachable == null) unreachable = e
                        break
                    }
                    // Keep the most telling "no": a real answer beats being forwarded nowhere.
                    val kept = refused
                    if (kept == null || (kept.network && !e.network)) refused = e
                }
            }
            // This server was there and said no every time; another address of the same service would too.
            if (refused != null) break
        }
        refused?.let { throw TvException(it.message + " " + OTHER_PLAYER_HINT, it, network = it.network, forwarded = it.forwarded) }
        val problem = unreachable ?: throw TvException("Fill in the server address, user name and password.")
        val others = servers.drop(1)
        if (others.isEmpty()) throw problem
        // Say what else was tried, so nobody retypes an address the app has already ruled out.
        throw TvException(problem.message + " Also tried " + others.joinToString(", ") + " - no luck there either.", problem, network = true)
    }

    /** One section of a login's catalogue: its categories and everything in them. */
    fun catalog(account: TvAccount, section: TvSection, now: Long): TvCatalog {
        val (categoriesAction, itemsAction) = when (section) {
            TvSection.LIVE -> "get_live_categories" to "get_live_streams"
            TvSection.MOVIES -> "get_vod_categories" to "get_vod_streams"
            TvSection.SHOWS -> "get_series_categories" to "get_series"
        }
        val categories = TvNet.open(Xtream.api(account, categoriesAction), readMs = 60_000, agent = agent(account)).use { Xtream.categories(Xtream.elements(clean(it.stream))) }
        val items = TvNet.open(Xtream.api(account, itemsAction), readMs = 90_000, agent = agent(account)).use { Xtream.items(Xtream.elements(clean(it.stream)), section) }
        // Channels filed under a category the service forgot to list still need a home.
        val known = categories.mapTo(HashSet()) { it.id }
        val extra = items.asSequence().map { it.category }.filter { it !in known }.distinct()
            .map { TvCategory(it, if (it.isEmpty()) "Uncategorised" else "Category $it") }.toList()
        return TvCatalog(categories + extra, items, now)
    }

    /** Downloads a playlist and sorts it into the three sections. */
    fun playlist(account: TvAccount, now: Long): Map<TvSection, TvCatalog> =
        TvNet.open(account.playlistUrl, readMs = 90_000, agent = agent(account)).use { response ->
            M3u.catalogs(response.stream.bufferedReader(Charsets.UTF_8), now)
        }

    /** The playlist, fetched with whichever introduction the server accepts; the account is returned to match. */
    fun playlistHowever(account: TvAccount, now: Long): Pair<TvAccount, Map<TvSection, TvCatalog>> {
        var first: TvException? = null
        for (agent in TvNet.agents(account.userAgent)) {
            val candidate = account.copy(userAgent = if (agent == TvNet.userAgent) "" else agent)
            try {
                return candidate to playlist(candidate, now)
            } catch (e: TvException) {
                if (first == null) first = e
                if (e.network && !e.forwarded) break
            }
        }
        throw first ?: TvException("That address did not return a playlist.")
    }

    fun details(account: TvAccount, item: TvItem): TvDetails = when (item.section) {
        TvSection.SHOWS -> Xtream.series(ask(Xtream.api(account, "get_series_info", "series_id" to item.id), agent(account)))
        TvSection.MOVIES -> Xtream.movie(ask(Xtream.api(account, "get_vod_info", "vod_id" to item.id), agent(account)))
        TvSection.LIVE -> TvDetails()
    }

    /** What is on [channelId] now and for the next few programmes. */
    fun guide(account: TvAccount, channelId: String, count: Int = 6): List<TvProgramme> =
        Xtream.guide(ask(Xtream.api(account, "get_short_epg", "stream_id" to channelId, "limit" to count.toString()), agent(account)))

    private fun ask(address: String, agent: String?): JsonObject = TvNet.open(address, agent = agent).use { response ->
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
