package com.noexcusehere.mashwave.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.noexcusehere.mashwave.MainActivity
import com.noexcusehere.mashwave.R
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.Mashup
import com.noexcusehere.mashwave.data.MediaEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.pow

data class DeckState(
    val entry: MediaEntry? = null,
    val playing: Boolean = false,
    val tempo: Float = 1f,
    /** Pitch shift in semitones, -12..+12. */
    val pitch: Float = 0f,
    val volume: Float = 1f,
    /** Beats per minute of the original track, 0 when unknown. */
    val bpm: Float = 0f,
    val analysing: Boolean = false,
    /** Where this deck starts when the mix is (re)started. */
    val cueMs: Long = 0,
) {
    /** The tempo you actually hear once the speed change is applied. */
    val effectiveBpm: Float
        get() = bpm * tempo
}

data class MashupState(
    val decks: List<DeckState> = listOf(DeckState(), DeckState()),
    /** 0 = only deck A, 0.5 = both at full volume, 1 = only deck B. */
    val crossfade: Float = 0.5f,
    val loadedId: String? = null,
    val loadedName: String? = null,
) {
    val anyPlaying: Boolean
        get() = decks.any { it.playing }
    val ready: Boolean
        get() = decks.all { it.entry != null }
}

/**
 * Two independent players ("decks") that run at the same time so two songs can be layered:
 * each has its own tempo, pitch and volume, with a crossfader between them.
 */
class MashupEngine(private val context: Context, private val scope: CoroutineScope) {
    private val players = arrayOfNulls<ExoPlayer>(2)
    private val analysis = arrayOfNulls<Job>(2)
    private val taps = Array(2) { ArrayList<Long>() }
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var hasFocus = false
    private var wasPlaying = false

