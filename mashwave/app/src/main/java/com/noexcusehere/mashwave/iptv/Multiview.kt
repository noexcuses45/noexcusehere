package com.noexcusehere.mashwave.iptv

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.playback.CushionedLoadControl
import com.noexcusehere.mashwave.playback.mashwaveRenderers

/**
 * One picture in the multiview: a live channel with a player of its own. It looks after itself
 * the way the main player looks after a channel - follows forwarding, redials when the line
 * drops, and says plainly when it cannot go on.
 */
class MultiviewTile(context: Context, val item: TvItem, private val address: String, agent: String?) {
    /** Still fetching, or redialling. */
    var loading by mutableStateOf(true)
        private set

    /** Why this picture stopped, when it has. */
    var problem by mutableStateOf<String?>(null)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val retryTimes = ArrayDeque<Long>()
    private var triedAsList = false
    private var released = false

    val player: ExoPlayer = run {
        val web = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("User-Agent" to (agent ?: TvNet.userAgent)))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        ExoPlayer.Builder(context, mashwaveRenderers(context))
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, web)))
            .setLoadControl(CushionedLoadControl { TvNet.cushion })
            // Four players asking for the speaker would keep silencing each other; the multiview asks once for all.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    loading = false
                    problem = null
                }
                Player.STATE_BUFFERING -> loading = true
                // A live channel never finishes: the line closed, so dial again.
                Player.STATE_ENDED -> if (mayRetry()) redial(800L * retryTimes.size) else fail("This channel keeps dropping out")
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val unknownKind = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
            if (unknownKind && !triedAsList) {
                // Some addresses are a list of short pieces without saying so.
                triedAsList = true
                player.setMediaItem(MediaItem.Builder().setUri(address).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                player.prepare()
                return
            }
            val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
            val refused = status == 401 || status == 403 || status == 404 || status == 429 || status == 451
            val network = error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
                error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
            if (network && !refused && mayRetry()) {
                redial(1200L * retryTimes.size)
                return
            }
            fail(
                when {
                    status == 401 || status == 403 || status == 429 -> "The service refused this one. It may only allow so many screens at a time."
                    status == 404 -> "Off air right now"
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ->
                        "This phone can't show another picture at the same time"
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED || unknownKind -> "This phone can't play this channel's format"
                    else -> "This channel stopped"
                },
            )
        }
    }

    init {
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(address))
        player.prepare()
        player.playWhenReady = true
    }

    private fun mayRetry(): Boolean {
        val now = System.currentTimeMillis()
        while (retryTimes.isNotEmpty() && now - retryTimes.first() > 60_000) retryTimes.removeFirst()
        if (retryTimes.size >= 5) return false
        retryTimes.addLast(now)
        return true
    }

    private fun fail(message: String) {
        loading = false
        problem = message
    }

    private fun redial(delayMs: Long) {
        loading = true
        handler.postDelayed({
            if (!released) {
                player.stop()
                player.seekToDefaultPosition()
                player.prepare()
            }
        }, delayMs)
    }

    /** Starts over after it gave up. */
    fun retry() {
        retryTimes.clear()
        problem = null
        redial(0)
    }

    var hasSound: Boolean = false
        set(value) {
            field = value
            player.volume = if (value) 1f else 0f
        }

    /** Rejoins the broadcast after the app comes back, or stops fetching while it is away. */
    fun setActive(active: Boolean) {
        if (released) return
        if (active) {
            if (!player.playWhenReady) {
                player.playWhenReady = true
                if (problem == null) redial(0)
            }
        } else {
            handler.removeCallbacksAndMessages(null)
            player.playWhenReady = false
            player.stop()
        }
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        player.removeListener(listener)
        player.release()
    }
}

/**
 * Up to four live channels playing side by side, one of them with sound. Every picture is a
 * stream of its own, so the service has to allow that many at once.
 */
class Multiview(private val context: Context) {
    val tiles = mutableStateListOf<MultiviewTile>()

    /** Which picture is heard. */
    var sound by mutableIntStateOf(0)
        private set

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null
    private var silenced = false

    private val tv: TvRepository
        get() = context.app.tv

    val isFull: Boolean
        get() = tiles.size >= MAX

    fun has(item: TvItem): Boolean = tiles.any { it.item.id == item.id }

