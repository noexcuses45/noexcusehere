package com.noexcusehere.mashwave.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import com.noexcusehere.mashwave.data.MediaEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Tells Google's Cast software to use the standard built-in player on the Chromecast. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setStopReceiverApplicationWhenEndingSession(true)
        // The app shows its own "playing on TV" notification and controls.
        .setCastMediaOptions(CastMediaOptions.Builder().setNotificationOptions(null).setMediaSessionEnabled(false).build())
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Chromecast and "Chromecast built-in" TVs, through Google Play services on the phone.
 * Everything is wrapped so that a phone without Google services simply finds no Chromecasts.
 */
class ChromecastBridge(context: Context) {
    private val appContext = context.applicationContext
    private var castContext: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var callback: MediaRouter.Callback? = null

    private fun ready(): Boolean {
        if (castContext != null) return true
        return try {
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) != ConnectionResult.SUCCESS) return false
            @Suppress("DEPRECATION")
            castContext = CastContext.getSharedInstance(appContext)
            router = MediaRouter.getInstance(appContext)
            selector = MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .build()
            true
        } catch (e: Throwable) {
            Log.w("UltimateBeats", "Chromecast support is not available on this phone", e)
            false
        }
    }

    /** Must be called on the main thread. [onChange] fires whenever a Chromecast appears or disappears. */
    fun startDiscovery(onChange: () -> Unit) {
        if (!ready() || callback != null) return
        val watcher = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = onChange()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = onChange()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = onChange()
        }
        runCatching {
            router?.addCallback(selector ?: return, watcher, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            callback = watcher
            onChange()
        }
    }

    fun stopDiscovery() {
        val watcher = callback ?: return
        callback = null
        runCatching { router?.removeCallback(watcher) }
    }

    fun devices(): List<CastDevice> {
        val chosen = selector ?: return emptyList()
        return runCatching {
            router?.routes.orEmpty()
                .filter { it.isEnabled && !it.isDefault && it.matchesSelector(chosen) }
                .map { CastDevice(it.id, it.name, it.description?.takeIf(String::isNotBlank) ?: "Chromecast", CastKind.CHROMECAST) }
        }.getOrDefault(emptyList())
    }

    suspend fun connect(routeId: String): CastLink? = withContext(Dispatchers.Main) {
        if (!ready()) return@withContext null
        val cast = castContext ?: return@withContext null
        val mediaRouter = router ?: return@withContext null
        val route = mediaRouter.routes.firstOrNull { it.id == routeId } ?: return@withContext null
        val manager = cast.sessionManager
        val started = CompletableDeferred<CastSession?>()
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                started.complete(session)
            }

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                started.complete(session)
            }

            override fun onSessionStartFailed(session: CastSession, error: Int) {
                started.complete(null)
            }

            override fun onSessionResumeFailed(session: CastSession, error: Int) {
                started.complete(null)
            }

            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionEnded(session: CastSession, error: Int) = Unit
            override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        }
        manager.addSessionManagerListener(listener, CastSession::class.java)
        try {
            mediaRouter.selectRoute(route)
            val session = withTimeoutOrNull(25_000) { started.await() }
            if (session == null) {
                runCatching { mediaRouter.unselect(MediaRouter.UNSELECT_REASON_STOPPED) }
                null
            } else {
                ChromecastLink(session, cast, mediaRouter)
            }
        } finally {
            manager.removeSessionManagerListener(listener, CastSession::class.java)
        }
    }
}

private class ChromecastLink(
    private val session: CastSession,
    private val cast: CastContext,
    private val router: MediaRouter,
) : CastLink {
    override suspend fun load(url: String, entry: MediaEntry, mime: String, artUrl: String?, startMs: Long) = onMain {
        val client = session.remoteMediaClient ?: throw IllegalStateException("The Chromecast is not ready")
        val metadata = MediaMetadata(if (entry.isVideo) MediaMetadata.MEDIA_TYPE_MOVIE else MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, entry.title)
            if (entry.artist.isNotBlank()) putString(MediaMetadata.KEY_ARTIST, entry.artist)
            if (entry.album.isNotBlank()) putString(MediaMetadata.KEY_ALBUM_TITLE, entry.album)
            if (artUrl != null) addImage(WebImage(Uri.parse(artUrl)))
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime)
            .setMetadata(metadata)
            .build()
        client.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(info)
                .setAutoplay(true)
                .setCurrentTime(startMs.coerceAtLeast(0))
                .build(),
        )
        Unit
    }

    override suspend fun play() = onMain { session.remoteMediaClient?.play(); Unit }

    override suspend fun pause() = onMain { session.remoteMediaClient?.pause(); Unit }

    override suspend fun seek(positionMs: Long) = onMain {
        session.remoteMediaClient?.seek(MediaSeekOptions.Builder().setPosition(positionMs).build())
        Unit
    }

    override suspend fun setVolume(level: Float) = onMain { session.volume = level.toDouble().coerceIn(0.0, 1.0) }

    override suspend fun stop() = onMain { session.remoteMediaClient?.stop(); Unit }

    override suspend fun status(): RemoteStatus? = onMain {
        if (!session.isConnected) return@onMain null
        val client = session.remoteMediaClient ?: return@onMain null
        val state = when (client.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> RemoteState.PLAYING
            MediaStatus.PLAYER_STATE_PAUSED -> RemoteState.PAUSED
            MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING -> RemoteState.LOADING
            MediaStatus.PLAYER_STATE_IDLE ->
                if (client.idleReason == MediaStatus.IDLE_REASON_FINISHED) RemoteState.FINISHED else RemoteState.STOPPED
            else -> RemoteState.STOPPED
        }
        RemoteStatus(
            state = state,
            positionMs = client.approximateStreamPosition,
            durationMs = client.streamDuration.coerceAtLeast(0),
            volume = runCatching { session.volume.toFloat() }.getOrNull(),
        )
    }

    override fun close() {
        runCatching { cast.sessionManager.endCurrentSession(true) }
        runCatching { router.unselect(MediaRouter.UNSELECT_REASON_STOPPED) }
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }
}
