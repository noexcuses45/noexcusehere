package com.noexcusehere.mashwave.desktop.vlc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.iptv.isLiveTv
import com.sun.jna.CallbackThreadInitializer
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.math.min

enum class PlayStatus { IDLE, LOADING, PLAYING, PAUSED, ENDED, FAILED }

/** What happens when the end of the list is reached, or of each thing in it. */
enum class Repeat { OFF, ALL, ONE }

/** A sound track or a set of subtitles the viewer can choose. */
data class TrackChoice(val id: Int, val name: String)

/** The newest picture of the video, shared between VLC's thread (which makes it) and the screen (which draws it). */
class FrameHolder {
    private val lock = Any()
    private var image: Image? = null

    fun swap(next: Image?) {
        val old = synchronized(lock) {
            val previous = image
            image = next
            previous
        }
        old?.close()
    }

    fun <T> use(block: (Image?) -> T): T = synchronized(lock) { block(image) }
}

/** What the player asks of the rest of the app. [agent] and [reserveMs] are called from the player's own thread; the rest on the screen's. */
interface PlayerHost {
    /** How to introduce the app to the server [entry] comes from; null for the usual way. */
    fun agent(entry: MediaEntry): String?

    /** How many milliseconds of a live channel to collect before showing it. */
    fun reserveMs(): Int

    /** Where to start [entry] from (0 for the beginning). */
    fun startAt(entry: MediaEntry): Long

    /** [entry] has reached [positionMs] of [durationMs]; remember it so it can carry on later. */
    fun place(entry: MediaEntry, positionMs: Long, durationMs: Long)
}

/**
 * Plays one thing at a time through the VLC on the computer. VLC decodes the video into memory
 * and each finished picture is handed to the screen through [frames]; sound goes straight to the
 * speakers.
 *
 * VLC's functions can take a moment (stopping a stream that has gone quiet, for one), so they are
 * all called from one background thread. What the screen shows lives in the properties below,
 * which are only ever changed on the screen's thread.
 */
