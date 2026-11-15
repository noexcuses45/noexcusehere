package com.noexcusehere.mashwave.iptv

import android.content.Context
import android.os.Build
import android.util.Log
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One section's list as the screen sees it: maybe loading, maybe failed, maybe there. */
data class TvShelf(
    val loading: Boolean = false,
    val error: String? = null,
    val catalog: TvCatalog? = null,
)

data class TvState(
    val accounts: List<TvAccount> = emptyList(),
    val active: TvAccount? = null,
    val shelves: Map<TvSection, TvShelf> = emptyMap(),
    val favourites: List<TvItem> = emptyList(),
    val recents: List<TvItem> = emptyList(),
    val section: TvSection = TvSection.LIVE,
) {
    fun shelf(section: TvSection): TvShelf = shelves[section] ?: TvShelf()

    fun isFavourite(item: TvItem): Boolean = favourites.any { it.section == item.section && it.id == item.id }
}

/**
 * Everything behind the TV tab: the services the user signed in to, their channel / movie / show
 * lists (kept on the phone and refreshed now and then), favourites, what was watched recently,
 * and turning a list entry into something the player can play.
 */
class TvRepository(context: Context, private val scope: CoroutineScope) {
    private val store = TvStore(context)
    private var saved = TvSaved()
    private val writes = Channel<TvSaved>(Channel.CONFLATED)
    private val jobs = HashMap<TvSection, Job>()
    private val detailsCache = ConcurrentHashMap<String, TvDetails>()
    private val guideCache = ConcurrentHashMap<String, Pair<Long, List<TvProgramme>>>()

    private val _state = MutableStateFlow(TvState())
    val state: StateFlow<TvState> = _state

    /** The movie or show whose page is open. */
    var opened: TvItem? = null

    /** The list the current channel was picked from, so "next channel" knows where to go. */
    private var lineup: List<TvItem> = emptyList()
    private var lineupIndex = 0

    init {
        TvNet.userAgent = "UltimateBeats/1.0 (Linux;Android ${Build.VERSION.RELEASE}) AndroidXMedia3/1.9.4"
        scope.launch(Dispatchers.IO) {
            for (snapshot in writes) store.save(snapshot)
        }
        scope.launch {
            saved = withContext(Dispatchers.IO) { store.load() }
            publish()
            if (active() != null) ensure(saved.section)
        }
    }

    private fun active(): TvAccount? = saved.accounts.firstOrNull { it.id == saved.active } ?: saved.accounts.firstOrNull()

    private fun edit(block: (TvSaved) -> TvSaved) {
        saved = block(saved)
        writes.trySend(saved)
        publish()
    }

    private fun publish() {
        val account = active()
        _state.update {
            it.copy(
                accounts = saved.accounts,
                active = account,
                favourites = account?.let { a -> saved.favourites[a.id] }.orEmpty(),
                recents = account?.let { a -> saved.recents[a.id] }.orEmpty(),
                section = saved.section,
            )
        }
    }

    // ---- services ---------------------------------------------------------------------------