    /**
     * Opens the multiview: the main player lets go of its channel (a service counts every open
     * stream), last time's pictures come back, and [first] - the channel that was on - joins them.
     */
    fun begin(first: TvItem?) {
        val player = context.app.player
        if (player.state.value.current?.isTv == true) player.stop()
        if (tiles.isEmpty()) {
            val remembered = tv.multiviewItems()
            val start = if (first != null) listOf(first) + remembered.filterNot { it.id == first.id } else remembered
            start.take(MAX).forEach { add(it, announce = false) }
        } else if (first != null && !has(first)) {
            if (isFull) remove(tiles.lastIndex)
            add(first, announce = false)
        }
        if (first != null) tiles.indexOfFirst { it.item.id == first.id }.takeIf { it >= 0 }?.let { giveSound(it) }
    }

    /** Adds a channel. Returns false when there are four already, or it is one of them. */
    fun add(item: TvItem, announce: Boolean = true): Boolean {
        if (isFull || has(item) || item.section != TvSection.LIVE) return false
        val entry = tv.entry(item) ?: return false
        if (tiles.isEmpty()) takeSpeaker()
        val tile = MultiviewTile(context.applicationContext, item, entry.uri, tv.agentFor(entry))
        tiles += tile
        tile.hasSound = !silenced && tiles.size - 1 == sound
        remember()
        val allowed = tv.state.value.active?.maxConnections ?: 0
        if (announce && allowed in 1 until tiles.size) {
            context.app.say("Your service allows $allowed ${if (allowed == 1) "screen" else "screens"} at a time, so this channel may not start.")
        }
        return true
    }

    fun remove(index: Int) {
        val tile = tiles.getOrNull(index) ?: return
        tiles.removeAt(index)
        tile.release()
        if (sound >= tiles.size) sound = (tiles.size - 1).coerceAtLeast(0) else if (index < sound) sound--
        applySound()
        remember()
        if (tiles.isEmpty()) dropSpeaker()
    }

    /** Replaces the channel in one of the pictures. */
    fun replace(index: Int, item: TvItem) {
        if (index !in tiles.indices || has(item)) return
        val entry = tv.entry(item) ?: return
        val old = tiles[index]
        tiles[index] = MultiviewTile(context.applicationContext, item, entry.uri, tv.agentFor(entry))
        old.release()
        applySound()
        remember()
    }

    fun giveSound(index: Int) {
        if (index !in tiles.indices) return
        sound = index
        // Asking to hear a picture also asks for the speaker back, if something else had taken it.
        if (silenced) focus?.let { if (audio.requestAudioFocus(it) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) silenced = false }
        applySound()
    }

    private fun applySound() {
        tiles.forEachIndexed { i, tile -> tile.hasSound = !silenced && i == sound }
    }

    private fun remember() = tv.rememberMultiview(tiles.map { it.item })

    /** Stops fetching while the app is out of sight; rejoins when it is back. */
    fun setActive(active: Boolean) {
        tiles.forEach { it.setActive(active) }
    }

    /** Leaves the multiview: every stream is closed. The channels are remembered for next time. */
    fun close() {
        tiles.forEach { it.release() }
        tiles.clear()
        dropSpeaker()
    }

    // ---- one request for the speaker on behalf of all four -----------------------------------

    private fun takeSpeaker() {
        if (focus != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                PlatformAudioAttributes.Builder().setUsage(PlatformAudioAttributes.USAGE_MEDIA).setContentType(PlatformAudioAttributes.CONTENT_TYPE_MOVIE).build(),
            )
            .setOnAudioFocusChangeListener({ change ->
                // A phone call or another player wants the speaker: go quiet, and come back when it is free.
                silenced = change != AudioManager.AUDIOFOCUS_GAIN
                applySound()
            }, Handler(Looper.getMainLooper()))
            .build()
        focus = request
        silenced = false
        audio.requestAudioFocus(request)
    }

    private fun dropSpeaker() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        silenced = false
    }

    companion object {
        const val MAX = 4

        /** How the pictures are laid out: rows and columns for [count] cells. One fills the screen, two sit side by side, more make a 2 x 2 grid. */
        fun grid(count: Int): Pair<Int, Int> = when {
            count <= 1 -> 1 to 1
            count == 2 -> 1 to 2
            else -> 2 to 2
        }
    }
}
