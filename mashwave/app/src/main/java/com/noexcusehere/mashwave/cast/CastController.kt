package com.noexcusehere.mashwave.cast

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.noexcusehere.mashwave.KeepAliveService
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.iptv.isLiveTv
import com.noexcusehere.mashwave.iptv.isTv
import com.noexcusehere.mashwave.data.ResumeRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

enum class CastKind { DLNA, CHROMECAST }

/**
 * The two things that can be sent to a TV, switched on separately: a show can run on the TV
 * while music keeps playing on the phone or a Bluetooth speaker.
 */
enum class CastWhat { MUSIC, VIDEO }

/** A TV or speaker the user can send music and videos to. */
data class CastDevice(val id: String, val name: String, val detail: String, val kind: CastKind)

data class CastState(
    val scanning: Boolean = false,
    val devices: List<CastDevice> = emptyList(),
    /** Id of the device we are in the middle of connecting to. */
    val connecting: String? = null,
    /** The device we are playing on, once connected. */
    val device: CastDevice? = null,
    val queue: List<MediaEntry> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** TV volume from 0 to 1, when the TV tells us. */
    val volume: Float? = null,
    val problem: String? = null,
    /** False when the phone is not on Wi-Fi, so no TV can be reached. */
    val onNetwork: Boolean = true,
    /** Videos and TV channels the user starts go to the TV. */
    val sendVideo: Boolean = false,
    /** Songs the user starts go to the TV. */
    val sendMusic: Boolean = false,
) {
    fun sends(what: CastWhat): Boolean = if (what == CastWhat.VIDEO) sendVideo else sendMusic

    val entry: MediaEntry?
        get() = queue.getOrNull(index)
}

enum class RemoteState { PLAYING, PAUSED, LOADING, STOPPED, FINISHED }

/** Why something would not start on the TV. */
enum class CastWhy {
    /** The phone has no Wi-Fi, so the TV cannot reach it. */
    NO_WIFI,

    /** The TV service refused or failed to send the stream. */
    SERVICE,

    /** The TV never came to the phone for the stream. */
    NOT_COLLECTED,

    /** The TV answered "no" when told to play. */
    TV_SAID_NO,

    /** The TV stopped answering. */
    TV_SILENT,

    /** The TV took the stream and could not play it. */
    UNSUPPORTED,
}

/** The phone is not on a home network, so nothing on it can be fetched by a TV. */
class NoWifiException : IllegalStateException("The phone is not on Wi-Fi")

data class RemoteStatus(val state: RemoteState, val positionMs: Long, val durationMs: Long, val volume: Float?)

/** What every kind of connected device must be able to do. All calls may block or fail. */
interface CastLink {
    suspend fun load(url: String, entry: MediaEntry, mime: String, artUrl: String?, startMs: Long)
    suspend fun play()
    suspend fun pause()
    suspend fun seek(positionMs: Long)
    suspend fun setVolume(level: Float)
    suspend fun stop()

    /** Null when the device did not answer. */
    suspend fun status(): RemoteStatus?
    fun close()

    /** The kinds of file the device says it plays (MIME types); empty when it does not say. */
    val accepts: Set<String>
        get() = emptySet()
}

