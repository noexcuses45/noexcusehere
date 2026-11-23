package com.noexcusehere.mashwave.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.noexcusehere.mashwave.cast.CastDevice
import com.noexcusehere.mashwave.cast.CastKind
import com.noexcusehere.mashwave.cast.CastLink
import com.noexcusehere.mashwave.cast.CastRules
import com.noexcusehere.mashwave.cast.CastWhy
import com.noexcusehere.mashwave.cast.Dlna
import com.noexcusehere.mashwave.cast.DlnaException
import com.noexcusehere.mashwave.cast.DlnaLink
import com.noexcusehere.mashwave.cast.DlnaRenderer
import com.noexcusehere.mashwave.cast.FileSource
import com.noexcusehere.mashwave.cast.MediaServer
import com.noexcusehere.mashwave.cast.NoWifiException
import com.noexcusehere.mashwave.cast.RemoteState
import com.noexcusehere.mashwave.cast.RemoteStatus
import com.noexcusehere.mashwave.cast.StreamRelay
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.data.ResumeRules
import com.noexcusehere.mashwave.iptv.isLiveTv
import com.noexcusehere.mashwave.iptv.isTv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Plays things on a smart TV instead of on this computer: finds TVs on the home network, connects
 * to one, hands it songs, videos and TV channels, and keeps track of what it is doing.
 *
 * Files on this computer are fetched by the TV from a small server here; a channel or film from a
 * TV service reaches the TV through this computer, so the address that holds the login never
 * leaves it. This follows the phone app's way of doing it, using the same shared parts.
 */
class DeskCast(private val desk: Desk, private val scope: CoroutineScope) {
    // ---- what the screens watch ----------------------------------------------------------------

    var scanning by mutableStateOf(false)
        private set
    var devices by mutableStateOf(emptyList<CastDevice>())
        private set

    /** Id of the TV being connected to, while that is under way. */
    var connecting by mutableStateOf<String?>(null)
        private set