class VlcPlayer(private val found: VlcFinder.Found, private val host: PlayerHost, extraArgs: List<String> = emptyList()) {
    private val lib = found.lib
    private val control = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "vlc-control").apply { isDaemon = true } }
    private val instance: Pointer
    private val player: Pointer

    val version: String
        get() = found.version

    /** True when the engine came packed with the app rather than from a VLC on the computer. */
    val builtIn: Boolean
        get() = found.builtIn

    /** How much has been decoded and played so far; for the app's check on itself. */
    class Counts(val pictures: Int, val soundDecoded: Int, val soundPlayed: Int)

    fun counts(): Counts? = runCatching {
        control.submit<Counts?> {
            val media = lib.libvlc_media_player_get_media(player) ?: return@submit null
            try {
                // Fifteen numbers of four bytes each; the decoders' counts are the 7th, 8th and 11th.
                val stats = Memory(64)
                stats.clear()
                if (lib.libvlc_media_get_stats(media, stats) == 0) null else Counts(stats.getInt(24), stats.getInt(28), stats.getInt(40))
            } finally {
                lib.libvlc_media_release(media)
            }
        }.get(3, TimeUnit.SECONDS)
    }.getOrNull()

    // ---- what the screen shows ------------------------------------------------------------------

    var queue by mutableStateOf(emptyList<MediaEntry>())
        private set
    var index by mutableIntStateOf(0)
        private set
    val current: MediaEntry?
        get() = queue.getOrNull(index)

    var status by mutableStateOf(PlayStatus.IDLE)
        private set
    var problem by mutableStateOf("")
        private set
    var positionMs by mutableLongStateOf(0)
        private set
    var durationMs by mutableLongStateOf(0)
        private set
    var seekable by mutableStateOf(false)
        private set
    var volume by mutableIntStateOf(100)
        private set
    var muted by mutableStateOf(false)
        private set
    var audioTracks by mutableStateOf(emptyList<TrackChoice>())
        private set
    var audioTrack by mutableIntStateOf(-1)
        private set
    var subtitles by mutableStateOf(emptyList<TrackChoice>())
        private set
    var subtitle by mutableIntStateOf(-1)
        private set

    /** Width of the picture divided by its height as it should look on screen; 0 until known. */
    var shape by mutableFloatStateOf(0f)
        private set

    /** Goes up by one with every new picture, so the screen knows to draw again. */
    var frameTick by mutableIntStateOf(0)
        private set
    val frames = FrameHolder()

    /** Set by whoever starts something playing; music uses it, films and channels leave it off. */
    @Volatile
    var repeat = Repeat.OFF

    val hasNext: Boolean
        get() = index < queue.lastIndex
    val hasPrevious: Boolean
        get() = index > 0

    // ---- VLC's side (touched only on the control thread or in VLC's callbacks) -----------------

    @Volatile
    private var buffer: Pointer? = null
    private var bufferKeeper: Memory? = null

    @Volatile
    private var frameInfo: ImageInfo? = null

    @Volatile
    private var pitch = 0

    @Volatile
    private var frameCount = 0

    @Volatile
    private var codedWidth = 0

    @Volatile
    private var codedHeight = 0

    private val request = AtomicReference<Request?>(null)
    private var playing: MediaEntry? = null
    private var live = false
    private var wantPaused = false
    private var pausedAt = 0L
    private var openedAt = 0L
    private var lastAlive = 0L
    private var lastFrames = 0
    private var lastTime = -1L
    private var shown = false
    private var attempts = 0
    private var retryAt = 0L
    private var failed = false
    private var finished = false
    private var lastKnownMs = 0L
    private var lastKnownLength = 0L
    private var lastPlaceAt = 0L
    private var ticks = 0
    private var trackShape = 0f
    private var awake = false
    private var holdPositionUntil = 0L
    private var released = false

    private class Request(val queue: List<MediaEntry>, val index: Int, val startMs: Long)

    @Volatile
    private var soundVolume = 100

    @Volatile
    private var soundMuted = false
    private var statusHoldUntil = 0L
    private var wasShown = false

    private val formatCallback = LibVlc.FormatCallback { _, chroma, width, height, pitches, lines ->
        val sourceWidth = width.getInt(0)
        val sourceHeight = height.getInt(0)
        if (sourceWidth <= 0 || sourceHeight <= 0) return@FormatCallback 0
        // Bigger than full HD only costs time: the picture is copied and drawn by the processor.
        val scale = min(1.0, min(MAX_WIDTH.toDouble() / sourceWidth, MAX_HEIGHT.toDouble() / sourceHeight))
        val w = if (scale < 1.0) ((sourceWidth * scale).toInt() and 1.inv()).coerceAtLeast(2) else sourceWidth
        val h = if (scale < 1.0) ((sourceHeight * scale).toInt() and 1.inv()).coerceAtLeast(2) else sourceHeight
        // Rows and row lengths in multiples of 32, as VLC's faster routines expect.
        val rowBytes = ((w + 31) and 31.inv()) * 4
        val rows = (h + 31) and 31.inv()
        val memory = Memory(rowBytes.toLong() * rows + 64)
        bufferKeeper = memory
        buffer = memory.align(32)
        pitch = rowBytes
        frameInfo = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        codedWidth = sourceWidth
        codedHeight = sourceHeight
        chroma.write(0, byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), '3'.code.toByte(), '2'.code.toByte()), 0, 4)
        width.setInt(0, w)
        height.setInt(0, h)
        pitches.setInt(0, rowBytes)
        lines.setInt(0, rows)
        1
    }

    private val cleanupCallback = LibVlc.CleanupCallback {
        buffer = null
        frameInfo = null
    }

    private val lockCallback = LibVlc.LockCallback { _, planes ->
        planes.setPointer(0, buffer)
        null
    }

    private val displayCallback = LibVlc.DisplayCallback { _, _ ->
        val memory = buffer
        val info = frameInfo
        if (memory != null && info != null) {
            val image = runCatching { Pixmap.make(info, Pointer.nativeValue(memory), pitch).use { Image.makeFromPixmap(it) } }.getOrNull()
            if (image != null) {
                frames.swap(image)
                frameCount++
                frameTick++
            }
        }
    }

    init {
        val args = ArrayList<String>()
        args += listOf(
            "--ignore-config",
            "--quiet",
            "--no-video-title-show",
            "--no-osd",
            "--no-snapshot-preview",
            "--no-metadata-network-access",
            "--no-sub-autodetect-file",
        )
        args += extraArgs
        instance = lib.libvlc_new(args.size, args.toTypedArray())
            ?: lib.libvlc_new(0, null)
            ?: throw IllegalStateException("VLC's engine did not start")
        player = lib.libvlc_media_player_new(instance) ?: throw IllegalStateException("VLC's player did not start")
        // VLC calls these many times a second from its own threads; keep those threads known to Java.
        val threads = CallbackThreadInitializer(true, false, "vlc-video")
        Native.setCallbackThreadInitializer(lockCallback, threads)
        Native.setCallbackThreadInitializer(displayCallback, threads)
        lib.libvlc_video_set_callbacks(player, lockCallback, null, displayCallback, null)
        lib.libvlc_video_set_format_callbacks(player, formatCallback, cleanupCallback)
        control.scheduleWithFixedDelay({ runCatching { tick() } }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
    }

    // ---- what the screen can ask for ------------------------------------------------------------

    /** Plays [entries] starting with the one at [at]. With [startMs] it starts there instead of where it was left. */
    fun play(entries: List<MediaEntry>, at: Int, startMs: Long? = null) {
        if (entries.isEmpty()) return
        val i = at.coerceIn(0, entries.lastIndex)
        queue = entries
        index = i
        status = PlayStatus.LOADING
        problem = ""
        positionMs = 0
        durationMs = entries[i].durationMs
        seekable = false
        shape = 0f
        audioTracks = emptyList()
        subtitles = emptyList()
        // Asked for twice in a row (zapping through channels): only the newest one is opened.
        request.set(Request(entries, i, startMs ?: if (entries[i].isLiveTv) 0 else host.startAt(entries[i])))
        control.execute {
            val wanted = request.getAndSet(null) ?: return@execute
            saveNow()
            open(wanted.queue[wanted.index], wanted.startMs, fresh = true)
        }
    }

    fun toggle() {
        if (status == PlayStatus.ENDED || status == PlayStatus.FAILED) {
            retry()
            return
        }
        if (status == PlayStatus.IDLE) return
        val pause = status != PlayStatus.PAUSED
        status = if (pause) PlayStatus.PAUSED else PlayStatus.PLAYING
        statusHoldUntil = System.nanoTime() + 500_000_000L
        control.execute { pauseNow(pause) }
    }

    fun pause() {
        if (status == PlayStatus.PLAYING || status == PlayStatus.LOADING) toggle()
    }

    /** Tries the same thing again: after "could not play", or to start a finished video over. */
    fun retry() {
        val entry = current ?: return
        val over = status == PlayStatus.ENDED
        status = PlayStatus.LOADING
        problem = ""
        control.execute {
            attempts = 0
            open(entry, if (over) 0 else null, fresh = true)
        }
    }

    fun seekTo(ms: Long) {
        if (!seekable) return
        val target = ms.coerceIn(0, (durationMs - 1000).coerceAtLeast(0))
        positionMs = target
        control.execute {
            holdPositionUntil = System.nanoTime() + 700_000_000L
            lastKnownMs = target
            if (finished) {
                playing?.let { open(it, target, fresh = true) }
            } else {
                lib.libvlc_media_player_set_time(player, target)
            }
        }
    }

    fun seekBy(deltaMs: Long) = seekTo(positionMs + deltaMs)

    fun next(): Boolean {
        if (!hasNext) return false
        play(queue, index + 1)
        return true
    }

    /**
     * Puts what is waiting to be played in a new order without disturbing what is playing now:
     * [entries] must still hold the current one, at [at].
     */
    fun reorder(entries: List<MediaEntry>, at: Int) {
        val playingNow = current ?: return
        if (entries.getOrNull(at)?.uri != playingNow.uri) return
        queue = entries
        index = at
    }

    fun previous(): Boolean {
        if (!hasPrevious) return false
        play(queue, index - 1)
        return true
    }

    fun changeVolume(value: Int) {
        val v = value.coerceIn(0, 100)
        volume = v
        if (v > 0 && muted) muted = false
        soundVolume = v
        soundMuted = muted
        control.execute { applySound() }
    }

    fun toggleMute() {
        muted = !muted
        soundMuted = muted
        control.execute { applySound() }
    }

    /** Sets the loudness without playing anything, e.g. what was saved last time. */
    fun restoreSound(value: Int, mute: Boolean) {
        volume = value.coerceIn(0, 100)
        muted = mute
        soundVolume = volume
        soundMuted = mute
    }

    fun chooseAudio(id: Int) {
        audioTrack = id
        control.execute { lib.libvlc_audio_set_track(player, id) }
    }

    fun chooseSubtitle(id: Int) {
        subtitle = id
        control.execute { lib.libvlc_video_set_spu(player, id) }
    }

    /** Stops and forgets what was playing. */
    fun close() {
        queue = emptyList()
        index = 0
        status = PlayStatus.IDLE
        problem = ""
        positionMs = 0
        durationMs = 0
        shape = 0f
        request.set(null)
        control.execute {
            saveNow()
            playing = null
            lib.libvlc_media_player_stop(player)
            frames.swap(null)
            frameTick++
            keepAwake(false)
        }
    }

    /** Shuts VLC down for good (the app is closing). Waits a moment for it, but not for ever. */
    fun release() {
        if (released) return
        released = true
        val done = control.submit {
            saveNow()
            playing = null
            runCatching { lib.libvlc_media_player_stop(player) }
            keepAwake(false)
            runCatching { lib.libvlc_media_player_release(player) }
            runCatching { lib.libvlc_release(instance) }
        }
        runCatching { done.get(3, TimeUnit.SECONDS) }
        control.shutdownNow()
    }

    // ---- on the control thread -------------------------------------------------------------------

    /** [startMs] null means "from where it had got to". */
    private fun open(entry: MediaEntry, startMs: Long?, fresh: Boolean) {
        lib.libvlc_media_player_stop(player)
        val from = if (entry.isLiveTv) 0 else startMs ?: lastKnownMs
        if (fresh) {
            frames.swap(null)
            frameTick++
            attempts = 0
            wasShown = false
        }
        playing = entry
        live = entry.isLiveTv
        wantPaused = false
        failed = false
        finished = false
        shown = false
        trackShape = 0f
        codedWidth = 0
        codedHeight = 0
        lastFrames = frameCount
        lastTime = -1
        retryAt = 0
        val now = System.nanoTime()
        openedAt = now
        lastAlive = now
        lastKnownMs = from
        if (fresh) lastKnownLength = entry.durationMs
        val address = entry.uri
        val local = !address.contains("://")
        val media = (if (local) lib.libvlc_media_new_path(instance, File(address).absolutePath) else lib.libvlc_media_new_location(instance, address))
        if (media == null) {
            fail("This could not be opened.")
            return
        }
        if (!local) {
            lib.libvlc_media_add_option(media, ":http-user-agent=" + (host.agent(entry) ?: DEFAULT_AGENT))
            lib.libvlc_media_add_option(media, ":http-reconnect")
            lib.libvlc_media_add_option(media, ":network-caching=" + if (live) host.reserveMs().coerceIn(1000, 20_000) else 3000)
        }
        if (from > 0) lib.libvlc_media_add_option(media, ":start-time=" + (from / 1000.0))
        lib.libvlc_media_player_set_media(player, media)
        lib.libvlc_media_release(media)
        if (lib.libvlc_media_player_play(player) != 0) {
            fail(if (live) "This channel could not be opened." else "This video could not be opened.")
            return
        }
        post {
            if (current?.uri == entry.uri) {
                status = PlayStatus.LOADING
                problem = ""
            }
        }
    }

    private fun pauseNow(pause: Boolean) {
        val entry = playing ?: return
        if (failed || finished) return
        if (pause) {
            wantPaused = true
            pausedAt = System.nanoTime()
            lib.libvlc_media_player_set_pause(player, 1)
            saveNow()
            return
        }
        val away = System.nanoTime() - pausedAt
        wantPaused = false
        lastAlive = System.nanoTime()
        // A live channel does not wait: after a real break, join the broadcast where it is now.
        if (live && away > LIVE_PAUSE_LIMIT_NS) open(entry, null, fresh = false) else lib.libvlc_media_player_set_pause(player, 0)
    }

    private fun applySound() {
        if (playing == null) return
        lib.libvlc_audio_set_volume(player, soundVolume)
        lib.libvlc_audio_set_mute(player, if (soundMuted) 1 else 0)
    }

    /** Looks at how VLC is getting on, four times a second, and tells the screen. */
    private fun tick() {
        val entry = playing ?: return
        if (failed) return
        val now = System.nanoTime()
        if (retryAt != 0L) {
            if (now >= retryAt) open(entry, if (live) null else lastKnownMs, fresh = false)
            return
        }
        if (finished) return
        ticks++
        val state = lib.libvlc_media_player_get_state(player)
        val time = lib.libvlc_media_player_get_time(player)
        val length = lib.libvlc_media_player_get_length(player)
        val count = frameCount
        val moving = count != lastFrames || (time > 0 && time != lastTime)
        lastFrames = count
        lastTime = time
        if (moving) {
            lastAlive = now
            if (!shown) {
                shown = true
                wasShown = true
                // The speakers only exist once something plays, so the loudness is set now.
                applySound()
            }
            if (attempts != 0 && now - openedAt > 20_000_000_000L) attempts = 0
        }
        if (time > 0) lastKnownMs = time
        if (length > 0) lastKnownLength = length

        val quiet = now - lastAlive
        when {
            state == LibVlc.ERROR -> {
                stumble(entry, if (live) "This channel is not answering." else "This video could not be played.")
                return
            }
            state == LibVlc.ENDED || state == LibVlc.STOPPED -> {
                if (live) {
                    stumble(entry, "This channel is not answering.")
                } else if (shown && lastKnownLength > 0 && lastKnownMs < lastKnownLength - CUT_SHORT_MS) {
                    // The line dropped in the middle of the video: carry on from where it stopped.
                    stumble(entry, "The connection was lost.")
                } else if (!shown) {
                    fail("This video could not be played.")
                } else {
                    end(entry)
                }
                return
            }
            live && !wantPaused && quiet > (if (shown) LIVE_STALL_NS else LIVE_START_NS) + host.reserveMs() * 1_000_000L -> {
                stumble(entry, "This channel is not answering.")
                return
            }
            !live && !wantPaused && !shown && quiet > VIDEO_START_NS -> {
                stumble(entry, "This video is not answering.")
                return
            }
        }

        // The shape of the picture is wanted quickly; after a few seconds, look only now and then.
        if (ticks % 8 == 0 || (shown && trackShape == 0f && now - openedAt < 8_000_000_000L)) readTracks()
        keepAwake(state == LibVlc.PLAYING && !wantPaused)
        if (!live && shown && !wantPaused && now - lastPlaceAt > 5_000_000_000L) saveNow()

        val canSeek = !live && lib.libvlc_media_player_is_seekable(player) != 0
        val loading = !wantPaused && (state == LibVlc.OPENING || state == LibVlc.BUFFERING || !shown || quiet > 1_500_000_000L)
        val next = when {
            wantPaused -> PlayStatus.PAUSED
            loading -> PlayStatus.LOADING
            else -> PlayStatus.PLAYING
        }
        val width = codedWidth
        val height = codedHeight
        val look = when {
            trackShape > 0f -> trackShape
            width > 0 && height > 0 -> width.toFloat() / height * guessPixelShape(width, height)
            else -> 0f
        }
        val hold = now < holdPositionUntil
        val audio = audioList
        val subs = subtitleList
        val audioNow = if (shown) lib.libvlc_audio_get_track(player) else -1
        val subNow = if (shown) lib.libvlc_video_get_spu(player) else -1
        post {
            if (current?.uri != entry.uri) return@post
            // The screen already shows what the viewer just asked for (pause, play): do not undo it for a moment.
            if (System.nanoTime() >= statusHoldUntil) status = next
            if (!hold) positionMs = if (live) 0 else time.coerceAtLeast(0)
            if (length > 0) durationMs = length
            seekable = canSeek
            if (look > 0f) shape = look
            if (audio != audioTracks) audioTracks = audio
            if (subs != subtitles) subtitles = subs
            if (shown) {
                audioTrack = audioNow
                subtitle = subNow
            }
        }
    }

    private var audioList = emptyList<TrackChoice>()
    private var subtitleList = emptyList<TrackChoice>()

    private fun readTracks() {
        audioList = describe(lib.libvlc_audio_get_track_description(player))
        subtitleList = describe(lib.libvlc_video_get_spu_description(player))
        if (trackShape == 0f) trackShape = readShape()
    }

    /** Walks one of VLC's lists of tracks: each link holds a number, a name and the next link. */
    private fun describe(list: Pointer?): List<TrackChoice> {
        if (list == null) return emptyList()
        val out = ArrayList<TrackChoice>()
        var at: Pointer? = list
        var guard = 0
        while (at != null && guard++ < 64) {
            val id = at.getInt(0)
            val name = at.getPointer(Native.POINTER_SIZE.toLong())?.getString(0, "UTF-8").orEmpty()
            out += TrackChoice(id, name.ifBlank { "Track ${out.size + 1}" })
            at = at.getPointer(Native.POINTER_SIZE * 2L)
        }
        lib.libvlc_track_description_list_release(list)
        return out
    }

    /**
     * The shape the picture should have on screen (width divided by height), as the stream itself
     * describes it; 0 when it does not say yet. TV pictures are often stored narrower than they
     * are shown (720 dots across for a wide picture), and the stream says how far to stretch them.
     */
    private fun readShape(): Float {
        if (Native.POINTER_SIZE != 8) return 0f
        val media = lib.libvlc_media_player_get_media(player) ?: return 0f
        try {
            val holder = PointerByReference()
            val count = lib.libvlc_media_tracks_get(media, holder)
            val tracks = holder.value ?: return 0f
            try {
                for (i in 0 until count) {
                    val track = tracks.getPointer(i * 8L) ?: continue
                    if (track.getInt(12) != 1) continue
                    val video = track.getPointer(24) ?: continue
                    return shapeOf(video.getInt(4), video.getInt(0), video.getInt(8), video.getInt(12))
                }
            } finally {
                lib.libvlc_media_tracks_release(tracks, count)
            }
        } finally {
            lib.libvlc_media_release(media)
        }
        return 0f
    }

    /** Something went wrong: try again a few times before giving up with [message]. */
    private fun stumble(entry: MediaEntry, message: String) {
        attempts++
        val allowed = if (!live) VIDEO_ATTEMPTS else if (wasShown) LIVE_ATTEMPTS else LIVE_FIRST_ATTEMPTS
        if (attempts > allowed || (!live && !wasShown)) {
            fail(message)
            return
        }
        lib.libvlc_media_player_stop(player)
        retryAt = System.nanoTime() + min(attempts, 4) * 1_200_000_000L
        post { if (current?.uri == entry.uri && status != PlayStatus.PAUSED) status = PlayStatus.LOADING }
    }

    private fun fail(message: String) {
        val entry = playing
        failed = true
        retryAt = 0
        runCatching { lib.libvlc_media_player_stop(player) }
        keepAwake(false)
        post {
            if (entry == null || current?.uri == entry.uri) {
                status = PlayStatus.FAILED
                problem = message
            }
        }
    }

    private fun end(entry: MediaEntry) {
        finished = true
        keepAwake(false)
        val length = lastKnownLength
        post {
            // Watched to the end: nothing to carry on from next time.
            host.place(entry, length, length)
            if (current?.uri != entry.uri) return@post
            when {
                repeat == Repeat.ONE -> play(queue, index, 0L)
                hasNext -> next()
                repeat == Repeat.ALL -> play(queue, 0, 0L)
                else -> status = PlayStatus.ENDED
            }
        }
    }

    private fun saveNow() {
        val entry = playing ?: return
        if (live || !shown || finished) return
        lastPlaceAt = System.nanoTime()
        val at = lastKnownMs
        val length = lastKnownLength
        if (length > 0) post { host.place(entry, at, length) }
    }

    /** While a video plays the computer should not dim the screen or go to sleep (Windows). */
    private fun keepAwake(on: Boolean) {
        if (on == awake) return
        awake = on
        if (!Platform.isWindows()) return
        runCatching {
            val flags = if (on) ES_CONTINUOUS or ES_DISPLAY_REQUIRED or ES_SYSTEM_REQUIRED else ES_CONTINUOUS
            power.SetThreadExecutionState(flags)
        }
    }

    private fun post(block: () -> Unit) {
        if (!released) SwingUtilities.invokeLater(block)
    }

    @Suppress("FunctionName")
    private interface Power : com.sun.jna.Library {
        fun SetThreadExecutionState(flags: Int): Int
    }

    private val power: Power by lazy { Native.load("kernel32", Power::class.java) }

    companion object {
        const val DEFAULT_AGENT = "VLC/3.0.21 LibVLC/3.0.21"
        private const val POLL_MS = 250L
        private const val MAX_WIDTH = 1920
        private const val MAX_HEIGHT = 1080
        private const val LIVE_ATTEMPTS = 5
        private const val LIVE_FIRST_ATTEMPTS = 2
        private const val VIDEO_ATTEMPTS = 3
        private const val LIVE_START_NS = 20_000_000_000L
        private const val LIVE_STALL_NS = 10_000_000_000L
        private const val VIDEO_START_NS = 30_000_000_000L
        private const val LIVE_PAUSE_LIMIT_NS = 15_000_000_000L
        private const val CUT_SHORT_MS = 30_000L
        private const val ES_CONTINUOUS = 0x80000000.toInt()
        private const val ES_SYSTEM_REQUIRED = 0x00000001
        private const val ES_DISPLAY_REQUIRED = 0x00000002

        /**
         * The shape on screen of a picture [width] by [height] dots whose dots are [across] wide
         * for every [down] tall. 0 when the size is unknown or the answer is not believable.
         */
        fun shapeOf(width: Int, height: Int, across: Int, down: Int): Float {
            if (width <= 0 || height <= 0) return 0f
            val stretch = if (across > 0 && down > 0) across.toFloat() / down else 1f
            val shape = width.toFloat() / height * (if (stretch in 0.3f..3.5f) stretch else 1f)
            return if (shape in 0.2f..6f) shape else 0f
        }

        /** When the stream does not say: the usual TV sizes that are stored narrow and shown wide. */
        fun guessPixelShape(width: Int, height: Int): Float = when {
            height == 576 && width in setOf(720, 704, 544, 528, 480) -> (16f / 9f) / (width.toFloat() / height)
            height == 480 && width in setOf(720, 704, 544, 528) -> (16f / 9f) / (width.toFloat() / height)
            height == 1080 && width in setOf(1440, 1280) -> (16f / 9f) / (width.toFloat() / height)
            else -> 1f
        }
    }
}