/** Finds TVs, connects to one, and keeps a queue playing on it. */
class CastController(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state

    private val server = MediaServer()
    private val renderers = ConcurrentHashMap<String, DlnaRenderer>()
    private val chromecast: ChromecastBridge? = runCatching { ChromecastBridge(context) }.getOrNull()

    private var link: CastLink? = null
    private var scanJob: Job? = null
    private var pollJob: Job? = null
    private var loadJob: Job? = null

    /** Set while we ourselves asked the TV to stop or switch, so that is not mistaken for "song ended". */
    private var quietUntil = 0L
    private var sawPlaying = false
    private var misses = 0
    private var polls = 0
    private var liveRetries = 0
    private var lastLiveRetry = 0L

    /** The pass-through the TV is being fed from, when what is on comes from the TV tab. */
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

    val isConnected: Boolean
        get() = _state.value.device != null

    // ---- finding devices -------------------------------------------------------------------

    /** Looks for devices for as long as the cast sheet is open. */
    fun startScan() {
        if (scanJob?.isActive == true) return
        _state.update { it.copy(scanning = true, problem = null) }
        chromecast?.startDiscovery { publishDevices() }
        scanJob = scope.launch {
            var round = 0
            while (isActive) {
                val address = withContext(Dispatchers.IO) { MediaServer.localAddress() }
                _state.update { it.copy(onNetwork = address != null) }
                if (address != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            Dlna.search(address, listenMs = 3000) { location ->
                                if (!renderers.containsKey(location)) {
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { Dlna.describe(location) }.getOrNull()?.let { found ->
                                            renderers[location] = found
                                            publishDevices()
                                        }
                                    }
                                }
                            }
                        }.onFailure { Log.w(TAG, "Device search failed", it) }
                    }
                }
                round++
                // After the first few sweeps, slow down; the list stays up while the sheet is open.
                if (round >= 3) _state.update { it.copy(scanning = false) }
                delay(if (round < 3) 500 else 6000)
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        chromecast?.stopDiscovery()
        _state.update { it.copy(scanning = false) }
    }

    /** For networks where TVs cannot be found automatically: add one by its address. */
    fun addByAddress(text: String) {
        var address = text.trim()
        if (address.isEmpty()) return
        if (!address.startsWith("http://") && !address.startsWith("https://")) address = "http://$address"
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { Dlna.describe(address) }.getOrNull() }
            if (found == null) {
                context.app.say("No TV answered at that address")
            } else {
                renderers[found.location] = found
                publishDevices()
                context.app.say("Added ${found.name}")
            }
        }
    }

    private fun publishDevices() {
        val dlna = renderers.values.map {
            CastDevice(it.location, it.name, listOf(it.maker, it.model).filter(String::isNotBlank).joinToString(" ").ifBlank { "TV / speaker" }, CastKind.DLNA)
        }
        val cast = chromecast?.devices().orEmpty()
        _state.update { it.copy(devices = (cast + dlna).sortedBy { d -> d.name.lowercase() }) }
    }

    // ---- connecting ------------------------------------------------------------------------

    /** True when [entry] should play on the TV instead of the phone: connected, and that kind is switched on. */
    fun takes(entry: MediaEntry): Boolean = isConnected && _state.value.sends(kindOf(entry))

    private fun kindOf(entry: MediaEntry): CastWhat = if (entry.isVideo) CastWhat.VIDEO else CastWhat.MUSIC

    /** Moves what the phone is playing over to the TV, if it is of a kind the TV is taking. */
    private fun carryOver() {
        val local = context.app.player
        val now = local.state.value
        val current = now.current ?: return
        if (now.queue.isEmpty() || !takes(current)) return
        val position = local.positionMs
        local.pause()
        // A live channel moves over with the channels around it, so the remote can change channel.
        val around = if (current.isLiveTv) context.app.tv.lineupAround(current) else null
        when {
            around != null -> play(around.first, around.second)
            current.isLiveTv || (current.isStream && !current.isTv) -> play(now.queue, now.index)
            else -> play(now.queue, now.index, position)
        }
    }

    /**
     * Switches one kind of media on or off for the connected TV. Switching on moves what the
     * phone is playing of that kind; switching off stops it on the TV. With both off there is
     * nothing left to cast, so the TV is let go.
     */
    fun setSending(what: CastWhat, on: Boolean) {
        val before = _state.value
        if (before.device == null || before.sends(what) == on) return
        val after = if (what == CastWhat.VIDEO) before.copy(sendVideo = on) else before.copy(sendMusic = on)
        if (!after.sendVideo && !after.sendMusic) {
            disconnect()
            return
        }
        _state.value = after
        if (on) {
            carryOver()
        } else if (before.entry?.let { kindOf(it) } == what) {
            // Keep its place, then clear it off the TV.
            if (before.playing || before.positionMs > 0) rememberSpot(before.positionMs, before.durationMs)
            loadJob?.cancel()
            val target = link
            _state.update { it.copy(queue = emptyList(), index = 0, playing = false, loading = false, positionMs = 0, durationMs = 0) }
            if (target != null) scope.launch { runCatching { withContext(Dispatchers.IO) { target.stop() } } }
        }
    }

    /** Connects for [what] and, if the phone is playing something of that kind, moves it to the TV. */
    fun connect(device: CastDevice, what: CastWhat = CastWhat.VIDEO) {
        if (_state.value.connecting != null) return
        _state.update { it.copy(connecting = device.id, problem = null) }
        scope.launch {
            val newLink: CastLink? = try {
                when (device.kind) {
                    CastKind.DLNA -> renderers[device.id]?.let { renderer ->
                        // Make sure the TV really answers before calling it connected.
                        withContext(Dispatchers.IO) { Dlna.transportState(renderer) }
                        val accepts = withContext(Dispatchers.IO) { runCatching { Dlna.sinkTypes(renderer) }.getOrDefault(emptySet()) }
                        DlnaLink(renderer, accepts)
                    }
                    CastKind.CHROMECAST -> chromecast?.connect(device.id)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Connecting failed", e)
                null
            }
            if (newLink == null) {
                _state.update { it.copy(connecting = null, problem = "Couldn't connect to ${device.name}. Check it is on and on the same Wi-Fi.") }
                return@launch
            }
            closeLink()
            link = newLink
            runCatching { withContext(Dispatchers.IO) { server.start() } }
            _state.update {
                it.copy(
                    connecting = null,
                    device = device,
                    queue = emptyList(),
                    index = 0,
                    playing = false,
                    positionMs = 0,
                    durationMs = 0,
                    sendVideo = what == CastWhat.VIDEO,
                    sendMusic = what == CastWhat.MUSIC,
                )
            }
            KeepAliveService.refresh(context)
            startPolling()
            // Carry on with what the phone was playing - if it is the kind this connection is for.
            carryOver()
        }
    }

    fun disconnect() {
        // Keep the place of whatever was on, so it can carry on later on the phone or the TV.
        _state.value.let { if (it.playing || it.positionMs > 0) rememberSpot(it.positionMs, it.durationMs) }
        val current = link
        pollJob?.cancel()
        pollJob = null
        loadJob?.cancel()
        link = null
        _state.update { CastState(devices = it.devices, scanning = it.scanning, onNetwork = it.onNetwork) }
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

    // ---- playing ---------------------------------------------------------------------------

    fun play(entries: List<MediaEntry>, index: Int, startMs: Long = 0) {
        if (entries.isEmpty() || link == null) return
        val at = index.coerceIn(0, entries.lastIndex)
        val from = startFor(entries[at], startMs)
        _state.update { it.copy(queue = entries, index = at, positionMs = from, durationMs = entries[at].durationMs, loading = true, playing = false, problem = null) }
        load(entries[at], from)
    }

    /** A video carries on where it was stopped (on the phone or on the TV) unless told otherwise. */
    private fun startFor(entry: MediaEntry, requestedMs: Long): Long = when {
        requestedMs > 0 -> requestedMs
        entry.isVideo -> ResumeRules.startFrom(context.app.store.resumePoint(entry.uri))
        else -> 0
    }

    /** Jumps back or forward, e.g. by ten seconds. */
    fun seekBy(deltaMs: Long) {
        val s = _state.value
        if (s.entry == null) return
        var target = (s.positionMs + deltaMs).coerceAtLeast(0)
        if (s.durationMs > 0) target = target.coerceAtMost((s.durationMs - 1000).coerceAtLeast(0))
        seekTo(target)
    }

    /** Keeps a video's place while it plays on the TV, so it can be picked up later anywhere. */
    private fun rememberSpot(positionMs: Long, durationMs: Long) {
        val entry = _state.value.entry ?: return
        if (!entry.isVideo || entry.isStream && durationMs <= 0) return
        val store = context.app.store
        if (ResumeRules.worthKeeping(positionMs, durationMs)) {
            store.saveResume(entry.uri, positionMs, durationMs)
        } else if (ResumeRules.nearEnd(positionMs, durationMs)) {
            store.clearResume(entry.uri)
        }
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
                // A service that allows one screen at a time must not find the phone still on the line.
                if (entry.isTv) releasePhoneStream()
                val (url, mime, art) = withContext(Dispatchers.IO) { addressFor(entry) }
                withContext(Dispatchers.IO) { target.load(url, entry, mime, art, startMs) }
                acceptedAt = System.currentTimeMillis()
                awaitingStart = true
                if (!entry.isTv) context.app.store.recordPlay(entry)
            } catch (e: Exception) {
                if (!isActive) return@launch
                Log.w(TAG, "Casting ${entry.title} failed", e)
                // A quick "no" is often just a slow start; a long wait that still failed has had its chance.
                didNotStart(entry, startMs, e, mayRetry = System.currentTimeMillis() - began < 12_000)
            }
        }
    }

    /** The phone's own player lets go of a TV-service stream, so the TV can have the line. */
    private fun releasePhoneStream() {
        val local = context.app.player
        if (local.state.value.current?.isTv == true) local.stop()
    }

    /** [entry] did not start on the TV: try once more by itself, then say why in plain words. */
    private fun didNotStart(entry: MediaEntry, startMs: Long, error: Exception?, mayRetry: Boolean) {
        awaitingStart = false
        val why = whyNot(error, relay?.asked, relay?.handedOver ?: 0, relay?.trouble)
        if (mayRetry && !triedAgain && why != CastWhy.NO_WIFI) {
            triedAgain = true
            _state.update { it.copy(loading = true, playing = false, problem = null) }
            loadJob = scope.launch {
                delay(1500)
                if (link != null && _state.value.entry?.uri == entry.uri) load(entry, startMs, secondGo = true)
            }
            return
        }
        needsReload = true
        val name = _state.value.device?.name ?: "The TV"
        val detail = if (why == CastWhy.SERVICE) relay?.trouble else (error as? DlnaException)?.message
        // "Not found" is not a sign of the line being busy elsewhere; a refusal or a stream that dried up can be.
        val busyElsewhere = relay?.troubleCode !in NOT_THERE
        _state.update { it.copy(loading = false, playing = false, problem = wordsFor(why, name, entry.title, detail, busyElsewhere)) }
    }

    /** Where the TV can fetch [entry] from: the stream's own address, or this phone. */
    private fun addressFor(entry: MediaEntry): Triple<String, String, String?> {
        if (entry.isTv) return relayFor(entry)
        val mime = mimeOf(entry)
        if (entry.isStream) return Triple(entry.uri, mime, null)
        val host = MediaServer.localAddress()?.hostAddress ?: throw NoWifiException()
        val port = server.start()
        val uri = Uri.parse(entry.uri)
        val source = if (uri.scheme == "file") FileSource(File(uri.path.orEmpty()), mime) else ContentSource(context, uri, mime)
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (entry.isVideo) "mp4" else "mp3"
        val path = server.publish(source, entry.title.take(40) + "." + extension)
        return Triple("http://$host:$port$path", mime, null)
    }

    /**
     * A channel, movie or episode from the TV tab reaches the TV through this phone: the TV is
     * given an address here, and the phone fetches the real stream (whose address holds the login).
     */
    private fun relayFor(entry: MediaEntry): Triple<String, String, String?> {
        val host = MediaServer.localAddress()?.hostAddress ?: throw NoWifiException()
        val port = server.start()
        val chromecast = _state.value.device?.kind == CastKind.CHROMECAST
        val address = context.app.tv.castAddress(entry, chromecast)
        val mime = StreamRelay.mimeFor(address, entry.isLiveTv, link?.accepts.orEmpty())
        var base = ""
        val relay = StreamRelay(address, entry.isLiveTv, mime, context.app.tv.agentFor(entry)) { base }
        this.relay = relay
        val name = when {
            mime == "application/x-mpegURL" -> "stream.m3u8"
            entry.isLiveTv -> "stream.ts"
            else -> "video." + (address.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "mp4").take(5).ifBlank { "mp4" })
        }
        val path = server.publish(relay, name)
        base = "http://$host:$port" + path.substringBeforeLast('/')
        return Triple("http://$host:$port$path", mime, entry.art.takeIf { it.startsWith("http") })
    }

    /** What kind of file this is, as the TV needs to be told. */
    private fun mimeOf(entry: MediaEntry): String {
        val declared = declaredMime(entry)
        if (entry.isStream) return declared
        // Phones sometimes mislabel files (an .m4a called "MP3"), and a strict TV then refuses
        // to play. The first few bytes of the file say what it really is.
        val head = ByteArray(16)
        val read = runCatching {
            val uri = Uri.parse(entry.uri)
            val stream = if (uri.scheme == "file") FileInputStream(File(uri.path.orEmpty())) else context.contentResolver.openInputStream(uri)
            stream?.use { it.read(head) } ?: 0
        }.getOrDefault(0)
        return refineMime(declared, head, read, entry.isVideo)
    }

    private fun declaredMime(entry: MediaEntry): String {
        val uri = Uri.parse(entry.uri)
        val known = if (uri.scheme == "content") runCatching { context.contentResolver.getType(uri) }.getOrNull() else null
        if (!known.isNullOrBlank() && known != "application/octet-stream") return known
        val extension = (uri.lastPathSegment ?: entry.uri).substringAfterLast('.', "").lowercase()
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
        return when (extension) {
            "m3u8" -> "application/x-mpegURL"
            "mkv" -> "video/x-matroska"
            "flac" -> "audio/flac"
            "opus", "ogg", "oga" -> "audio/ogg"
            "m4a", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            else -> if (entry.isVideo) "video/mp4" else "audio/mpeg"
        }
    }

    fun togglePlay() {
        val target = link ?: return
        val now = _state.value
        // After something failed or stopped part-way there is nothing on the TV to "play": hand it over again.
        if (needsReload && !now.playing && now.entry != null) {
            skipTo(now.index)
            return
        }
        val wasPlaying = _state.value.playing
        _state.update { it.copy(playing = !wasPlaying) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { if (wasPlaying) target.pause() else target.play() } }
                .onFailure { _state.update { s -> s.copy(playing = wasPlaying) } }
        }
    }

    fun seekTo(positionMs: Long) {
        val target = link ?: return
        _state.update { it.copy(positionMs = positionMs) }
        quietUntil = maxOf(quietUntil, System.currentTimeMillis() + 3000)
        scope.launch { runCatching { withContext(Dispatchers.IO) { target.seek(positionMs) } } }
    }

    fun next() {
        val s = _state.value
        if (s.index < s.queue.lastIndex) skipTo(s.index + 1)
    }

    fun previous() {
        val s = _state.value
        when {
            // A live channel has no beginning to go back to: "previous" is the previous channel.
            s.entry?.isLiveTv == true -> if (s.index > 0) skipTo(s.index - 1)
            s.positionMs > 4000 || s.index == 0 -> seekTo(0)
            else -> skipTo(s.index - 1)
        }
    }

    fun skipTo(index: Int) {
        val s = _state.value
        if (index !in s.queue.indices) return
        val from = startFor(s.queue[index], 0)
        _state.update { it.copy(index = index, positionMs = from, durationMs = s.queue[index].durationMs, loading = true, playing = false, problem = null) }
        load(s.queue[index], from)
    }

    fun setVolume(level: Float) {
        val target = link ?: return
        val v = level.coerceIn(0f, 1f)
        _state.update { it.copy(volume = v) }
        scope.launch { runCatching { withContext(Dispatchers.IO) { target.setVolume(v) } } }
    }

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
                    // A few missed answers are normal on Wi-Fi; a long silence means the TV is gone.
                    if (++misses >= 12) {
                        val name = _state.value.device?.name ?: "The TV"
                        disconnect()
                        _state.update { it.copy(problem = "$name stopped answering.") }
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
                    val s = _state.value
                    val entry = s.entry
                    if (entry != null) {
                        Log.w(TAG, "The TV accepted ${entry.title} but did not start it")
                        didNotStart(entry, s.positionMs, null, mayRetry = true)
                        return
                    }
                    awaitingStart = false
                }
            }
        }

        val ended = !quiet && sawPlaying && (status.state == RemoteState.FINISHED || status.state == RemoteState.STOPPED)
        if (ended && _state.value.queue.isNotEmpty()) {
            sawPlaying = false
            val s = _state.value
            if (s.entry?.isLiveTv == true) {
                // A channel that stops has dropped out; moving to the next channel would be wrong.
                val now = System.currentTimeMillis()
                if (now - lastLiveRetry > 30_000) liveRetries = 0
                lastLiveRetry = now
                if (liveRetries++ < 2) {
                    skipTo(s.index)
                } else {
                    needsReload = true
                    _state.update { it.copy(playing = false, loading = false, problem = "“${s.entry?.title}” stopped. Tap play to try again, or pick another channel.") }
                }
                return
            }
            // Stopping in the middle is not the same as finishing: the next episode must not start
            // because the line hiccuped (or someone pressed stop on the TV's remote).
            val entry = s.entry
            if (entry != null && status.state != RemoteState.FINISHED && stoppedEarly(s.positionMs, s.durationMs)) {
                rememberSpot(s.positionMs, s.durationMs)
                val lost = relay?.let { it.gaveUp || it.trouble != null } == true
                val now = System.currentTimeMillis()
                if (now - lastDropRetry > 120_000) dropRetries = 0
                lastDropRetry = now
                if (lost && dropRetries++ < 2) {
                    // The stream was lost: pick the same thing up from where it stopped.
                    _state.update { it.copy(loading = true, playing = false, problem = null) }
                    load(entry, s.positionMs)
                } else {
                    needsReload = true
                    _state.update {
                        it.copy(
                            playing = false,
                            loading = false,
                            problem = if (lost) "“${entry.title}” stopped because the stream stopped arriving. Tap play to carry on from where it was." else null,
                        )
                    }
                }
                return
            }
            if (s.index < s.queue.lastIndex) {
                skipTo(s.index + 1)
            } else {
                needsReload = true
                _state.update { it.copy(playing = false, loading = false, positionMs = 0) }
            }
            return
        }
        // Save the place every few seconds while playing, and at once when paused.
        val wasPlaying = _state.value.playing
        polls++
        if (!quiet && status.durationMs > 0 &&
            ((status.state == RemoteState.PLAYING && polls % 5 == 0) || (status.state == RemoteState.PAUSED && wasPlaying))
        ) {
            rememberSpot(status.positionMs, status.durationMs)
        }
        _state.update {
            it.copy(
                playing = status.state == RemoteState.PLAYING,
                loading = status.state == RemoteState.LOADING || awaitingStart || loadJob?.isActive == true ||
                    (quiet && !sawPlaying && status.state != RemoteState.PAUSED),
                positionMs = if ((quiet || awaitingStart) && status.positionMs == 0L) it.positionMs else status.positionMs,
                durationMs = if (status.durationMs > 0) status.durationMs else it.durationMs,
                volume = status.volume ?: it.volume,
            )
        }
    }

    fun clearProblem() = _state.update { it.copy(problem = null) }

    companion object {
        private const val TAG = "UltimateBeats"

        /** How long a TV that said "yes" may sit at "stopped" before it counts as not starting. */
        private const val START_STOPPED_MS = 10_000L

        /** ...and how long it may take in all, however busy it says it is. */
        private const val START_WAIT_MS = 45_000L

        /**
         * True when something stopped well before its end - a lost stream or a press of "stop",
         * not a film running out. Without a believable position (some TVs never report one) it
         * counts as finished, as before.
         */
        fun stoppedEarly(positionMs: Long, durationMs: Long): Boolean {
            if (durationMs < 60_000 || positionMs < 5_000) return false
            return positionMs < durationMs - maxOf(20_000L, durationMs / 20)
        }

        /**
         * Works out why something would not start, from what is known: the [error] if there was
         * one, how often the TV [asked] the phone for the stream (null when the phone was not
         * feeding it), how much was [handedOver], and what the service said was wrong ([trouble]).
         */
        fun whyNot(error: Throwable?, asked: Int?, handedOver: Long, trouble: String?): CastWhy = when {
            error is NoWifiException -> CastWhy.NO_WIFI
            trouble != null -> CastWhy.SERVICE
            // No answer at all from the TV (as opposed to an answer of "no").
            error != null && error !is DlnaException -> CastWhy.TV_SILENT
            asked == 0 -> CastWhy.NOT_COLLECTED
            error is DlnaException && handedOver == 0L -> CastWhy.TV_SAID_NO
            else -> CastWhy.UNSUPPORTED
        }

        /** Answers from a service that mean "there is no such thing here". */
        private val NOT_THERE = setOf(404, 410)

        /**
         * The sentence shown to the viewer. [detail] is the service's or the TV's own explanation,
         * when there is one; [busyElsewhere] adds the hint about plans that allow one screen at a time.
         */
        fun wordsFor(why: CastWhy, tv: String, title: String, detail: String?, busyElsewhere: Boolean = true): String = when (why) {
            CastWhy.NO_WIFI -> "The phone isn't on Wi-Fi, so $tv can't collect the video from it. Join the same Wi-Fi as the TV, then tap play."
            CastWhy.SERVICE ->
                "Your TV service didn't send “$title”" + (if (detail.isNullOrBlank()) "" else " (${detail.trimEnd('.')})") +
                    (if (busyElsewhere) ". If your plan allows one screen at a time, make sure nothing else is using it, then tap play." else ". Tap play to try again.")
            CastWhy.NOT_COLLECTED -> "$tv didn't collect “$title” from the phone. Check the phone and the TV are on the same Wi-Fi, then tap play."
            CastWhy.TV_SAID_NO -> "$tv wouldn't start “$title”" + (if (detail.isNullOrBlank()) "" else " (it said: ${detail.trimEnd('.')})") + ". Tap play to try again."
            CastWhy.TV_SILENT -> "$tv isn't answering. Check it is still on, then tap play."
            CastWhy.UNSUPPORTED -> "$tv couldn't play “$title”. It may not support this kind of file."
        }

        /**
         * Corrects [declared] when the first [count] bytes of the file ([head]) show it is really
         * a different kind of file. Unknown beginnings leave the declared type alone.
         */
        fun refineMime(declared: String, head: ByteArray, count: Int, isVideo: Boolean): String {
            if (count < 12) return declared
            fun at(offset: Int, text: String) = text.indices.all { head[offset + it] == text[it].code.toByte() }
            val lower = declared.lowercase()
            return when {
                at(4, "ftyp") ->
                    if (listOf("mp4", "m4", "quicktime", "3gp").any { it in lower }) declared else if (isVideo) "video/mp4" else "audio/mp4"
                at(0, "fLaC") -> if ("flac" in lower) declared else "audio/flac"
                at(0, "OggS") -> if (listOf("ogg", "opus", "vorbis").any { it in lower }) declared else if (isVideo) "video/ogg" else "audio/ogg"
                at(0, "RIFF") && at(8, "WAVE") -> if ("wav" in lower) declared else "audio/wav"
                at(0, "RIFF") && at(8, "AVI ") -> if ("avi" in lower || "msvideo" in lower) declared else "video/x-msvideo"
                head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() ->
                    if (listOf("matroska", "webm").any { it in lower }) declared else if (isVideo) "video/x-matroska" else "audio/x-matroska"
                at(0, "ID3") -> if ("mpeg" in lower || "mp3" in lower) declared else "audio/mpeg"
                else -> declared
            }
        }
    }
}

