package com.noexcusehere.mashwave.iptv

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeToSequence
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.io.encoding.Base64
import java.util.Locale

/**
 * The "Xtream Codes" way of talking to a TV service: one address that answers questions
 * (who am I, which channels, which movies ...) and predictable addresses for the streams.
 * Everything here is plain text handling, so it can be tested without a server.
 *
 * Services differ in small ways - numbers sent as text, lists sent as numbered objects,
 * empty details sent as "[]" - so every reader below takes whatever shape arrives.
 */
object Xtream {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    class Login(val server: String, val username: String, val password: String)

    class Session(
        val ok: Boolean,
        val status: String,
        val message: String,
        val expiresAt: Long,
        val maxConnections: Int,
        val formats: List<String>,
    )

    // ---- addresses --------------------------------------------------------------------------

    /** Tidies a pasted server address: adds "http://", drops file names, questions and slashes. */
    fun normalizeServer(input: String): String {
        var s = input.trim().replace(" ", "")
        if (s.isEmpty()) return ""
        if (!s.contains("://")) s = "http://$s"
        s = s.substringBefore('?').substringBefore('#').trimEnd('/')
        for (ending in listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php", "/c")) {
            if (s.endsWith(ending, ignoreCase = true)) s = s.dropLast(ending.length)
        }
        return s.trimEnd('/')
    }

    /**
     * Finds the login inside a playlist link such as
     * `http://host:8080/get.php?username=me&password=secret&type=m3u_plus`.
     */
    fun loginFromLink(link: String): Login? {
        val text = link.trim()
        if (!text.substringBefore('?').endsWith(".php", ignoreCase = true)) return null
        val query = text.substringAfter('?', "").substringBefore('#')
        val values = HashMap<String, String>()
        for (pair in query.split('&')) {
            val at = pair.indexOf('=')
            if (at > 0) values[pair.substring(0, at).lowercase(Locale.ROOT)] = runCatching { URLDecoder.decode(pair.substring(at + 1), "UTF-8") }.getOrDefault(pair.substring(at + 1))
        }
        val user = values["username"].orEmpty()
        val pass = values["password"].orEmpty()
        if (user.isBlank() || pass.isBlank()) return null
        return Login(normalizeServer(text), user, pass)
    }

    private val linkPattern = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    // "Username: me", "user = me", "Username - me", "Username ➡ me": the value on the same line.
    private const val BETWEEN = """(?:[ \t]*[:=][ \t]*|[ \t]+[-–—>→➡]+\uFE0F?[ \t]+)"""
    private val userPattern = Regex("""user(?:[ \t]*name)?$BETWEEN(\S+)""", RegexOption.IGNORE_CASE)
    private val passPattern = Regex("""pass(?:word)?$BETWEEN(\S+)""", RegexOption.IGNORE_CASE)

    // Looser, for when nothing is found that way: the value may sit on the line underneath.
    private val userBelow = Regex("""user(?:\s*name)?\s*[:=]\s*(\S+)""", RegexOption.IGNORE_CASE)
    private val passBelow = Regex("""pass(?:word)?\s*[:=]\s*(\S+)""", RegexOption.IGNORE_CASE)

    /**
     * Picks a login out of whatever the service sent - a whole chat message can be pasted:
     *
     *     Username: me
     *     Password: secret
     *     DNS (URL): http://host:8080
     *
     * or just the long playlist link. Returns null when no complete login can be found.
     */
    fun loginFromText(text: String): Login? {
        val links = linkPattern.findAll(text).map { it.value.trimEnd('.', ',', ')', ';') }.toList()
        links.firstNotNullOfOrNull { loginFromLink(it) }?.let { return it }
        // Same-line first: in "your username and password:" followed by the details, the word
        // after that colon is on the next line and is not the password.
        val user = (userPattern.find(text) ?: userBelow.find(text))?.groupValues?.get(1) ?: return null
        val pass = (passPattern.find(text) ?: passBelow.find(text))?.groupValues?.get(1) ?: return null
        val server = links.firstOrNull() ?: return null
        return Login(normalizeServer(server), user, pass)
    }

    /**
     * Every server address in a pasted message, in the order written. Providers often give a
     * spare ("DNS: http://one.example  Samsung and LG DNS: http://two.example") for networks
     * where the first cannot be reached.
     */
    fun serversInText(text: String): List<String> =
        linkPattern.findAll(text).map { normalizeServer(it.value.trimEnd('.', ',', ')', ';')) }.filter { it.isNotEmpty() }.distinct().toList()

    /**
     * The same server address with the other kind of connection: "https://host" for
     * "http://host" and the reverse. People type "https" out of habit when the service only
     * has "http". Null when the address names a port, which fixes the kind of connection.
     */
    fun otherScheme(server: String): String? {
        val secure = server.startsWith("https://", ignoreCase = true)
        val rest = server.substringAfter("://", "")
        if (rest.isEmpty() || !server.startsWith("http", ignoreCase = true)) return null
        val host = rest.substringBefore('/')
        if (':' in host) return null
        return (if (secure) "http://" else "https://") + rest
    }