    /**
     * Checks a new login (or playlist link) with the service and, if it works, keeps it and
     * switches to it. Throws [TvException] with a plain explanation when it does not.
     * [spares] are other addresses of the same service, tried when the first cannot be reached.
     */
    suspend fun add(draft: TvAccount, replaces: String? = null, spares: List<String> = emptyList()): TvAccount {
        val now = System.currentTimeMillis()
        var account = draft.copy(
            id = replaces ?: UUID.randomUUID().toString().take(8),
            server = Xtream.normalizeServer(draft.server),
            username = draft.username.trim(),
            password = draft.password.trim(),
            // People leave the "http://" off; put it back.
            playlistUrl = draft.playlistUrl.trim().let { if (it.isEmpty() || it.contains("://")) it else "http://$it" },
            addedAt = now,
        )
        // A playlist link from a login-based service works better as a login: movies and shows get their own sections.
        var loginProblem: TvException? = null
        if (!account.isXtream) {
            val login = Xtream.loginFromLink(account.playlistUrl)
            if (login != null) {
                val asLogin = account.copy(type = TvAccount.XTREAM, server = login.server, username = login.username, password = login.password)
                val checked = withContext(Dispatchers.IO) {
                    try {
                        TvClient.signIn(asLogin, emptyList())
                    } catch (e: TvException) {
                        loginProblem = e
                        null
                    } catch (e: Exception) {
                        null
                    }
                }
                if (checked != null) account = checked.copy(playlistUrl = "")
                // The server cannot be reached: fetching the playlist from it would only fail the same way, slowly.
                loginProblem?.let { if (it.network && !it.forwarded) throw it }
            }
        }
        var catalogs: Map<TvSection, TvCatalog>? = null
        if (account.isXtream) {
            if (account.server.isEmpty() || account.username.isEmpty() || account.password.isEmpty()) {
                throw TvException("Fill in the server address, user name and password.")
            }
            if (account.status.isEmpty()) account = withContext(Dispatchers.IO) { attempt { TvClient.signIn(account, spares) } }
        } else {
            if (!account.playlistUrl.startsWith("http", ignoreCase = true)) throw TvException("The playlist link should start with http:// or https://")
            catalogs = try {
                val (accepted, lists) = withContext(Dispatchers.IO) { attempt { TvClient.playlistHowever(account, now) } }
                account = accepted
                lists
            } catch (e: TvException) {
                // "That user name or password was not accepted" says more than a failed download does.
                throw loginProblem ?: e
            }
            if (catalogs.values.all { it.items.isEmpty() }) throw TvException("That playlist has nothing in it.")
        }
        if (account.name.isBlank()) account = account.copy(name = hostOf(if (account.isXtream) account.server else account.playlistUrl))

        val id = account.id
        if (catalogs != null) {
            val lists = catalogs
            withContext(Dispatchers.IO) { lists.forEach { (section, catalog) -> store.saveCatalog(id, section, catalog) } }
        } else if (replaces != null) {
            // Different login, different lists.
            withContext(Dispatchers.IO) { store.forget(id) }
        }
        cancelLoads()
        detailsCache.clear()
        guideCache.clear()
        val final = account
        edit { s ->
            s.copy(
                accounts = if (s.accounts.any { it.id == id }) s.accounts.map { if (it.id == id) final else it } else s.accounts + final,
                active = id,
                section = TvSection.LIVE,
            )
        }
        _state.update { it.copy(shelves = catalogs?.mapValues { (_, c) -> TvShelf(catalog = c) } ?: emptyMap()) }
        ensure(TvSection.LIVE)
        return final
    }

