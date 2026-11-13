package com.noexcusehere.mashwave.playback

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.noexcusehere.mashwave.data.MediaEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val connected: Boolean = false,
    val current: MediaEntry? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<MediaEntry> = emptyList(),
    val index: Int = 0,
    val speed: Float = 1f,
    val hasVideo: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val sleepTimerEndsAt: Long = 0,
)

/** One selectable audio or subtitle track inside the current file. */
data class TrackOption(
    val label: String,
    val selected: Boolean,
    val groupIndex: Int,
    val trackIndex: Int,
)

/**
 * The UI's handle on the playback service. Everything the screens do to the player goes
 * through here, and [state] is what they draw.
 */
class PlayerConnection(private val context: Context, private val scope: CoroutineScope) {
    private var future: ListenableFuture<MediaController>? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var sleepJob: Job? = null

    /** Exposed for the video surface, which needs the real player object. */
    var controller: MediaController? by mutableStateOf(null)
        private set

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val pendingController = MediaController.Builder(context, token).buildAsync()
        future = pendingController
        pendingController.addListener(
            {
                try {
                    val c = pendingController.get()
                    controller = c
                    c.addListener(listener)
                    refresh(c, queueChanged = true)
                    while (pending.isNotEmpty()) pending.removeFirst().invoke(c)
                } catch (e: Exception) {
                    future = null
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun release() {
        controller?.let {
            it.removeListener(listener)
            it.release()
        }
        controller = null
        future = null
        _state.update { it.copy(connected = false) }
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            block(c)
        } else {
            pending.addLast(block)
            connect()
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(player, queueChanged = events.contains(Player.EVENT_TIMELINE_CHANGED))
        }
    }

    private fun refresh(player: Player, queueChanged: Boolean) {
        val queue = if (queueChanged) {
            (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toEntry() }
        } else {
            _state.value.queue
        }
        val size = player.videoSize
        _state.update {
            it.copy(
                connected = true,
                current = player.currentMediaItem?.toEntry(),
                isPlaying = !Util.shouldShowPlayButton(player),
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                queue = queue,
                index = player.currentMediaItemIndex,
                speed = player.playbackParameters.speed,
                hasVideo = player.currentTracks.groups.any { g -> g.type == C.TRACK_TYPE_VIDEO },
                videoWidth = size.width,
                videoHeight = size.height,
            )
        }
    }

    // ---- starting playback --------------------------------------------------------------

    fun play(entries: List<MediaEntry>, startIndex: Int = 0, shuffle: Boolean? = null) {
        if (entries.isEmpty()) return
        withController { c ->
            if (shuffle != null) c.shuffleModeEnabled = shuffle
            val index = if (shuffle == true && startIndex < 0) entries.indices.random() else startIndex.coerceIn(0, entries.lastIndex)
            c.setMediaItems(entries.map { it.toMediaItem() }, index, 0L)
            c.prepare()
            c.play()
        }
    }

    fun shuffleAll(entries: List<MediaEntry>) = play(entries, startIndex = -1, shuffle = true)

    fun playNext(entry: MediaEntry) = withController { c ->
        if (c.mediaItemCount == 0) {
            c.setMediaItem(entry.toMediaItem())
            c.prepare()
            c.play()
        } else {
            c.addMediaItem((c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount), entry.toMediaItem())
        }
    }

    fun addToQueue(entries: List<MediaEntry>) = withController { c ->
        val wasEmpty = c.mediaItemCount == 0
        c.addMediaItems(entries.map { it.toMediaItem() })
        if (wasEmpty) {
            c.prepare()
            c.play()
        }
    }

    // ---- transport ------------------------------------------------------------------------

    fun togglePlay() = withController { c -> Util.handlePlayPauseButtonAction(c) }

    fun pause() {
        controller?.pause()
    }

    fun next() = withController { c -> if (c.hasNextMediaItem()) c.seekToNextMediaItem() }

    fun previous() = withController { c -> c.seekToPrevious() }

    fun seekTo(positionMs: Long) = withController { c -> c.seekTo(positionMs.coerceAtLeast(0)) }

    fun seekBy(deltaMs: Long) = withController { c ->
        val duration = c.duration
        var target = (c.currentPosition + deltaMs).coerceAtLeast(0)
        if (duration != C.TIME_UNSET) target = target.coerceAtMost(duration)
        c.seekTo(target)
    }

    fun skipTo(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0L)
            c.prepare()
            c.play()
        }
    }

    fun removeAt(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    fun move(from: Int, to: Int) = withController { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    fun clearQueue() = withController { c -> c.clearMediaItems() }

    fun toggleShuffle() = withController { c -> c.shuffleModeEnabled = !c.shuffleModeEnabled }

    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setSpeed(speed: Float) = withController { c -> c.setPlaybackSpeed(speed.coerceIn(0.25f, 4f)) }

    val positionMs: Long
        get() = controller?.currentPosition?.coerceAtLeast(0) ?: 0

    val durationMs: Long
        get() = controller?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: (_state.value.current?.durationMs ?: 0)

    // ---- audio / subtitle tracks ---------------------------------------------------------

    fun tracks(type: Int): List<TrackOption> {
        val c = controller ?: return emptyList()
        val out = ArrayList<TrackOption>()
        c.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != type) return@forEachIndexed
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                out += TrackOption(describe(group, i, out.size + 1), group.isTrackSelected(i), groupIndex, i)
            }
        }
        return out
    }

    private fun describe(group: Tracks.Group, index: Int, number: Int): String {
        val format = group.getTrackFormat(index)
        val parts = ArrayList<String>()
        format.label?.takeIf { it.isNotBlank() }?.let { parts += it }
        format.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
            parts += java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it }
        }
        if (group.type == C.TRACK_TYPE_AUDIO) {
            when (format.channelCount) {
                1 -> parts += "Mono"
                2 -> parts += "Stereo"
                6 -> parts += "5.1"
                8 -> parts += "7.1"
            }
            format.sampleMimeType?.substringAfter('/')?.let { parts += it.uppercase() }
        }
        return if (parts.isEmpty()) "Track $number" else parts.distinct().joinToString(" · ")
    }

    fun selectTrack(type: Int, option: TrackOption?) = withController { c ->
        val builder = c.trackSelectionParameters.buildUpon()
        if (option == null) {
            // "Off" only makes sense for subtitles.
            builder.clearOverridesOfType(type).setTrackTypeDisabled(type, true)
        } else {
            val group = c.currentTracks.groups.getOrNull(option.groupIndex) ?: return@withController
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        c.trackSelectionParameters = builder.build()
    }

    // ---- sleep timer ---------------------------------------------------------------------

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _state.update { it.copy(sleepTimerEndsAt = 0) }
            return
        }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepTimerEndsAt = endsAt) }
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            controller?.pause()
            _state.update { it.copy(sleepTimerEndsAt = 0) }
        }
    }

    /** Lets previews and tests show a player without a running service. */
    fun fakeState(state: PlayerUiState) {
        _state.value = state
    }
}