    private val _state = MutableStateFlow(MashupState())
    val state: StateFlow<MashupState> = _state

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setOnAudioFocusChangeListener { change ->
            // Another player (including this app's own main player) took over the speakers.
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                pauseBoth()
            }
        }
        .build()

    private fun deck(i: Int) = _state.value.decks[i]

    private fun editDeck(i: Int, block: (DeckState) -> DeckState) = _state.update { s ->
        s.copy(decks = s.decks.mapIndexed { index, d -> if (index == i) block(d) else d })
    }

    private fun player(i: Int): ExoPlayer = players[i] ?: ExoPlayer.Builder(context, mashwaveRenderers(context))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ false,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()
        .also { p ->
            // Decks are audio only, even when a video file is loaded.
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    editDeck(i) { it.copy(playing = playWhenReady) }
                    onPlayingChanged()
                }

                override fun onPlayerError(error: PlaybackException) {
                    val title = deck(i).entry?.title ?: "this file"
                    context.app.say("Deck ${if (i == 0) "A" else "B"} can't play \"$title\"")
                    p.playWhenReady = false
                }
            })
            players[i] = p
        }

    // ---- loading --------------------------------------------------------------------------

    fun load(i: Int, entry: MediaEntry, bpm: Float = 0f, startMs: Long = 0) {
        val p = player(i)
        p.playWhenReady = false
        p.setMediaItem(MediaItem.fromUri(entry.uri), startMs)
        p.prepare()
        taps[i].clear()
        editDeck(i) { it.copy(entry = entry, playing = false, bpm = bpm, analysing = false, cueMs = startMs) }
        _state.update { it.copy(loadedId = null, loadedName = null) }
        applyParameters(i)
        applyVolumes()
        if (bpm <= 0f) detectBpm(i)
    }

    fun clear(i: Int) {
        analysis[i]?.cancel()
        players[i]?.let {
            it.playWhenReady = false
            it.clearMediaItems()
        }
        editDeck(i) { DeckState() }
        _state.update { it.copy(loadedId = null, loadedName = null) }
    }

    private fun detectBpm(i: Int) {
        val entry = deck(i).entry ?: return
        analysis[i]?.cancel()
        editDeck(i) { it.copy(analysing = true) }
        analysis[i] = scope.launch {
            val bpm = withContext(Dispatchers.Default) {
                runCatching { BpmAnalyzer.analyze(context, entry.uri) }.getOrNull()
            }
            if (deck(i).entry?.uri == entry.uri) {
                editDeck(i) { it.copy(analysing = false, bpm = if (it.bpm > 0f) it.bpm else (bpm ?: 0f)) }
            }
        }
    }

    // ---- transport ------------------------------------------------------------------------

    fun toggle(i: Int) {
        if (deck(i).entry == null) return
        if (deck(i).playing) {
            players[i]?.playWhenReady = false
        } else if (requestFocus()) {
            start(i)
        }
    }

    private fun start(i: Int) {
        val p = player(i)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }

    fun playBoth() {
        if (!requestFocus()) return
        for (i in 0..1) if (deck(i).entry != null) start(i)
    }

    fun pauseBoth() {
        for (i in 0..1) players[i]?.playWhenReady = false
    }

    /** Jumps both decks back to their start points and plays them together. */
    fun restartBoth() {
        for (i in 0..1) players[i]?.seekTo(deck(i).cueMs)
        playBoth()
    }

    /** Marks the current position as this deck's start point for the mix. */
    fun setCue(i: Int) {
        val at = position(i)
        editDeck(i) { it.copy(cueMs = at) }
    }

    fun seekTo(i: Int, positionMs: Long) {
        players[i]?.seekTo(positionMs.coerceAtLeast(0))
    }

    fun nudge(i: Int, deltaMs: Long) {
        val p = players[i] ?: return
        p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0))
    }

    fun position(i: Int): Long = players[i]?.currentPosition?.coerceAtLeast(0) ?: 0

    fun duration(i: Int): Long =
        players[i]?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: (deck(i).entry?.durationMs ?: 0)

    // ---- tuning ---------------------------------------------------------------------------

    fun setTempo(i: Int, tempo: Float) {
        editDeck(i) { it.copy(tempo = tempo.coerceIn(0.5f, 2f)) }
        applyParameters(i)
    }

    fun setPitch(i: Int, semitones: Float) {
        editDeck(i) { it.copy(pitch = semitones.coerceIn(-12f, 12f)) }
        applyParameters(i)
    }

    fun setVolume(i: Int, volume: Float) {
        editDeck(i) { it.copy(volume = volume.coerceIn(0f, 1f)) }
        applyVolumes()
    }

    fun setCrossfade(value: Float) {
        _state.update { it.copy(crossfade = value.coerceIn(0f, 1f)) }
        applyVolumes()
    }

    fun setBpm(i: Int, bpm: Float) {
        editDeck(i) { it.copy(bpm = bpm.coerceIn(0f, 300f)) }
    }

    /** Tap along with the beat a few times to set the BPM by hand. */
    fun tap(i: Int) {
        val now = System.nanoTime() / 1_000_000
        val list = taps[i]
        if (list.isNotEmpty() && now - list.last() > 2500) list.clear()
        list += now
        if (list.size > 8) list.removeAt(0)
        if (list.size >= 3) {
            val interval = (list.last() - list.first()).toFloat() / (list.size - 1)
            // What the user hears is already sped up / slowed down, so undo that.
            if (interval > 0) setBpm(i, (60_000f / interval) / deck(i).tempo)
        }
    }

    /** Changes deck [i]'s tempo so its beat matches the other deck. Returns false if a BPM is missing. */
    fun matchTempo(i: Int): Boolean {
        val mine = deck(i)
        val other = deck(1 - i)
        if (mine.bpm <= 0f || other.bpm <= 0f) return false
        var target = other.effectiveBpm / mine.bpm
        // Prefer the half / double time relationship when it needs less stretching.
        while (target > 1.45f) target /= 2f
        while (target < 0.7f) target *= 2f
        setTempo(i, target)
        return true
    }

    fun resetDeck(i: Int) {
        editDeck(i) { it.copy(tempo = 1f, pitch = 0f, volume = 1f) }
        applyParameters(i)
        applyVolumes()
    }

    private fun applyParameters(i: Int) {
        val d = deck(i)
        players[i]?.playbackParameters = PlaybackParameters(d.tempo, 2f.pow(d.pitch / 12f))
    }

    private fun applyVolumes() {
        val s = _state.value
        val gains = crossfadeGains(s.crossfade)
        players[0]?.volume = s.decks[0].volume * gains.first
        players[1]?.volume = s.decks[1].volume * gains.second
    }

    // ---- saving / loading mixes -----------------------------------------------------------

    fun snapshot(name: String, existingId: String? = null): Mashup? {
        val s = _state.value
        val a = s.decks[0].entry ?: return null
        val b = s.decks[1].entry ?: return null
        return Mashup(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "${a.title} × ${b.title}" },
            a = a,
            b = b,
            tempoA = s.decks[0].tempo,
            tempoB = s.decks[1].tempo,
            pitchA = s.decks[0].pitch,
            pitchB = s.decks[1].pitch,
            volumeA = s.decks[0].volume,
            volumeB = s.decks[1].volume,
            crossfade = s.crossfade,
            startA = s.decks[0].cueMs,
            startB = s.decks[1].cueMs,
            bpmA = s.decks[0].bpm,
            bpmB = s.decks[1].bpm,
            createdAt = System.currentTimeMillis(),
        )
    }

    fun open(mashup: Mashup, autoPlay: Boolean) {
        pauseBoth()
        load(0, mashup.a, mashup.bpmA, mashup.startA)
        load(1, mashup.b, mashup.bpmB, mashup.startB)
        _state.update { s ->
            s.copy(
                decks = listOf(
                    s.decks[0].copy(tempo = mashup.tempoA, pitch = mashup.pitchA, volume = mashup.volumeA),
                    s.decks[1].copy(tempo = mashup.tempoB, pitch = mashup.pitchB, volume = mashup.volumeB),
                ),
                crossfade = mashup.crossfade,
                loadedId = mashup.id,
                loadedName = mashup.name,
            )
        }
        applyParameters(0)
        applyParameters(1)
        applyVolumes()
        if (autoPlay) playBoth()
    }

    fun markSaved(mashup: Mashup) {
        _state.update { it.copy(loadedId = mashup.id, loadedName = mashup.name) }
    }

    // ---- audio focus + keeping alive in the background ------------------------------------

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun onPlayingChanged() {
        val playing = _state.value.anyPlaying
        if (playing && !wasPlaying) {
            // The service stops itself again once both decks are paused.
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, MashupService::class.java))
            }
        }
        wasPlaying = playing
        if (!playing && hasFocus) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            hasFocus = false
        }
    }

    companion object {
        /**
         * Crossfader curve made for layering: in the middle both decks are at full volume,
         * and each side only fades out over its own half of the slider.
         */
        fun crossfadeGains(x: Float): Pair<Float, Float> {
            val c = x.coerceIn(0f, 1f)
            val a = (2f * (1f - c)).coerceIn(0f, 1f)
            val b = (2f * c).coerceIn(0f, 1f)
            return a to b
        }
    }
}