    /**
     * Every address worth trying for a login, in order: the one given, the same with the other
     * kind of connection, then each spare the same way.
     */
    fun candidates(server: String, spares: List<String>): List<String> =
        (listOf(server) + spares).map { normalizeServer(it) }.filter { it.isNotEmpty() }
            .flatMap { listOfNotNull(it, otherScheme(it)) }.distinct()

    private fun query(text: String): String = URLEncoder.encode(text, "UTF-8")

    private fun path(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    /** The question address, e.g. `api(account, "get_live_streams")`. No action asks "who am I". */
    fun api(account: TvAccount, action: String = "", vararg extra: Pair<String, String>): String = buildString {
        append(account.server).append("/player_api.php?username=").append(query(account.username))
        append("&password=").append(query(account.password))
        if (action.isNotEmpty()) append("&action=").append(action)
        for ((key, value) in extra) append('&').append(key).append('=').append(query(value))
    }

    fun liveUrl(account: TvAccount, id: String, format: String = "ts"): String =
        "${account.server}/live/${path(account.username)}/${path(account.password)}/$id.$format"

    fun movieUrl(account: TvAccount, id: String, ext: String): String =
        "${account.server}/movie/${path(account.username)}/${path(account.password)}/$id.${ext.ifBlank { "mp4" }}"

    fun episodeUrl(account: TvAccount, id: String, ext: String): String =
        "${account.server}/series/${path(account.username)}/${path(account.password)}/$id.${ext.ifBlank { "mp4" }}"

    // ---- reading answers --------------------------------------------------------------------

    /** The items of a long list, handed out one at a time so the whole answer never sits in memory. */
    @OptIn(ExperimentalSerializationApi::class)
    fun elements(stream: InputStream): Sequence<JsonObject> = sequence {
        for (element in json.decodeToSequence(stream, JsonElement.serializer())) yieldAll(unwrap(element))
    }

    private fun unwrap(element: JsonElement): Sequence<JsonObject> = when (element) {
        is JsonArray -> element.asSequence().flatMap { unwrap(it) }
        is JsonObject -> when {
            // Asked for a list, got the sign-in answer: the service no longer accepts the login.
            element.containsKey("user_info") -> throw TvException(session(element).let { if (it.message.isNotBlank()) it.message else "The service no longer accepts this login." })
            listKeys.any { element.containsKey(it) } -> sequenceOf(element)
            // Some services send {"1": {...}, "2": {...}} instead of a plain list.
            else -> element.values.asSequence().flatMap { unwrap(it) }
        }
        else -> emptySequence()
    }

    private val listKeys = listOf("category_name", "stream_id", "series_id", "name")

    fun session(root: JsonObject): Session {
        val user = root.obj("user_info") ?: return Session(false, "", "The server answered, but not like a TV service.", 0, 0, emptyList())
        val ok = user.text("auth") == "1" || user.text("auth").equals("true", ignoreCase = true)
        val status = user.text("status")
        val message = when {
            !ok -> "That user name or password was not accepted."
            status.equals("Expired", true) -> "This subscription has run out."
            status.equals("Banned", true) || status.equals("Disabled", true) -> "The service has switched this login off."
            else -> ""
        }
        val formats = (user["allowed_output_formats"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content?.lowercase(Locale.ROOT) }
        return Session(
            ok = ok && message.isEmpty(),
            status = status,
            message = message,
            expiresAt = user.long("exp_date") * 1000,
            maxConnections = user.long("max_connections").toInt(),
            formats = formats,
        )
    }

    fun categories(elements: Sequence<JsonObject>): List<TvCategory> {
        val out = ArrayList<TvCategory>()
        val seen = HashSet<String>()
        for (o in elements) {
            val id = o.text("category_id")
            val name = o.text("category_name")
            if (id.isEmpty() || name.isEmpty() || !seen.add(id)) continue
            out += TvCategory(id, name)
        }
        return out
    }

    fun items(elements: Sequence<JsonObject>, section: TvSection): List<TvItem> {
        val out = ArrayList<TvItem>()
        // A hundred thousand movies share a few dozen category numbers and file types: keep one copy of each.
        val pool = HashMap<String, String>()
        fun shared(text: String): String = pool.getOrPut(text) { text }
        val seen = HashSet<String>()
        for (o in elements) {
            val id = if (section == TvSection.SHOWS) o.text("series_id") else o.text("stream_id")
            val name = o.text("name", "title")
            // Some services list the same stream twice; once is enough.
            if (id.isEmpty() || name.isEmpty() || !seen.add(id)) continue
            val category = o.text("category_id").ifEmpty {
                ((o["category_ids"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.content.orEmpty()
            }
            out += TvItem(
                section = section,
                id = id,
                name = name,
                logo = o.text("stream_icon", "cover"),
                category = shared(category),
                ext = if (section == TvSection.MOVIES) shared(o.text("container_extension")) else "",
                number = o.long("num").toInt().takeIf { it > 0 } ?: (out.size + 1),
                rating = o.text("rating").toFloatOrNull() ?: 0f,
                added = o.long("added", "last_modified"),
                epgId = if (section == TvSection.LIVE) o.text("epg_channel_id") else "",
            )
        }
        return out
    }

    /** A show's page: what it is about and every episode of every season. */
    fun series(root: JsonObject): TvDetails {
        val info = root.obj("info")
        val episodes = ArrayList<TvEpisode>()
        fun read(element: JsonElement?, seasonHint: Int) {
            when (element) {
                is JsonArray -> element.forEach { read(it, seasonHint) }
                is JsonObject -> {
                    if (element.containsKey("id") && (element.containsKey("episode_num") || element.containsKey("container_extension") || element.containsKey("title"))) {
                        val details = element.obj("info")
                        val id = element.text("id")
                        if (id.isNotEmpty()) {
                            episodes += TvEpisode(
                                id = id,
                                season = element.long("season").toInt().takeIf { it > 0 || seasonHint < 0 } ?: seasonHint,
                                number = element.long("episode_num").toInt(),
                                title = element.text("title"),
                                ext = element.text("container_extension").ifEmpty { "mp4" },
                                durationSec = details?.let { seconds(it) } ?: 0,
                                plot = details?.text("plot", "overview").orEmpty(),
                                image = details?.text("movie_image", "cover_big").orEmpty(),
                            )
                        }
                    } else {
                        // {"1": [...], "2": [...]} - the key is the season number.
                        for ((key, value) in element) read(value, key.toIntOrNull() ?: seasonHint)
                    }
                }
                else -> Unit
            }
        }
        read(root["episodes"], -1)
        val ordered = episodes.map { if (it.season < 0) it.copy(season = 1) else it }
            .sortedWith(compareBy({ it.season }, { it.number }))
        return TvDetails(
            name = info?.text("name", "title").orEmpty(),
            plot = info?.text("plot", "description", "overview").orEmpty(),
            cast = info?.text("cast", "actors").orEmpty(),
            director = info?.text("director").orEmpty(),
            genre = info?.text("genre").orEmpty(),
            released = info?.text("releaseDate", "releasedate", "release_date").orEmpty(),
            rating = info?.text("rating").orEmpty(),
            durationSec = (info?.long("episode_run_time") ?: 0).toInt() * 60,
            cover = info?.text("cover", "movie_image").orEmpty(),
            backdrop = info?.let { picture(it["backdrop_path"]) }.orEmpty(),
            episodes = ordered,
        )
    }

    /** A movie's page. */
    fun movie(root: JsonObject): TvDetails {
        val info = root.obj("info")
        val data = root.obj("movie_data")
        return TvDetails(
            name = data?.text("name").orEmpty().ifEmpty { info?.text("name", "o_name").orEmpty() },
            plot = info?.text("plot", "description", "overview").orEmpty(),
            cast = info?.text("cast", "actors").orEmpty(),
            director = info?.text("director").orEmpty(),
            genre = info?.text("genre").orEmpty(),
            released = info?.text("releasedate", "release_date", "releaseDate").orEmpty(),
            rating = info?.text("rating").orEmpty(),
            durationSec = info?.let { seconds(it) } ?: 0,
            cover = info?.text("movie_image", "cover_big", "cover").orEmpty(),
            backdrop = info?.let { picture(it["backdrop_path"]) }.orEmpty(),
            ext = data?.text("container_extension").orEmpty(),
        )
    }

    /** What is on a channel now and next. Titles arrive scrambled as Base64 text. */
    fun guide(root: JsonObject): List<TvProgramme> {
        val list = root["epg_listings"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val start = o.long("start_timestamp") * 1000
            val end = o.long("stop_timestamp", "end_timestamp") * 1000
            val title = unscramble(o.text("title"))
            if (start <= 0 || end <= start || title.isBlank()) null else TvProgramme(title, unscramble(o.text("description")), start, end)
        }.sortedBy { it.startMs }
    }

    /** Reads scrambled (base64) text however it was wrapped or padded; works on every Android version. */
    private val lenient = Base64.Mime.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    private fun unscramble(text: String): String {
        if (text.isEmpty()) return ""
        return runCatching { String(lenient.decode(text), Charsets.UTF_8).trim() }.getOrDefault(text)
    }

    private fun seconds(info: JsonObject): Int {
        val direct = info.long("duration_secs").toInt()
        if (direct > 0) return direct
        // "01:45:00"
        val parts = info.text("duration").split(':').mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> 0
        }
    }

    private fun picture(element: JsonElement?): String = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content?.takeIf { s -> s.startsWith("http") } }.orEmpty()
        is JsonPrimitive -> if (element is JsonNull) "" else element.content.takeIf { it.startsWith("http") }.orEmpty()
        else -> ""
    }

    // ---- forgiving field readers ------------------------------------------------------------

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    /** The first of [keys] that holds a real value, as text - whether it was sent as text or as a number. */
    private fun JsonObject.text(vararg keys: String): String {
        for (key in keys) {
            val value = this[key] as? JsonPrimitive ?: continue
            if (value is JsonNull) continue
            val text = value.content.trim()
            if (text.isNotEmpty() && text != "null") return text
        }
        return ""
    }

    private fun JsonObject.long(vararg keys: String): Long = text(*keys).toDoubleOrNull()?.toLong() ?: 0
}