/** A smart TV or other device controlled the standard home-network way. */
class DlnaLink(private val device: DlnaRenderer, override val accepts: Set<String> = emptySet()) : CastLink {
    override suspend fun load(url: String, entry: MediaEntry, mime: String, artUrl: String?, startMs: Long) {
        runCatching { Dlna.stop(device) }
        val features = if (entry.isLiveTv) MediaServer.DLNA_LIVE_FEATURES else MediaServer.DLNA_FEATURES
        val metadata = Dlna.metadata(url, entry.title, entry.artist, mime, entry.isVideo, entry.durationMs, artUrl, features)
        try {
            Dlna.setUri(device, url, metadata)
        } catch (e: DlnaException) {
            // Some TVs choke on the description; the bare address is enough for them.
            Dlna.setUri(device, url, "")
        }
        // The TV may still be collecting the start of the file when it is first told to play,
        // and says "not now". Keep asking for a while rather than giving up after two seconds.
        val giveUpAt = System.currentTimeMillis() + START_PATIENCE_MS
        var last: Exception? = null
        var attempt = 0
        while (true) {
            try {
                Dlna.play(device)
                last = null
                break
            } catch (e: Exception) {
                last = e
                // It may have started by itself while the answer was on its way.
                val now = runCatching { Dlna.transportState(device) }.getOrNull()
                if (now == "PLAYING" || now == "TRANSITIONING") {
                    last = null
                    break
                }
                // "No such file" and "wrong kind of file" will not change by asking again.
                val final = e is DlnaException && e.code in FINAL_REFUSALS && attempt >= 1
                if (final || System.currentTimeMillis() >= giveUpAt) break
                delay((600L + 300L * attempt).coerceAtMost(2000L))
                attempt++
            }
        }
        last?.let { throw it }
        if (startMs > 3000) {
            // Wait until it is really playing before jumping to where it was left off. A TV that
            // just sits at "stopped" is not going to start; there is nothing to jump in.
            var idle = 0
            var started = false
            for (i in 0 until 40) {
                delay(500)
                val now = runCatching { Dlna.transportState(device) }.getOrNull()
                if (now == "PLAYING") {
                    started = true
                    break
                }
                idle = if (now == "STOPPED" || now == "NO_MEDIA_PRESENT") idle + 1 else 0
                if (idle >= 12) break
            }
            // Still busy loading after all that waiting: ask for the jump anyway, as it may yet start.
            if (started || idle < 12) runCatching { Dlna.seek(device, startMs) }
        }
    }

