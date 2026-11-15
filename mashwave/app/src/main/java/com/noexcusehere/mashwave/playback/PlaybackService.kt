package com.noexcusehere.mashwave.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.noexcusehere.mashwave.MainActivity
import com.noexcusehere.mashwave.MashwaveApp
import com.noexcusehere.mashwave.R
import com.noexcusehere.mashwave.data.ResumeRules
import com.noexcusehere.mashwave.data.SavedQueue
import com.noexcusehere.mashwave.iptv.TvNet
import com.noexcusehere.mashwave.iptv.isLiveTv
import com.noexcusehere.mashwave.iptv.isTv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The main player. Runs as a media service so music keeps playing with the screen off and
 * shows up on the lock screen, in the notification shade and on Bluetooth / headset buttons.
 */
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var consecutiveErrors = 0
    private var lastRecordedId: String? = null
    private var userChangedQueue = false

    private val mashwave: MashwaveApp
        get() = application as MashwaveApp

    private companion object {
        /** How long a live channel may sit "loading" before it is redialled. */
        const val STALL_AFTER_MS = 25_000L
        const val TAG = "UltimateBeats"
    }

    private val positionTicker = object : Runnable {
        override fun run() {
            savePosition()
            rememberSpot()
            if (player.isPlaying) handler.postDelayed(this, 10_000)
        }
    }

    // ---- carrying on a video where it was stopped ------------------------------------------

    /** The video whose length we last saw, so its place can still be saved after the player moved on. */
    private var lengthOf: String? = null
    private var lengthMs = 0L

    private fun showsVideo(item: MediaItem): Boolean =
        item.toEntry().isVideo || player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }

    /** Saves how far into the current video we are. Songs and live streams are left alone. */
    private fun rememberSpot() {
        val item = player.currentMediaItem ?: return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0 || player.isCurrentMediaItemLive || !showsVideo(item)) return
        lengthOf = item.mediaId
        lengthMs = duration
        mashwave.store.saveResume(item.mediaId, player.currentPosition, duration)
    }

    /** Jumps a freshly started video to the place it was stopped at last time. */
    private fun resumeIfKnown(item: MediaItem?) {
        item ?: return
        if (!item.toEntry().isVideo || player.currentPosition > 1500) return
        val point = mashwave.store.resumePoint(item.mediaId) ?: return
        val start = ResumeRules.startFrom(point)
        if (start <= 0) return
        player.seekTo(start)
        // Lets the video screen say "carried on from 12:34" and offer to start over.
        mashwave.player.resumed.value = ResumeNotice(item.mediaId, start, System.currentTimeMillis())
    }

    private val saveQueueTask = Runnable {
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toEntry() }
        // TV streams are not kept: reopening the app must not quietly reconnect to the TV service.
        if (items.none { it.isTv }) {
            mashwave.store.saveQueue(SavedQueue(items = items))
            savePosition()
        }
    }

    // ---- keeping TV streams going ---------------------------------------------------------

    /** When each recent reconnection was tried, so a dead channel is given up on instead of retried for ever. */
    private val retryTimes = ArrayDeque<Long>()

    /** Streams already retried as a "list of short pieces", which some addresses are without saying so. */
    private val triedAsList = HashSet<String>()

    private fun mayRetry(): Boolean {
        val now = System.currentTimeMillis()
        while (retryTimes.isNotEmpty() && now - retryTimes.first() > 60_000) retryTimes.removeFirst()
        if (retryTimes.size >= 6) return false
        retryTimes.addLast(now)
        return true
    }

    private val reconnect: Runnable = Runnable {
        val item = player.currentMediaItem ?: return@Runnable
        val live = item.toEntry().isLiveTv
        if (!item.toEntry().isTv || !player.playWhenReady) return@Runnable
        if (live) {
            // Hang up completely (line and decoders), then rejoin at "now".
            player.stop()
            player.seekToDefaultPosition()
        }
        // A movie carries on from where it stopped.
        player.prepare()
        handler.removeCallbacks(stallCheck)
        if (live) watchForStall()
    }

    /** How many times in a row a channel was redialled for loading endlessly, without ever getting going. */
    private var stalls = 0

    /** How much of the stream had arrived at the last look, and since when nothing more has. */
    private var stallBuffered = 0L
    private var stallSince = 0L

    private fun watchForStall() {
        handler.removeCallbacks(stallCheck)
        stallBuffered = player.bufferedPosition
        stallSince = SystemClock.elapsedRealtime()
        handler.postDelayed(stallCheck, 5_000)
    }

    /**
     * A live channel can hang without failing: the line stays open but nothing playable arrives.
     * While it is "loading", look every few seconds; if nothing new has arrived for a good while,
     * hang up and dial again. A slow connection that is still delivering is left alone.
     */
    private val stallCheck: Runnable = Runnable {
        val item = player.currentMediaItem ?: return@Runnable
        if (!item.toEntry().isLiveTv || !player.playWhenReady || player.playbackState != Player.STATE_BUFFERING) return@Runnable
        val now = SystemClock.elapsedRealtime()
        val buffered = player.bufferedPosition
        if (buffered > stallBuffered + 300) {
            stallBuffered = buffered
            stallSince = now
        }
        if (now - stallSince < STALL_AFTER_MS) {
            handler.postDelayed(stallCheck, 5_000)
        } else if (stalls++ < 3) {
            Log.i(TAG, "TV: nothing arriving, redialling (${stalls})")
            reconnect.run()
        } else {
            stalls = 0
            player.pause()
            mashwave.say("\"${item.toEntry().title}\" isn't sending a picture right now. Try again in a moment, or another channel.")
        }
    }

    /** Handles a TV stream that failed. Returns the message to show, or null when it is being retried. */
    private fun tvProblem(item: MediaItem, error: PlaybackException): String? {
        val entry = item.toEntry()
        val unknownKind = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        if (unknownKind && triedAsList.add(item.mediaId) && item.localConfiguration?.mimeType != MimeTypes.APPLICATION_M3U8) {
            val index = player.currentMediaItemIndex
            player.replaceMediaItem(index, item.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build())
            player.prepare()
            return null
        }
        val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
        val refused = status == 401 || status == 403 || status == 404 || status == 451
        val network = error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
        if (player.playWhenReady && network && !refused && mayRetry()) {
            Log.i(TAG, "TV: stream failed (${error.errorCodeName}), trying again")
            handler.removeCallbacks(reconnect)
            handler.postDelayed(reconnect, 1200L * retryTimes.size)
            return null
        }
        val why = when {
            status == 401 || status == 403 -> "the service refused. The subscription may have run out, or it is being watched on another device"
            status == 404 -> if (entry.isLiveTv) "the channel is off air right now" else "the service no longer has it"
            status in 500..599 -> "the service is having trouble (error $status)"
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "no connection"
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                unknownKind -> "this phone can't play its format"
            else -> "the stream stopped"
        }
        return "Can't play \"${entry.title}\" - $why"
    }

    override fun onCreate() {
        super.onCreate()
        // TV services forward streams from one server to another, sometimes between http and https.
        val web = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("User-Agent" to TvNet.userAgent))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        // A TV service may only answer under the name it accepted at sign-in; every request for
        // the stream that is on (lists of pieces and the pieces too) carries that name.
        val named = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, web)) { spec ->
            val agent = TvNet.playbackAgent
            val scheme = spec.uri.scheme.orEmpty()
            if (agent.isNullOrBlank() || !scheme.startsWith("http")) spec else spec.withAdditionalHeaders(mapOf("User-Agent" to agent))
        }
        player = ExoPlayer.Builder(this, mashwaveRenderers(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(named))
            // Live channels keep a few seconds in reserve so a hiccup on the line doesn't stop the picture.
            .setLoadControl(CushionedLoadControl { if (TvNet.playingLive) TvNet.cushion else null })
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(listener)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_PLAYER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().also {
                it.setSmallIcon(R.drawable.ic_stat_mashwave)
            },
        )

        AudioFx.attach(player.audioSessionId, mashwave.store.data.value.fx)
        restoreQueue()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        rememberSpot()
        val idle = !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE
        if (idle) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        savePosition()
        rememberSpot()
        AudioFx.release()
        session?.release()
        session = null
        player.removeListener(listener)
        player.release()
        super.onDestroy()
    }

    // ---- remembering the queue between launches ---------------------------------------

    private fun prefs() = getSharedPreferences("mashwave-playback", Context.MODE_PRIVATE)

    private fun savePosition() {
        if (player.mediaItemCount == 0 || player.currentMediaItem?.toEntry()?.isTv == true) return
        prefs().edit()
            .putInt("index", player.currentMediaItemIndex)
            .putLong("position", player.currentPosition.coerceAtLeast(0))
            .putBoolean("shuffle", player.shuffleModeEnabled)
            .putInt("repeat", player.repeatMode)
            .apply()
    }

    private fun restoreQueue() {
        mashwave.scope.launch {
            val saved = withContext(Dispatchers.IO) { mashwave.store.loadQueue() } ?: return@launch
            // Don't clobber anything the user started while we were reading the file.
            if (saved.items.isEmpty() || userChangedQueue || player.mediaItemCount > 0 || session == null) return@launch
            val p = prefs()
            val index = p.getInt("index", 0).coerceIn(0, saved.items.lastIndex)
            player.shuffleModeEnabled = p.getBoolean("shuffle", false)
            player.repeatMode = p.getInt("repeat", Player.REPEAT_MODE_OFF)
            player.setMediaItems(saved.items.map { it.toMediaItem() }, index, p.getLong("position", 0))
            player.prepare()
        }
    }

    // ---- player events ------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(positionTicker)
            if (isPlaying) {
                consecutiveErrors = 0
                recordCurrent()
                handler.postDelayed(positionTicker, 10_000)
            } else {
                savePosition()
                // Paused or stopped: this is the moment people expect their place to be kept.
                if (player.playbackState != Player.STATE_ENDED) rememberSpot()
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            val left = oldPosition.mediaItem ?: return
            if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex && left.mediaId == newPosition.mediaItem?.mediaId &&
                reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            ) {
                return
            }
            // The player has left a video: it either ran to its end, or the viewer moved on.
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                mashwave.store.clearResume(left.mediaId)
            } else if (left.toEntry().isVideo) {
                val length = if (lengthOf == left.mediaId) lengthMs else left.toEntry().durationMs
                if (length > 0) mashwave.store.saveResume(left.mediaId, oldPosition.positionMs, length)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            handler.removeCallbacks(stallCheck)
            if (playbackState == Player.STATE_READY) stalls = 0
            if (playbackState == Player.STATE_BUFFERING && player.currentMediaItem?.toEntry()?.isLiveTv == true) watchForStall()
            if (playbackState != Player.STATE_ENDED) return
            val item = player.currentMediaItem ?: return
            if (item.toEntry().isLiveTv) {
                // A live channel never "finishes": the service dropped the line, so dial again.
                if (player.playWhenReady && mayRetry()) {
                    Log.i(TAG, "TV: the channel's line closed, redialling")
                    handler.removeCallbacks(reconnect)
                    handler.postDelayed(reconnect, 800L * retryTimes.size)
                } else if (player.playWhenReady) {
                    mashwave.say("\"${item.toEntry().title}\" keeps dropping out. Try again in a moment, or another channel.")
                }
                return
            }
            // Watched to the very end: next time it starts from the beginning.
            mashwave.store.clearResume(item.mediaId)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            TvNet.playbackAgent = mashwave.tv.agentFor(mediaItem?.toEntry())
            TvNet.playingLive = mediaItem?.toEntry()?.isLiveTv == true
            // A different channel starts with a clean slate.
            stalls = 0
            retryTimes.clear()
            if (player.isPlaying) recordCurrent()
            savePosition()
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) resumeIfKnown(mediaItem)
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                handler.removeCallbacks(saveQueueTask)
                handler.postDelayed(saveQueueTask, 800)
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            AudioFx.attach(audioSessionId, mashwave.store.data.value.fx)
        }

        override fun onPlayerError(error: PlaybackException) {
            val failed = player.currentMediaItem
            if (failed != null && failed.toEntry().isTv) {
                // Never hop to another channel or episode because one failed.
                tvProblem(failed, error)?.let { mashwave.say(it) }
                return
            }
            val title = player.currentMediaItem?.mediaMetadata?.title ?: "this file"
            val why = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "file not found"
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "no permission to read it"
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                -> "no connection"
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "the server refused"
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                -> "format not supported on this phone"
                else -> "playback error"
            }
            mashwave.say("Can't play \"$title\" - $why")
            consecutiveErrors++
            if (player.playWhenReady && player.hasNextMediaItem() && consecutiveErrors <= 5) {
                player.seekToNextMediaItem()
                player.prepare()
            }
        }
    }

    private fun recordCurrent() {
        val item = player.currentMediaItem ?: return
        if (item.mediaId == lastRecordedId) return
        lastRecordedId = item.mediaId
        // The TV tab keeps its own "recently watched"; channels don't belong among recently played songs.
        if (!item.toEntry().isTv) mashwave.store.recordPlay(item.toEntry())
    }

    /**
     * Queue items arrive from the UI without their file address (the media session strips it),
     * so put it back from the id / request metadata before handing them to the player.
     */
    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            userChangedQueue = true
            // Known before the first byte is asked for: the name this queue's TV service accepts.
            TvNet.playbackAgent = mashwave.tv.agentFor(mediaItems.firstOrNull()?.toEntry())
            TvNet.playingLive = mediaItems.firstOrNull()?.toEntry()?.isLiveTv == true
            val resolved = mediaItems.map { item ->
                if (item.localConfiguration != null) {
                    item
                } else {
                    val uri = item.requestMetadata.mediaUri ?: Uri.parse(item.mediaId)
                    item.buildUpon().setUri(uri).build()
                }
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }
}
