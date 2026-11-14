package com.noexcusehere.mashwave.cast

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.noexcusehere.mashwave.KeepAliveService
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.MediaEntry
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
) {
    val entry: MediaEntry?
        get() = queue.getOrNull(index)
}

enum class RemoteState { PLAYING, PAUSED, LOADING, STOPPED, FINISHED }

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

    /** Connects and, if something is playing on the phone, moves it to the TV. */
    fun connect(device: CastDevice) {
        if (_state.value.connecting != null) return
        _state.update { it.copy(connecting = device.id, problem = null) }
        scope.launch {
            val newLink: CastLink? = try {
                when (device.kind) {
                    CastKind.DLNA -> renderers[device.id]?.let { renderer ->
                        // Make sure the TV really answers before calling it connected.
                        withContext(Dispatchers.IO) { Dlna.transportState(renderer) }
                        DlnaLink(renderer)
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
            _state.update { it.copy(connecting = null, device = device, queue = emptyList(), index = 0, playing = false, positionMs = 0, durationMs = 0) }
            KeepAliveService.refresh(context)
            startPolling()
            // Carry on with whatever the phone was playing.
            val local = context.app.player
            val now = local.state.value
            if (now.current != null && now.queue.isNotEmpty()) {
                val position = local.positionMs
                local.pause()
                play(now.queue, now.index, if (now.current.isStream) 0 else position)
            }
        }
    }

    fun disconnect() {
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
        _state.update { it.copy(queue = entries, index = at, positionMs = startMs, durationMs = entries[at].durationMs, loading = true, playing = false, problem = null) }
        load(entries[at], startMs)
    }

    private fun load(entry: MediaEntry, startMs: Long) {
        val target = link ?: return
        loadJob?.cancel()
        quietUntil = System.currentTimeMillis() + 8000
        sawPlaying = false
        loadJob = scope.launch {
            try {
                val (url, mime, art) = withContext(Dispatchers.IO) { addressFor(entry) }
                withContext(Dispatchers.IO) { target.load(url, entry, mime, art, startMs) }
                context.app.store.recordPlay(entry)
            } catch (e: Exception) {
                if (!isActive) return@launch
                Log.w(TAG, "Casting ${entry.title} failed", e)
                _state.update {
                    it.copy(loading = false, playing = false, problem = "${it.device?.name ?: "The TV"} couldn't play “${entry.title}”. It may not support this kind of file.")
                }
            }
        }
    }

    /** Where the TV can fetch [entry] from: the stream's own address, or this phone. */
    private fun addressFor(entry: MediaEntry): Triple<String, String, String?> {
        val mime = mimeOf(entry)
        if (entry.isStream) return Triple(entry.uri, mime, null)
        val host = MediaServer.localAddress()?.hostAddress ?: throw IllegalStateException("The phone is not on Wi-Fi")
        val port = server.start()
        val uri = Uri.parse(entry.uri)
        val source = if (uri.scheme == "file") FileSource(File(uri.path.orEmpty()), mime) else ContentSource(context, uri, mime)
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (entry.isVideo) "mp4" else "mp3"
        val path = server.publish(source, entry.title.take(40) + "." + extension)
        return Triple("http://$host:$port$path", mime, null)
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
        if (s.positionMs > 4000 || s.index == 0) seekTo(0) else skipTo(s.index - 1)
    }

    fun skipTo(index: Int) {
        val s = _state.value
        if (index !in s.queue.indices) return
        _state.update { it.copy(index = index, positionMs = 0, durationMs = s.queue[index].durationMs, loading = true, playing = false, problem = null) }
        load(s.queue[index], 0)
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
        val ended = !quiet && sawPlaying && (status.state == RemoteState.FINISHED || status.state == RemoteState.STOPPED)
        if (ended && _state.value.queue.isNotEmpty()) {
            sawPlaying = false
            val s = _state.value
            if (s.index < s.queue.lastIndex) {
                skipTo(s.index + 1)
            } else {
                _state.update { it.copy(playing = false, loading = false, positionMs = 0) }
            }
            return
        }
        _state.update {
            it.copy(
                playing = status.state == RemoteState.PLAYING,
                loading = status.state == RemoteState.LOADING || (quiet && !sawPlaying && status.state != RemoteState.PAUSED),
                positionMs = if (quiet && status.positionMs == 0L) it.positionMs else status.positionMs,
                durationMs = if (status.durationMs > 0) status.durationMs else it.durationMs,
                volume = status.volume ?: it.volume,
            )
        }
    }

    fun clearProblem() = _state.update { it.copy(problem = null) }

    companion object {
        private const val TAG = "UltimateBeats"

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
class DlnaLink(private val device: DlnaRenderer) : CastLink {
    override suspend fun load(url: String, entry: MediaEntry, mime: String, artUrl: String?, startMs: Long) {
        runCatching { Dlna.stop(device) }
        val metadata = Dlna.metadata(url, entry.title, entry.artist, mime, entry.isVideo, entry.durationMs, artUrl)
        try {
            Dlna.setUri(device, url, metadata)
        } catch (e: DlnaException) {
            // Some TVs choke on the description; the bare address is enough for them.
            Dlna.setUri(device, url, "")
        }
        var last: Exception? = null
        for (attempt in 0 until 4) {
            try {
                Dlna.play(device)
                last = null
                break
            } catch (e: Exception) {
                // "Not ready yet" right after being handed a new address is common - try again shortly.
                last = e
                delay(600)
            }
        }
        last?.let { throw it }
        if (startMs > 3000) {
            // Wait until it is really playing before jumping to where the phone left off.
            for (i in 0 until 16) {
                delay(500)
                if (runCatching { Dlna.transportState(device) }.getOrNull() == "PLAYING") break
            }
            runCatching { Dlna.seek(device, startMs) }
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