    override suspend fun play() = Dlna.play(device)

    override suspend fun pause() = Dlna.pause(device)

    override suspend fun seek(positionMs: Long) = Dlna.seek(device, positionMs)

    override suspend fun setVolume(level: Float) = Dlna.setVolume(device, (level * 100).toInt())

    override suspend fun stop() = Dlna.stop(device)

    private var polls = 0
    private var volume: Float? = null

    override suspend fun status(): RemoteStatus? {
        val state = runCatching { Dlna.transportState(device) }.getOrNull() ?: return null
        val position = runCatching { Dlna.position(device) }.getOrNull()
        // Volume changes rarely, so only ask now and then.
        if (polls++ % 5 == 0) volume = runCatching { Dlna.volume(device) }.getOrNull()?.let { it / 100f } ?: volume
        return RemoteStatus(
            state = when (state) {
                "PLAYING" -> RemoteState.PLAYING
                "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> RemoteState.PAUSED
                "TRANSITIONING" -> RemoteState.LOADING
                else -> RemoteState.STOPPED
            },
            positionMs = position?.positionMs ?: 0,
            durationMs = position?.durationMs ?: 0,
            volume = volume,
        )
    }

    override fun close() = Unit

    private companion object {
        /** How long to keep asking a TV to start something it has accepted. */
        const val START_PATIENCE_MS = 20_000L

        /** Answers that mean "never": bad request, wrong kind of file, no such file. */
        val FINAL_REFUSALS = setOf(402, 714, 716)
    }
}

/** A song or video in the phone's media library. */
class ContentSource(private val context: Context, private val uri: Uri, override val mime: String) : ServedSource {
    override val length: Long by lazy {
        val fromDescriptor = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1
        if (fromDescriptor >= 0) {
            fromDescriptor
        } else {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
                }
            }.getOrNull() ?: -1
        }
    }

    override fun open(offset: Long): InputStream {
        val stream = context.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        skipFully(stream, offset)
        return stream
    }
}

class FileSource(private val file: File, override val mime: String) : ServedSource {
    override val length: Long
        get() = file.length()

    override fun open(offset: Long): InputStream = FileInputStream(file).also { skipFully(it, offset) }
}

private fun skipFully(stream: InputStream, count: Long) {
    var left = count
    while (left > 0) {
        val skipped = stream.skip(left)
        if (skipped <= 0) {
            if (stream.read() < 0) return
            left--
        } else {
            left -= skipped
        }
    }
}