    /** The TV things are played on, once connected. */
    var device by mutableStateOf<CastDevice?>(null)
        private set
    var queue by mutableStateOf(emptyList<MediaEntry>())
        private set
    var index by mutableIntStateOf(0)
        private set
    var playing by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0)
        private set
    var durationMs by mutableLongStateOf(0)
        private set

    /** The TV's loudness from 0 to 1; below 0 when the TV does not say. */
    var volume by mutableFloatStateOf(-1f)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    /** False when this computer is not on a home network, so no TV can reach it. */
    var onNetwork by mutableStateOf(true)
        private set

    val connected: Boolean get() = device != null
    val entry: MediaEntry? get() = queue.getOrNull(index)

    // ---- behind the scenes ---------------------------------------------------------------------

    private val server = MediaServer()
    private val renderers = ConcurrentHashMap<String, DlnaRenderer>()
    private var link: CastLink? = null
    private var scanJob: Job? = null
    private var pollJob: Job? = null
    private var loadJob: Job? = null

    /** Set while we ourselves asked the TV to stop or switch, so that is not mistaken for "it ended". */
    private var quietUntil = 0L
    private var sawPlaying = false
    private var misses = 0
    private var polls = 0
    private var liveRetries = 0
    private var lastLiveRetry = 0L

    /** The pass-through the TV is being fed from, when what is on comes from a TV service. */
    @Volatile
    private var relay: StreamRelay? = null

    /** Set once the TV has accepted something and we are waiting to see it actually start. */
    private var awaitingStart = false
    private var acceptedAt = 0L

    /** Each thing gets one more go by itself before the viewer is told it would not start. */
    private var triedAgain = false

    /** True when nothing is loaded on the TV any more, so "play" has to hand it over again. */
    private var needsReload = false
    private var dropRetries = 0
    private var lastDropRetry = 0L

    // ---- finding TVs ---------------------------------------------------------------------------

    /** Looks for TVs for as long as the "play on a TV" box is open. */
    fun startScan() {
        if (scanJob?.isActive == true) return
        scanning = true
        problem = null
        scanJob = scope.launch {
            var round = 0
            while (isActive) {
                val address = withContext(Dispatchers.IO) { MediaServer.localAddress() }
                onNetwork = address != null
                if (address != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            Dlna.search(address, listenMs = 3000) { location ->
                                if (!renderers.containsKey(location)) {
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { Dlna.describe(location) }.getOrNull()?.let { found ->
                                            renderers[location] = found
                                            publish()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                round++
                // After the first few sweeps, slow down; the list stays up while the box is open.
                if (round >= 3) scanning = false
                delay(if (round < 3) 500 else 6000)
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanning = false
    }

    /** For networks where TVs cannot be found by themselves: add one by its address. */
    fun addByAddress(text: String) {
        var address = text.trim()
        if (address.isEmpty()) return
        if (!address.startsWith("http://") && !address.startsWith("https://")) address = "http://$address"
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { Dlna.describe(address) }.getOrNull() }
            if (found == null) {
                desk.say("No TV answered at that address")
            } else {
                renderers[found.location] = found
                publish()
                desk.say("Added ${found.name}")
            }
        }
    }

    private fun publish() {
        val found = renderers.values.map {
            CastDevice(it.location, it.name, listOf(it.maker, it.model).filter(String::isNotBlank).joinToString(" ").ifBlank { "TV / speaker" }, CastKind.DLNA)
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
        scope.launch { devices = found }
    }

    // ---- connecting ----------------------------------------------------------------------------

    /** Connects to [target]. What this computer was playing carries on there. */
    fun connect(target: CastDevice) {
        if (connecting != null) return
        connecting = target.id
        problem = null
        scope.launch {
            val newLink: CastLink? = try {
                renderers[target.id]?.let { renderer ->
                    // Make sure the TV really answers before calling it connected.
                    withContext(Dispatchers.IO) { Dlna.transportState(renderer) }
                    val accepts = withContext(Dispatchers.IO) { runCatching { Dlna.sinkTypes(renderer) }.getOrDefault(emptySet()) }
                    DlnaLink(renderer, accepts)
                }
            } catch (e: Exception) {
                null
            }
            if (newLink == null) {
                connecting = null
                problem = "Couldn't connect to ${target.name}. Check it is on and on the same network as this computer."
                return@launch
            }
            closeLink()
            link = newLink
            runCatching { withContext(Dispatchers.IO) { server.start() } }
            connecting = null
            device = target
            queue = emptyList()
            index = 0
            playing = false
            loading = false
            positionMs = 0
            durationMs = 0
            volume = -1f
            startPolling()
            carryOver()
        }
    }

    /** Whatever this computer is playing moves over to the TV, from the same place. */
    private fun carryOver() {
        val local = desk.player ?: return
        val current = local.current ?: return
        val entries = local.queue
        val at = local.index
        val from = if (current.isLiveTv) 0 else local.positionMs
        desk.stopPlaying()
        play(entries, at, from)
    }

    fun disconnect() {
        // Keep the place of whatever was on, so it can carry on later here or on the TV.
        if (playing || positionMs > 0) rememberSpot(positionMs, durationMs)
        val current = link
        pollJob?.cancel()
        pollJob = null
        loadJob?.cancel()
        link = null
        relay = null
        device = null
        connecting = null
        queue = emptyList()
        index = 0
        playing = false
        loading = false
        positionMs = 0
        durationMs = 0
        problem = null
        scope.launch {
            if (current != null) {
                runCatching { withContext(Dispatchers.IO) { current.stop() } }
                runCatching { current.close() }
            }
            withContext(Dispatchers.IO) { server.stop() }
        }
    }

    private fun closeLink() {
        val old = link ?: return
        link = null
        pollJob?.cancel()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { old.stop() } }
            runCatching { old.close() }
        }
    }

    // ---- playing -------------------------------------------------------------------------------

    /** Plays [entries] on the TV from the one at [at]; [startMs] above zero starts that far in. */
    fun play(entries: List<MediaEntry>, at: Int, startMs: Long = 0) {
        if (entries.isEmpty() || link == null) return
        val i = at.coerceIn(0, entries.lastIndex)
        val from = startFor(entries[i], startMs)
        queue = entries
        index = i
        positionMs = from
        durationMs = entries[i].durationMs
        loading = true
        playing = false
        problem = null
        load(entries[i], from)
    }

    /** A video carries on where it was stopped (here or on the TV) unless told otherwise. */
    private fun startFor(entry: MediaEntry, requestedMs: Long): Long = when {
        requestedMs > 0 -> requestedMs
        entry.isLiveTv || entry.isSong -> 0
        else -> ResumeRules.startFrom(desk.resume[entry.uri])
    }

    private fun rememberSpot(at: Long, length: Long) {
        val current = entry ?: return
        if (current.isSong || current.isLiveTv || length <= 0) return
        desk.place(current, at, length)
    }

    private fun load(entry: MediaEntry, startMs: Long, secondGo: Boolean = false) {
        val target = link ?: return
        loadJob?.cancel()
        quietUntil = System.currentTimeMillis() + 8000
        sawPlaying = false
        awaitingStart = false
        needsReload = false
        if (!secondGo) triedAgain = false
        relay = null
        loadJob = scope.launch {
            val began = System.currentTimeMillis()
            try {
                // A service that allows one screen at a time must not find this computer still on the line.
                if (entry.isTv && desk.player?.current?.isTv == true) desk.stopPlaying()
                val (url, mime, art) = withContext(Dispatchers.IO) { addressFor(entry) }
                withContext(Dispatchers.IO) { target.load(url, entry, mime, art, startMs) }
                acceptedAt = System.currentTimeMillis()
                awaitingStart = true
            } catch (e: Exception) {
                if (!isActive) return@launch
                // A quick "no" is often just a slow start; a long wait that still failed has had its chance.
                didNotStart(entry, startMs, e, mayRetry = System.currentTimeMillis() - began < 12_000)
            }
        }
    }

    /** [entry] did not start on the TV: try once more by itself, then say why in plain words. */
    private fun didNotStart(entry: MediaEntry, startMs: Long, error: Exception?, mayRetry: Boolean) {
        awaitingStart = false
        val why = CastRules.whyNot(error, relay?.asked, relay?.handedOver ?: 0, relay?.trouble)
        if (mayRetry && !triedAgain && why != CastWhy.NO_WIFI) {
            triedAgain = true
            loading = true
            playing = false
            problem = null
            loadJob = scope.launch {
                delay(1500)
                if (link != null && this@DeskCast.entry?.uri == entry.uri) load(entry, startMs, secondGo = true)
            }
            return
        }
        needsReload = true
        val name = device?.name ?: "The TV"
        val detail = if (why == CastWhy.SERVICE) relay?.trouble else (error as? DlnaException)?.message
        // "Not found" is not a sign of the line being busy elsewhere; a refusal or a stream that dried up can be.
        val busyElsewhere = relay?.troubleCode !in CastRules.NOT_THERE
        loading = false
        playing = false
        problem = wordsFor(why, name, entry.title, detail, busyElsewhere)
    }

    /** Where the TV can fetch [entry] from: the stream's own address, or this computer. */
    private fun addressFor(entry: MediaEntry): Triple<String, String, String?> {
        if (entry.isTv) return relayFor(entry)
        val local = !entry.uri.contains("://")
        if (!local) return Triple(entry.uri, mimeByName(entry.uri.substringBefore('?'), entry.isVideo), null)
        val file = File(entry.uri)
        val mime = mimeOf(file, entry.isVideo)
        val host = MediaServer.localAddress()?.hostAddress ?: throw NoWifiException()
        val port = server.start()
        val ending = file.extension.lowercase(Locale.ROOT).ifEmpty { if (entry.isVideo) "mp4" else "mp3" }
        val path = server.publish(FileSource(file, mime), entry.title.take(40) + "." + ending)
        // A cover picture kept on this computer is offered to the TV the same way.
        val art = entry.art.takeIf { it.isNotEmpty() && !it.contains("://") }?.let { File(it) }?.takeIf { it.isFile }?.let { picture ->
            "http://$host:$port" + server.publish(FileSource(picture, if (picture.extension.equals("png", true)) "image/png" else "image/jpeg"), "cover.jpg")
        }
        return Triple("http://$host:$port$path", mime, art)
    }

    /**
     * A channel, film or episode from a TV service reaches the TV through this computer: the TV is
     * given an address here, and this computer fetches the real stream (whose address holds the login).
     */
    private fun relayFor(entry: MediaEntry): Triple<String, String, String?> {
        val host = MediaServer.localAddress()?.hostAddress ?: throw NoWifiException()
        val port = server.start()
        val address = desk.tv.castAddress(entry, false)
        val mime = StreamRelay.mimeFor(address, entry.isLiveTv, link?.accepts.orEmpty())
        var base = ""
        val made = StreamRelay(address, entry.isLiveTv, mime, desk.tv.agentFor(entry)) { base }
        relay = made
        val name = when {
            mime == "application/x-mpegURL" -> "stream.m3u8"
            entry.isLiveTv -> "stream.ts"
            else -> "video." + (address.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "mp4").take(5).ifBlank { "mp4" })
        }
        val path = server.publish(made, name)
        base = "http://$host:$port" + path.substringBeforeLast('/')
        return Triple("http://$host:$port$path", mime, entry.art.takeIf { it.startsWith("http") })
    }

    fun togglePlay() {
        val target = link ?: return
        // After something failed or stopped part-way there is nothing on the TV to "play": hand it over again.
        if (needsReload && !playing && entry != null) {
            skipTo(index)
            return
        }
        val wasPlaying = playing
        playing = !wasPlaying
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { if (wasPlaying) target.pause() else target.play() } }
                .onFailure { playing = wasPlaying }
        }
    }

    fun seekTo(ms: Long) {
        val target = link ?: return
        positionMs = ms
        quietUntil = maxOf(quietUntil, System.currentTimeMillis() + 3000)
        scope.launch { runCatching { withContext(Dispatchers.IO) { target.seek(ms) } } }
    }

    fun seekBy(deltaMs: Long) {
        if (entry == null) return
        var target = (positionMs + deltaMs).coerceAtLeast(0)
        if (durationMs > 0) target = target.coerceAtMost((durationMs - 1000).coerceAtLeast(0))
        seekTo(target)
    }

    val hasNext: Boolean get() = index < queue.lastIndex || entry?.isLiveTv == true

    /** The next thing in the list; for a channel, the next channel. */
    fun next() {
        val current = entry ?: return
        if (current.isLiveTv && queue.size == 1) {
            desk.tv.step(current, 1)?.let { play(listOf(it), 0) }
        } else if (index < queue.lastIndex) {
            skipTo(index + 1)
        }
    }

    fun previous() {
        val current = entry ?: return
        when {
            // A live channel has no beginning to go back to: "previous" is the previous channel.
            current.isLiveTv && queue.size == 1 -> desk.tv.step(current, -1)?.let { play(listOf(it), 0) }
            current.isLiveTv -> if (index > 0) skipTo(index - 1)
            positionMs > 4000 || index == 0 -> seekTo(0)
            else -> skipTo(index - 1)
        }
    }

    private fun skipTo(to: Int) {
        if (to !in queue.indices) return
        val from = startFor(queue[to], 0)
        index = to
        positionMs = from
        durationMs = queue[to].durationMs
        loading = true
        playing = false
        problem = null
        load(queue[to], from)
    }

    fun changeVolume(level: Float) {
        val target = link ?: return
        val v = level.coerceIn(0f, 1f)
        volume = v
        scope.launch { runCatching { withContext(Dispatchers.IO) { target.setVolume(v) } } }
    }

    fun clearProblem() {
        problem = null
    }

    // ---- keeping track of the TV ---------------------------------------------------------------

    private fun startPolling() {
        pollJob?.cancel()
        misses = 0
        pollJob = scope.launch {
            while (isActive) {
                delay(1000)
                val target = link ?: break
                val status = runCatching { withContext(Dispatchers.IO) { target.status() } }.getOrNull()
                if (link !== target) break
                if (status == null) {
                    // A few missed answers are normal; a long silence means the TV is gone.
                    if (++misses >= 12) {
                        val name = device?.name ?: "The TV"
                        disconnect()
                        problem = "$name stopped answering."
                        break
                    }
                    continue
                }
                misses = 0
                apply(status)
            }
        }
    }

    private fun apply(status: RemoteStatus) {
        val quiet = System.currentTimeMillis() < quietUntil
        if (status.state == RemoteState.PLAYING) sawPlaying = true

        // The TV said "yes" to something; make sure it then really starts.
        if (awaitingStart) {
            val waited = System.currentTimeMillis() - acceptedAt
            when {
                status.state == RemoteState.PLAYING || status.state == RemoteState.PAUSED -> awaitingStart = false
                (status.state == RemoteState.STOPPED && waited > START_STOPPED_MS) || waited > START_WAIT_MS -> {
                    val current = entry
                    if (current != null) {
                        didNotStart(current, positionMs, null, mayRetry = true)
                        return
                    }
                    awaitingStart = false
                }
            }
        }

        val ended = !quiet && sawPlaying && (status.state == RemoteState.FINISHED || status.state == RemoteState.STOPPED)
        if (ended && queue.isNotEmpty()) {
            sawPlaying = false
            val current = entry
            if (current?.isLiveTv == true) {
                // A channel that stops has dropped out; moving to the next channel would be wrong.
                val now = System.currentTimeMillis()
                if (now - lastLiveRetry > 30_000) liveRetries = 0
                lastLiveRetry = now
                if (liveRetries++ < 2) {
                    skipTo(index)
                } else {
                    needsReload = true
                    playing = false
                    loading = false
                    problem = "“${current.title}” stopped. Press play to try again, or pick another channel."
                }
                return
            }
            // Stopping in the middle is not the same as finishing: the next episode must not start
            // because the line hiccuped (or someone pressed stop on the TV's remote).
            if (current != null && status.state != RemoteState.FINISHED && CastRules.stoppedEarly(positionMs, durationMs)) {
                rememberSpot(positionMs, durationMs)
                val lost = relay?.let { it.gaveUp || it.trouble != null } == true
                val now = System.currentTimeMillis()
                if (now - lastDropRetry > 120_000) dropRetries = 0
                lastDropRetry = now
                if (lost && dropRetries++ < 2) {
                    // The stream was lost: pick the same thing up from where it stopped.
                    loading = true
                    playing = false
                    problem = null
                    load(current, positionMs)
                } else {
                    needsReload = true
                    playing = false
                    loading = false
                    problem = if (lost) "“${current.title}” stopped because the stream stopped arriving. Press play to carry on from where it was." else null
                }
                return
            }
            // Something that ran to its end has nowhere to be picked up from.
            if (current != null && durationMs > 0) rememberSpot(durationMs, durationMs)
            if (index < queue.lastIndex) {
                skipTo(index + 1)
            } else {
                needsReload = true
                playing = false
                loading = false
                positionMs = 0
            }
            return
        }
        // Keep the place every few seconds while playing, and at once when paused.
        val wasPlaying = playing
        polls++
        if (!quiet && status.durationMs > 0 &&
            ((status.state == RemoteState.PLAYING && polls % 5 == 0) || (status.state == RemoteState.PAUSED && wasPlaying))
        ) {
            rememberSpot(status.positionMs, status.durationMs)
        }
        playing = status.state == RemoteState.PLAYING
        loading = status.state == RemoteState.LOADING || awaitingStart || loadJob?.isActive == true ||
            (quiet && !sawPlaying && status.state != RemoteState.PAUSED)
        if (!((quiet || awaitingStart) && status.positionMs == 0L)) positionMs = status.positionMs
        if (status.durationMs > 0) durationMs = status.durationMs
        status.volume?.let { volume = it }
    }

    /** The app is closing: let the TV go and stop serving files. */
    fun shutDown() {
        stopScan()
        pollJob?.cancel()
        loadJob?.cancel()
        runCatching { server.stop() }
    }

    companion object {
        /** How long a TV that said "yes" may sit at "stopped" before it counts as not starting. */
        private const val START_STOPPED_MS = 10_000L

        /** ...and how long it may take in all, however busy it says it is. */
        private const val START_WAIT_MS = 45_000L

        /** The sentence shown when something would not start, worded for a computer. */
        fun wordsFor(why: CastWhy, tv: String, title: String, detail: String?, busyElsewhere: Boolean = true): String = when (why) {
            CastWhy.NO_WIFI -> "This computer isn't on a home network, so $tv can't collect anything from it. Connect it to the same network as the TV, then press play."
            CastWhy.SERVICE ->
                "Your TV service didn't send “$title”" + (if (detail.isNullOrBlank()) "" else " (${detail.trimEnd('.')})") +
                    (if (busyElsewhere) ". If your plan allows one screen at a time, make sure nothing else is using it, then press play." else ". Press play to try again.")
            CastWhy.NOT_COLLECTED ->
                "$tv didn't collect “$title” from this computer. Check both are on the same network, and that the computer's firewall lets the TV in, then press play."
            CastWhy.TV_SAID_NO -> "$tv wouldn't start “$title”" + (if (detail.isNullOrBlank()) "" else " (it said: ${detail.trimEnd('.')})") + ". Press play to try again."
            CastWhy.TV_SILENT -> "$tv isn't answering. Check it is still on, then press play."
            CastWhy.UNSUPPORTED -> "$tv couldn't play “$title”. It may not support this kind of file."
        }

        /** What kind of file this is, as the TV needs to be told: by its name, corrected by how it really begins. */
        fun mimeOf(file: File, isVideo: Boolean): String {
            val declared = mimeByName(file.name, isVideo)
            val head = ByteArray(16)
            val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
            return CastRules.refineMime(declared, head, read, isVideo)
        }

        fun mimeByName(name: String, isVideo: Boolean): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "mp3" -> "audio/mpeg"
            "m4a", "m4b", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "wma" -> "audio/x-ms-wma"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi", "divx" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "wmv" -> "video/x-ms-wmv"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "mpg", "mpeg", "vob" -> "video/mpeg"
            "flv" -> "video/x-flv"
            "3gp" -> "video/3gpp"
            "m3u8" -> "application/x-mpegURL"
            else -> if (isVideo) "video/mp4" else "audio/mpeg"
        }
    }
}
