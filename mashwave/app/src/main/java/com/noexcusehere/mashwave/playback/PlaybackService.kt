package com.noexcusehere.mashwave.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.noexcusehere.mashwave.MainActivity
import com.noexcusehere.mashwave.MashwaveApp
import com.noexcusehere.mashwave.R
import com.noexcusehere.mashwave.data.SavedQueue
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

    private val positionTicker = object : Runnable {
        override fun run() {
            savePosition()
            if (player.isPlaying) handler.postDelayed(this, 10_000)
        }
    }

    private val saveQueueTask = Runnable {
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toEntry() }
        mashwave.store.saveQueue(SavedQueue(items = items))
        savePosition()
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this, mashwaveRenderers(this))
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
        val idle = !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE
        if (idle) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        savePosition()
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
        if (player.mediaItemCount == 0) return
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
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (player.isPlaying) recordCurrent()
            savePosition()
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
        mashwave.store.recordPlay(item.toEntry())
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
