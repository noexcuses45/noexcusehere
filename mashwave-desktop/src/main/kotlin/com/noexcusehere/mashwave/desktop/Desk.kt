package com.noexcusehere.mashwave.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.data.ResumePoint
import com.noexcusehere.mashwave.data.ResumeRules
import com.noexcusehere.mashwave.desktop.vlc.PlayStatus
import com.noexcusehere.mashwave.desktop.vlc.PlayerHost
import com.noexcusehere.mashwave.desktop.vlc.VlcFinder
import com.noexcusehere.mashwave.desktop.vlc.VlcPlayer
import com.noexcusehere.mashwave.iptv.TvAccount
import com.noexcusehere.mashwave.iptv.TvDetails
import com.noexcusehere.mashwave.iptv.TvEpisode
import com.noexcusehere.mashwave.iptv.TvItem
import com.noexcusehere.mashwave.iptv.TvNet
import com.noexcusehere.mashwave.iptv.TvRepository
import com.noexcusehere.mashwave.iptv.TvSection
import com.noexcusehere.mashwave.iptv.TvStore
import com.noexcusehere.mashwave.licence.LicenceConfig
import com.noexcusehere.mashwave.licence.LicenceDevice
import com.noexcusehere.mashwave.licence.LicenceManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(val title: String) {
    LIVE("Live TV"),
    MOVIES("Movies"),
    SHOWS("Shows"),
    SEARCH("Search"),
    SAVED("Favourites"),
    SETTINGS("Settings"),
}

/** How the picture is fitted into the space it has. */
enum class Look(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    WIDE("16:9"),
    CLASSIC("4:3"),
}

/** The sign-in box: adding a service, or changing [editing]. */
class SignIn(val editing: TvAccount?)

/**
 * Everything the screens share: the TV services (the same code the phone app runs), the player,
 * which screen is open, and the handful of things that are remembered between visits.
 */