    private inline fun <T> attempt(block: () -> T): T = try {
        block()
    } catch (e: TvException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "TV service request failed: ${e.javaClass.simpleName}")
        throw TvException(TvNet.explain(e), e)
    }

    private fun hostOf(address: String): String =
        runCatching { URI(address).host }.getOrNull()?.removePrefix("www.").orEmpty().ifBlank { "My TV" }

    fun remove(id: String) {
        cancelLoads()
        scope.launch(Dispatchers.IO) { store.forget(id) }
        edit { s ->
            val left = s.accounts.filterNot { it.id == id }
            s.copy(
                accounts = left,
                active = if (s.active == id) left.firstOrNull()?.id.orEmpty() else s.active,
                favourites = s.favourites - id,
                recents = s.recents - id,
            )
        }
        _state.update { it.copy(shelves = emptyMap()) }
        if (active() != null) ensure(saved.section)
    }

    fun select(id: String) {
        if (id == active()?.id) return
        cancelLoads()
        detailsCache.clear()
        guideCache.clear()
        lineup = emptyList()
        edit { it.copy(active = id) }
        _state.update { it.copy(shelves = emptyMap()) }
        ensure(saved.section)
    }

    fun setSection(section: TvSection) {
        if (section != saved.section) edit { it.copy(section = section) }
        ensure(section)
    }

    private fun cancelLoads() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    // ---- lists ------------------------------------------------------------------------------

    private fun setShelf(account: String, section: TvSection, change: (TvShelf) -> TvShelf) {
        if (active()?.id != account) return
        _state.update { it.copy(shelves = it.shelves + (section to change(it.shelf(section)))) }
    }

    /**
     * Makes sure [section] is on screen: from the copy on the phone first, then from the service
     * when there is no copy, it is older than half a day, or [force] says so.
     */
    fun ensure(section: TvSection, force: Boolean = false) {
        val account = active() ?: return
        // A playlist is one download that fills all three sections; a login is asked section by section.
        val sections = if (account.isXtream) listOf(section) else TvSection.entries.toList()
        val jobKey = if (account.isXtream) section else TvSection.LIVE
        if (jobs[jobKey]?.isActive == true) return
        fun fresh(catalog: TvCatalog?) = catalog != null && System.currentTimeMillis() - catalog.loadedAt < STALE_AFTER_MS
        if (!force && sections.all { fresh(_state.value.shelf(it).catalog) }) return
        jobs[jobKey] = scope.launch {
            val have = HashMap<TvSection, TvCatalog?>()
            for (s in sections) {
                var catalog = _state.value.shelf(s).catalog
                if (catalog == null) {
                    setShelf(account.id, s) { it.copy(loading = true, error = null) }
                    catalog = withContext(Dispatchers.IO) { store.loadCatalog(account.id, s) }
                    if (catalog != null) setShelf(account.id, s) { it.copy(catalog = catalog) }
                }
                have[s] = catalog
            }
            if (!force && sections.all { fresh(have[it]) }) {
                sections.forEach { s -> setShelf(account.id, s) { it.copy(loading = false) } }
                return@launch
            }
            sections.forEach { s -> setShelf(account.id, s) { it.copy(loading = true, error = null) } }
            try {
                val now = System.currentTimeMillis()
                if (account.isXtream) {
                    val catalog = withContext(Dispatchers.IO) { attempt { TvClient.catalog(account, section, now) } }
                    withContext(Dispatchers.IO) { store.saveCatalog(account.id, section, catalog) }
                    setShelf(account.id, section) { TvShelf(catalog = catalog) }
                } else {
                    val all = withContext(Dispatchers.IO) { attempt { TvClient.playlist(account, now) } }
                    withContext(Dispatchers.IO) { all.forEach { (s, c) -> store.saveCatalog(account.id, s, c) } }
                    all.forEach { (s, c) -> setShelf(account.id, s) { TvShelf(catalog = c) } }
                }
            } catch (e: TvException) {
                // Keep showing the copy we have; only say something is wrong.
                sections.forEach { s -> setShelf(account.id, s) { it.copy(loading = false, error = e.message) } }
            }
        }
    }

    /** Downloads every list again and re-checks the subscription. */
    fun refresh() {
        val account = active() ?: return
        cancelLoads()
        if (account.isXtream) {
            scope.launch {
                val checked = withContext(Dispatchers.IO) { runCatching { TvClient.signIn(account) }.getOrNull() }
                if (checked != null && active()?.id == account.id) {
                    edit { s -> s.copy(accounts = s.accounts.map { if (it.id == checked.id) checked else it }) }
                }
            }
            TvSection.entries.forEach { section ->
                // Sections never opened are left alone until they are.
                if (section == saved.section || _state.value.shelf(section).catalog != null) ensure(section, force = true)
            }
        } else {
            ensure(TvSection.LIVE, force = true)
        }
    }

    fun find(section: TvSection, id: String): TvItem? = _state.value.shelf(section).catalog?.items?.firstOrNull { it.id == id }

    // ---- favourites and history -------------------------------------------------------------

    fun toggleFavourite(item: TvItem) {
        val account = active() ?: return
        edit { s ->
            val list = s.favourites[account.id].orEmpty()
            val without = list.filterNot { it.section == item.section && it.id == item.id }
            s.copy(favourites = s.favourites + (account.id to if (without.size == list.size) list + item else without))
        }
    }

    private fun remember(item: TvItem) {
        val account = active() ?: return
        edit { s ->
            val list = s.recents[account.id].orEmpty().filterNot { it.section == item.section && it.id == item.id }
            s.copy(recents = s.recents + (account.id to (listOf(item) + list).take(RECENTS)))
        }
    }

    fun clearRecents() {
        val account = active() ?: return
        edit { it.copy(recents = it.recents - account.id) }
    }

    // ---- playing ----------------------------------------------------------------------------

    /** The stream address for a channel, in the [format] wanted ("ts" for most players, "m3u8" for Chromecast). */
    private fun liveAddress(account: TvAccount, item: TvItem, format: String?): String {
        if (item.url.isNotEmpty()) return item.url
        val allowed = account.formats
        val chosen = format?.takeIf { allowed.isEmpty() || it in allowed }
            ?: if (allowed.isNotEmpty() && "ts" !in allowed && "m3u8" in allowed) "m3u8" else "ts"
        return Xtream.liveUrl(account, item.id, chosen)
    }

    /** Something the player can play for a channel or a movie. Shows are played episode by episode. */
    fun entry(item: TvItem): MediaEntry? {
        val account = active() ?: return null
        val group = _state.value.shelf(item.section).catalog?.categoryName(item.category).orEmpty()
        return when {
            item.section == TvSection.LIVE -> MediaEntry(
                uri = liveAddress(account, item, null),
                title = item.name,
                artist = if (group.isBlank()) "Live TV" else "Live TV · $group",
                isVideo = true,
                art = item.logo,
                tv = TvRef(TvRef.LIVE, account.id, item.id).encode(),
            )
            item.section == TvSection.MOVIES || item.url.isNotEmpty() -> MediaEntry(
                uri = item.url.ifEmpty { Xtream.movieUrl(account, item.id, detailsCache[key(account, item)]?.ext?.ifBlank { null } ?: item.ext) },
                title = item.name,
                artist = if (item.section == TvSection.SHOWS) "Show" else "Movie",
                isVideo = true,
                durationMs = (detailsCache[key(account, item)]?.durationSec ?: 0) * 1000L,
                art = item.logo,
                tv = TvRef(TvRef.MOVIE, account.id, item.id).encode(),
            )
            else -> null
        }
    }

    /** The episodes of one season of [show], ready to play in order. */
    fun episodes(show: TvItem, details: TvDetails, season: Int): List<MediaEntry> {
        val account = active() ?: return emptyList()
        return details.episodes.filter { it.season == season }.map { episode ->
            MediaEntry(
                uri = Xtream.episodeUrl(account, episode.id, episode.ext),
                title = "${show.name} · S$season E${episode.number} · ${episodeTitle(show.name, episode)}",
                artist = show.name,
                album = "Season $season",
                isVideo = true,
                durationMs = episode.durationSec * 1000L,
                track = episode.number,
                art = episode.image.ifBlank { show.logo },
                tv = TvRef(TvRef.EPISODE, account.id, episode.id, show.id).encode(),
            )
        }
    }

    /** Starts a channel from [list], remembering the list for channel up / down. */
    fun tune(list: List<TvItem>, index: Int): MediaEntry? {
        val item = list.getOrNull(index) ?: return null
        if (item.section == TvSection.LIVE) {
            lineup = list
            lineupIndex = index
        }
        remember(item)
        return entry(item)
    }

    /** Notes that a movie or show was started, for "recently watched". */
    fun watched(item: TvItem) = remember(item)

    /** The next ([delta] = 1) or previous (-1) channel in the list the current one came from. */
    fun step(current: MediaEntry?, delta: Int): MediaEntry? {
        if (lineup.size < 2) return null
        val ref = current?.tvRef
        // Follow what is actually playing, in case it was changed from somewhere else.
        if (ref != null && lineup.getOrNull(lineupIndex)?.id != ref.id) {
            val at = lineup.indexOfFirst { it.id == ref.id }
            if (at >= 0) lineupIndex = at
        }
        val next = ((lineupIndex + delta) % lineup.size + lineup.size) % lineup.size
        return tune(lineup, next)
    }

    val canStep: Boolean
        get() = lineup.size > 1

    /** The channels "next channel" moves through: the list the current one was picked from. */
    val channels: List<TvItem>
        get() = lineup

    /** Changes channel on the phone's own player. */
    fun zap(player: PlayerConnection, delta: Int): Boolean {
        val entry = step(player.state.value.current, delta) ?: return false
        player.play(listOf(entry))
        return true
    }

    /** The channels around the one playing, so a TV's remote can move through them too. */
    fun lineupAround(entry: MediaEntry): Pair<List<MediaEntry>, Int>? {
        val ref = entry.tvRef ?: return null
        if (!ref.isLive || lineup.size < 2) return null
        val at = lineup.indexOfFirst { it.id == ref.id }
        if (at < 0) return null
        val from = (at - CAST_WINDOW).coerceAtLeast(0)
        val to = (at + CAST_WINDOW).coerceAtMost(lineup.lastIndex)
        val entries = (from..to).mapNotNull { entry(lineup[it]) }
        return entries to (at - from)
    }

    /** How to introduce the app when fetching [entry]'s stream: whatever its service accepted at sign-in. */
    fun agentFor(entry: MediaEntry?): String? {
        val ref = entry?.tvRef ?: return null
        return saved.accounts.firstOrNull { it.id == ref.account }?.userAgent?.ifBlank { null }
    }

    /** The channel behind a playing stream, e.g. to star it from the player. */
    fun itemOf(entry: MediaEntry?): TvItem? {
        val ref = entry?.tvRef ?: return null
        val section = when (ref.kind) {
            TvRef.LIVE -> TvSection.LIVE
            TvRef.MOVIE -> TvSection.MOVIES
            else -> return null
        }
        return lineup.firstOrNull { it.id == ref.id && it.section == section }
            ?: find(section, ref.id)
            ?: _state.value.recents.firstOrNull { it.id == ref.id && it.section == section }
    }

    /**
     * The address a TV should be given for [entry]. A Chromecast cannot play a raw broadcast
     * stream, so channels from a login are asked for in the "list of short pieces" form instead.
     */
    fun castAddress(entry: MediaEntry, forChromecast: Boolean): String {
        val ref = entry.tvRef ?: return entry.uri
        val account = saved.accounts.firstOrNull { it.id == ref.account } ?: return entry.uri
        if (!ref.isLive || !account.isXtream || !forChromecast) return entry.uri
        if (account.formats.isNotEmpty() && "m3u8" !in account.formats) return entry.uri
        return Xtream.liveUrl(account, ref.id, "m3u8")
    }

    // ---- details and guide ------------------------------------------------------------------

    private fun key(account: TvAccount, item: TvItem) = account.id + "/" + item.section.name + "/" + item.id

    fun cachedDetails(item: TvItem): TvDetails? = active()?.let { detailsCache[key(it, item)] }

    suspend fun details(item: TvItem): TvDetails {
        val account = active() ?: throw TvException("No TV service is set up.")
        if (!account.isXtream || !item.opensDetails) return TvDetails(name = item.name, cover = item.logo)
        detailsCache[key(account, item)]?.let { return it }
        val details = withContext(Dispatchers.IO) { attempt { TvClient.details(account, item) } }
        detailsCache[key(account, item)] = details
        return details
    }

    /** What is on a channel now and next; empty when the service has no guide for it. */
    suspend fun guide(entry: MediaEntry): List<TvProgramme> {
        val ref = entry.tvRef ?: return emptyList()
        val account = saved.accounts.firstOrNull { it.id == ref.account } ?: return emptyList()
        if (!ref.isLive || !account.isXtream) return emptyList()
        val cacheKey = account.id + "/" + ref.id
        val now = System.currentTimeMillis()
        guideCache[cacheKey]?.let { (at, list) ->
            // Good until the programme that was "on now" ends, or for ten minutes when there was nothing.
            val current = list.firstOrNull { it.endMs > at }
            if (now - at < 10 * 60_000 && (current == null || current.endMs > now)) return list.filter { it.endMs > now }
        }
        val list = withContext(Dispatchers.IO) { runCatching { TvClient.guide(account, ref.id) }.getOrDefault(emptyList()) }
        guideCache[cacheKey] = now to list
        return list.filter { it.endMs > now }
    }

    companion object {
        private const val TAG = "UltimateBeats"
        private const val STALE_AFTER_MS = 12 * 60 * 60 * 1000L
        private const val RECENTS = 30
        private const val CAST_WINDOW = 150

        private val episodeTag = Regex("""^\s*[-–|:]*\s*S\d{1,2}\s*[.:x-]?\s*E\d{1,3}\s*[-–|:]*\s*""", RegexOption.IGNORE_CASE)

        /** "Some Show - S01E02 - The Letter" is how services title episodes; people want "The Letter". */
        fun episodeTitle(show: String, episode: TvEpisode): String {
            var title = episode.title.trim()
            if (show.isNotBlank() && title.startsWith(show, ignoreCase = true)) title = title.substring(show.length)
            title = episodeTag.replace(title, "").trim(' ', '-', '–', '|', ':')
            return title.ifBlank { "Episode ${episode.number}" }
        }
    }
}