/** Keeps a playing mashup alive when the app is in the background or the screen is off. */
class MashupService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.mashup.pauseBoth()
        }
        // Always enter the foreground first: Android requires it after startForegroundService().
        val notification = buildNotification(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (watcher == null) {
            watcher = app.scope.launch {
                app.mashup.state
                    .map { s -> Triple(s.anyPlaying, s.decks.map { it.playing to it.entry?.title }, s.loadedName) }
                    .distinctUntilChanged()
                    .collect { (playing, _, _) ->
                    if (playing) {
                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.notify(NOTIFICATION_ID, buildNotification(this@MashupService))
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.noexcusehere.mashwave.mashup.STOP"
        private const val CHANNEL = "mashup"
        private const val NOTIFICATION_ID = 4201

        private fun buildNotification(context: Context): Notification {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Mashup playback", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown while a two-deck mashup is playing"
                        setShowBadge(false)
                    },
                )
            }
            val state = context.app.mashup.state.value
            val names = state.decks.filter { it.playing }.mapNotNull { it.entry?.title }.joinToString("  ×  ")
            val open = PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_MASHUP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stop = PendingIntent.getService(
                context,
                2,
                Intent(context, MashupService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mashwave)
                .setContentTitle(state.loadedName ?: "Mashup playing")
                .setContentText(names.ifBlank { "Two decks" })
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stat_mashwave),
                        "Pause",
                        stop,
                    ).build(),
                )
                .build()
        }
    }
}
