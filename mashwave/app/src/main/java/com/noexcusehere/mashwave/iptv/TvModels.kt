package com.noexcusehere.mashwave.iptv

import com.noexcusehere.mashwave.data.MediaEntry
import kotlinx.serialization.Serializable

/**
 * A TV service the user signed in to. Either an "Xtream Codes" login (server address, user name
 * and password) or the address of a playlist file (M3U). The app only plays what that service
 * offers; it has no channels of its own.
 */
@Serializable
data class TvAccount(
    val id: String,
    val name: String,
    /** [XTREAM] or [PLAYLIST]. */
    val type: String = XTREAM,
    /** "http://host:port" for a login. */
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val playlistUrl: String = "",
    val addedAt: Long = 0,
    /** What the service said about the subscription the last time we signed in. */
    val status: String = "",
    /** When the subscription runs out, in milliseconds, or 0 when unknown / never. */
    val expiresAt: Long = 0,
    val maxConnections: Int = 0,
    /** Stream formats the service allows ("ts", "m3u8"). */
    val formats: List<String> = emptyList(),
    /**
     * How the app introduces itself to this service (the "user agent"). Empty means the app's
     * own name. Some services only answer players they recognise; sign-in finds one that works.
     */
    val userAgent: String = "",
) {
    val isXtream: Boolean
        get() = type == XTREAM

    companion object {
        const val XTREAM = "xtream"
        const val PLAYLIST = "m3u"
    }
}

enum class TvSection(val title: String, val many: String) {
    LIVE("Live TV", "channels"),
    MOVIES("Movies", "movies"),
    SHOWS("Shows", "shows"),
}

@Serializable
data class TvCategory(val id: String, val name: String)

/** A channel, a movie or a show, as listed by the service. */
@Serializable
data class TvItem(
    val section: TvSection,
    /** The service's own number for it; for playlists, the stream address. */
    val id: String,
    val name: String,
    val logo: String = "",
    val category: String = "",
    /** Playlists name the address outright; logins build it from [id]. */
    val url: String = "",
    /** File type of a movie ("mkv", "mp4"). */
    val ext: String = "",
    val number: Int = 0,
    val rating: Float = 0f,
    /** When it was added to the service (seconds), for "recently added". */
    val added: Long = 0,
    val epgId: String = "",
    /** Country code, when the playlist says ("AU"). */
    val country: String = "",
) {
    /** A show from a login has seasons to open; anything with its own address just plays. */
    val opensDetails: Boolean
        get() = section != TvSection.LIVE && url.isEmpty()
}

/** Everything one section of a service holds. */
class TvCatalog(
    val categories: List<TvCategory>,
    val items: List<TvItem>,
    val loadedAt: Long,
) {
    val byCategory: Map<String, List<TvItem>> by lazy { items.groupBy { it.category } }

    fun categoryName(id: String): String = names[id] ?: ""

    private val names: Map<String, String> by lazy { categories.associate { it.id to it.name } }

    companion object {
        val EMPTY = TvCatalog(emptyList(), emptyList(), 0)
    }
}

data class TvEpisode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val ext: String = "mp4",
    val durationSec: Int = 0,
    val plot: String = "",
    val image: String = "",
)

/** The page behind a movie or a show: what it is about, and for shows the episodes. */
data class TvDetails(
    val name: String = "",
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    val released: String = "",
    val rating: String = "",
    val durationSec: Int = 0,
    val cover: String = "",
    val backdrop: String = "",
    /** File type of the movie itself, when the details know better than the list did. */
    val ext: String = "",
    val episodes: List<TvEpisode> = emptyList(),
) {
    val seasons: List<Int>
        get() = episodes.map { it.season }.distinct().sorted()
}

/** One programme in a channel's guide. */
data class TvProgramme(val title: String, val description: String, val startMs: Long, val endMs: Long) {
    fun progress(nowMs: Long): Float =
        if (endMs > startMs) ((nowMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f) else 0f
}

/**
 * The note a TV stream carries inside [MediaEntry.tv], so the player knows it is a live channel
 * (no seeking, reconnect when it drops) and which channel, movie or episode it is.
 */
data class TvRef(val kind: Char, val account: String, val id: String, val parent: String = "") {
    val isLive: Boolean
        get() = kind == LIVE

    fun encode(): String = listOf(kind.toString(), account, id, parent).joinToString(SEPARATOR)

    companion object {
        const val LIVE = 'L'
        const val MOVIE = 'M'
        const val EPISODE = 'E'
        private const val SEPARATOR = "\u001F"

        fun parse(text: String): TvRef? {
            if (text.isEmpty()) return null
            val parts = text.split(SEPARATOR)
            if (parts.size < 3 || parts[0].length != 1) return null
            return TvRef(parts[0][0], parts[1], parts[2], parts.getOrElse(3) { "" })
        }
    }
}

val MediaEntry.tvRef: TvRef?
    get() = TvRef.parse(tv)

val MediaEntry.isLiveTv: Boolean
    get() = tv.startsWith("L")

val MediaEntry.isTv: Boolean
    get() = tv.isNotEmpty()

/** Something went wrong that the user should be told about in plain words. */
class TvException(
    message: String,
    cause: Throwable? = null,
    /** True when the server could not be reached at all (as opposed to answering "no"). */
    val network: Boolean = false,
    /** True when the failure came after the service forwarded the app somewhere else. */
    val forwarded: Boolean = false,
) : Exception(message, cause)