class Desk(
    private val scope: CoroutineScope,
    val dataDir: File,
    private val vlcArgs: List<String> = emptyList(),
    /** False for the self-check, which plays its test clips and closes again. */
    asksForCode: Boolean = true,
) : PlayerHost {
    val store = DeskStore(dataDir, scope)
    val tv: TvRepository

    /** Whether this computer has been unlocked with a code. */
    val licence = LicenceManager(
        endpoint = if (asksForCode) LicenceConfig.ENDPOINT else "",
        publicKey = if (asksForCode) LicenceConfig.PUBLIC_KEY else "",
        file = File(dataDir, "licence.json"),
        device = LicenceDevice.fingerprint(ThisComputer.name(dataDir)),
        platform = ThisComputer.platform(),
        version = System.getProperty("jpackage.app-version") ?: "1.0",
        scope = scope,
    )

    var player by mutableStateOf<VlcPlayer?>(null)
        private set

    /** Why there is no player (VLC missing, wrong kind); empty while looking or when all is well. */
    var vlcProblem by mutableStateOf("")
        private set
    var lookingForVlc by mutableStateOf(true)
        private set
    var vlcFolder by mutableStateOf("")
        private set

    var tab by mutableStateOf(Tab.LIVE)

    /** The movie or show whose page is open. */
    var page by mutableStateOf<TvItem?>(null)

    /** True while the player fills the window. */
    var watching by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)
    var signIn by mutableStateOf<SignIn?>(null)
    var look by mutableStateOf(runCatching { Look.valueOf(store.saved.look) }.getOrDefault(Look.FIT))
        private set

    /** A short message shown at the bottom for a few seconds. */
    var notice by mutableStateOf("")
        private set
    private var noticeJob: Job? = null

    var resume by mutableStateOf(store.saved.resume)
        private set

    init {
        // The pictures and sound are fetched by VLC, so that is the name services are given.
        TvNet.userAgent = TvNet.VLC_AGENT
        tv = TvRepository(TvStore(File(dataDir, "tv")), scope) { System.err.println(it) }
        findVlc()
        licence.lookAgainIfDue()
    }

    /** Looks for the VLC on this computer and starts the player with it. */
    fun findVlc() {
        if (player != null) return
        lookingForVlc = true
        vlcProblem = ""
        scope.launch {
            val made = withContext(Dispatchers.IO) {
                runCatching {
                    val found = VlcFinder.load() ?: return@runCatching null
                    VlcPlayer(found, this@Desk, vlcArgs) to found.folder
                }
            }
            val pair = made.getOrNull()
            if (pair != null) {
                pair.first.restoreSound(store.saved.volume, store.saved.muted)
                player = pair.first
                vlcFolder = pair.second
            } else {
                vlcProblem = VlcFinder.problem.ifEmpty { "The video player could not be started." }
            }
            lookingForVlc = false
        }
    }

    fun say(text: String) {
        notice = text
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(4000)
            notice = ""
        }
    }

    // ---- what the player asks ---------------------------------------------------------------

    override fun agent(entry: MediaEntry): String = tv.agentFor(entry) ?: TvNet.userAgent

    override fun reserveMs(): Int = TvNet.cushion.startMs

    override fun startAt(entry: MediaEntry): Long = ResumeRules.startFrom(resume[entry.uri])

    override fun place(entry: MediaEntry, positionMs: Long, durationMs: Long) {
        val next = ResumeRules.updated(resume, entry.uri, positionMs, durationMs, System.currentTimeMillis())
        if (next === resume) return
        resume = next
        store.edit { it.copy(resume = next) }
    }

    fun placeOf(entry: MediaEntry?): ResumePoint? = entry?.let { resume[it.uri] }

    // ---- playing ----------------------------------------------------------------------------

    private fun ready(): VlcPlayer? {
        val p = player
        if (p == null) {
            say(if (lookingForVlc) "The player is still starting. Try again in a moment." else "The video player is missing - see the note at the top.")
        }
        return p
    }

    /** Starts the channel at [index] of [list]; [fill] opens it over the whole window. */
    fun playChannel(list: List<TvItem>, index: Int, fill: Boolean = false) {
        val p = ready() ?: return
        val item = list.getOrNull(index) ?: return
        // Already on: a second click only makes it big.
        val same = p.current?.let { tv.itemOf(it) }?.let { it.id == item.id && it.section == item.section } == true
        if (!same || p.status == PlayStatus.FAILED) {
            val entry = tv.tune(list, index) ?: return
            p.play(listOf(entry), 0)
        }
        if (fill) watching = true
    }

    fun playMovie(item: TvItem, fromStart: Boolean = false) {
        val p = ready() ?: return
        val entry = tv.entry(item) ?: return
        tv.watched(item)
        p.play(listOf(entry), 0, if (fromStart) 0L else null)
        watching = true
    }

    /** Every episode of [show] in order, so "next" carries on into the following season. */
    fun episodeQueue(show: TvItem, details: TvDetails): List<MediaEntry> = details.seasons.flatMap { tv.episodes(show, details, it) }

    fun playEpisode(show: TvItem, details: TvDetails, episode: TvEpisode, fromStart: Boolean = false) {
        val p = ready() ?: return
        val queue = episodeQueue(show, details)
        // The queue is built season by season in the same order as this list.
        val ordered = details.seasons.flatMap { season -> details.episodes.filter { it.season == season } }
        val at = ordered.indexOfFirst { it.id == episode.id }
        if (queue.isEmpty() || at !in queue.indices) return
        tv.watched(show)
        p.play(queue, at, if (fromStart) 0L else null)
        watching = true
    }

    /** Opens whatever [item] is: a channel plays, a movie or show shows its page. */
    fun open(item: TvItem, list: List<TvItem> = listOf(item), index: Int = 0) {
        when {
            item.section == TvSection.LIVE -> playChannel(list, index, fill = true)
            item.opensDetails -> page = item
            else -> playMovie(item)
        }
    }

    /** The next (1) or previous (-1) channel. False when there is nowhere to go. */
    fun step(delta: Int): Boolean {
        val p = player ?: return false
        val next = tv.step(p.current, delta) ?: return false
        p.play(listOf(next), 0)
        return true
    }

    fun stopPlaying() {
        watching = false
        fullscreen = false
        player?.close()
    }

    fun changeLook(next: Look) {
        look = next
        store.edit { it.copy(look = next.name) }
    }

    fun rememberSound() {
        val p = player ?: return
        store.edit { it.copy(volume = p.volume, muted = p.muted) }
    }

    /** The app is closing: write everything down and let VLC go. */
    fun shutDown() {
        rememberSound()
        player?.release()
        store.flush()
    }
}
