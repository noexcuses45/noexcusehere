package com.noexcusehere.mashwave

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.noexcusehere.mashwave.data.describeUri
import com.noexcusehere.mashwave.iptv.isLiveTv
import com.noexcusehere.mashwave.iptv.zap
import com.noexcusehere.mashwave.ui.AppRoot
import com.noexcusehere.mashwave.ui.FloatingWindow
import com.noexcusehere.mashwave.ui.LibraryTabs
import com.noexcusehere.mashwave.ui.Routes
import com.noexcusehere.mashwave.ui.UiState
import com.noexcusehere.mashwave.ui.theme.MashwaveTheme
import android.content.pm.ActivityInfo
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.noexcusehere.mashwave.ui.tv.TvMode
import com.noexcusehere.mashwave.ui.tv.TvRoot

class MainActivity : ComponentActivity() {
    private val ui = UiState()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Hardware volume keys always control the music, even when nothing is playing yet.
        volumeControlStream = AudioManager.STREAM_MUSIC
        app.player.connect()
        ContextCompat.registerReceiver(this, floatButtonPressed, IntentFilter(ACTION_FLOAT_BUTTON), ContextCompat.RECEIVER_NOT_EXPORTED)
        // On a Fire TV or Android TV stick the app shows its remote-control layout instead.
        tvLayout = TvMode.wanted(this, intent)
        if (tvLayout) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        } else if (savedInstanceState == null) {
            handleIntent(intent)
        }
        setContent {
            MashwaveTheme {
                if (tvLayout) TvRoot() else AppRoot(ui)
            }
        }
    }

    /** True while the app is showing its TV (remote-control) layout. */
    private var tvLayout = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A TV has no status bar. On a box or tablet that has one, the TV layout takes the whole screen.
        if (hasFocus && tvLayout) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.player.connect()
    }

    // ---- the small floating video window ("picture in picture") --------------------------------

    private val canFloat: Boolean by lazy { Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    private var floatWhenLeaving = false
    private var floatPlaying = false
    private var floatHasNext = false
    private var floatShape = Rational(16, 9)
    private var floatEndedAt = 0L

    /**
     * Kept up to date by the video player while it is open: whether a video is [playing] (so
     * leaving the app should keep it on screen in a small floating window), whether there is
     * something to skip on to, and the shape of the video. [open] false means the player closed.
     */
    fun setFloatState(open: Boolean, playing: Boolean, hasNext: Boolean, videoWidth: Int, videoHeight: Int) {
        floatWhenLeaving = open && playing
        floatPlaying = playing
        floatHasNext = hasNext
        FloatingWindow.shape(videoWidth, videoHeight)?.let { floatShape = Rational(it.first, it.second) }
        // Keeps the window's own buttons right (play or pause), and from Android 12 lets the
        // system do the shrinking itself, smoothly, as the app is left.
        if (canFloat && Build.VERSION.SDK_INT >= 26) runCatching { setPictureInPictureParams(floatParams()) }
    }

    @RequiresApi(26)
    private fun floatParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(floatShape).setActions(floatButtons())
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(floatWhenLeaving)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /** The buttons Android shows on the floating window when it is tapped: play or pause, and next. */
    @RequiresApi(26)
    private fun floatButtons(): List<RemoteAction> {
        fun button(code: Int, icon: Int, label: String) = RemoteAction(
            Icon.createWithResource(this, icon),
            label,
            label,
            PendingIntent.getBroadcast(
                this,
                code,
                Intent(ACTION_FLOAT_BUTTON).setPackage(packageName).putExtra(EXTRA_BUTTON, code),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val buttons = ArrayList<RemoteAction>()
        buttons += if (floatPlaying) button(BUTTON_PAUSE, R.drawable.ic_pip_pause, "Pause") else button(BUTTON_PLAY, R.drawable.ic_pip_play, "Play")
        if (floatHasNext) buttons += button(BUTTON_NEXT, R.drawable.ic_pip_next, "Next")
        return buttons
    }

    private val floatButtonPressed = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = app.player.state.value
            when (intent.getIntExtra(EXTRA_BUTTON, 0)) {
                BUTTON_PLAY -> if (!now.isPlaying) app.player.togglePlay()
                BUTTON_PAUSE -> app.player.pause()
                // On a live channel "next" is the next channel; otherwise the next video or episode.
                BUTTON_NEXT -> if (now.current?.isLiveTv != true || !app.tv.zap(app.player, 1)) app.player.next()
            }
        }
    }

    /** Shrinks the app to the floating video window now. False when this phone will not do it. */
    fun floatNow(): Boolean {
        if (!canFloat || Build.VERSION.SDK_INT < 26) return false
        return runCatching { enterPictureInPictureMode(floatParams()) }.getOrDefault(false)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Home was pressed (or another app opened) while a video plays: keep watching in the corner.
        if (floatWhenLeaving && Build.VERSION.SDK_INT < 31 && !isInPictureInPictureMode) floatNow()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ui.inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            // Only the picture belongs in the little window: put away anything lying over it.
            ui.videoFullscreen = true
            ui.castOpen = false
            ui.queueOpen = false
            ui.menuEntry = null
        } else {
            floatEndedAt = SystemClock.elapsedRealtime()
            // Closed with its X rather than opened back up: nothing is on screen any more, so the video stops.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) app.player.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        // The same, for phones that report "closed" a moment before the app leaves the screen, or
        // that take the window away without reporting it at all. With the screen switched off
        // the video carries on (sound only), exactly as it does when it fills the screen.
        // A TV has no "carry on in the background": leaving the app (Home on the remote) stops what was on.
        if (tvLayout) {
            app.player.pause()
            app.player.stop()
            return
        }
        val wasFloating = ui.inPip || (floatEndedAt != 0L && SystemClock.elapsedRealtime() - floatEndedAt < 1500)
        val screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (wasFloating && (isFinishing || screenOn)) app.player.pause()
        floatEndedAt = 0L
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!tvLayout) handleIntent(intent)
    }

    override fun onDestroy() {
        // Let the playback service shut down when the app is really closed and nothing is playing.
        if (isFinishing) app.player.release()
        runCatching { unregisterReceiver(floatButtonPressed) }
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_SHOW_PLAYER -> ui.nowPlayingOpen = true
            ACTION_SHOW_MASHUP -> {
                // The mashup studio lives in the music library now.
                ui.mashupMode = 1
                ui.libraryTab = LibraryTabs.MASHUPS
                ui.pendingTab = Routes.LIBRARY
            }
            ACTION_SHOW_EDITOR -> ui.pendingTab = Routes.EDITOR
            ACTION_SHOW_CAST -> ui.castOpen = true
            Intent.ACTION_VIEW -> {
                // "Open with Ultimate Media Manager" from a file manager, browser or chat app.
                val uri = intent.data ?: return
                val entry = describeUri(this, uri, intent.type)
                ui.play(app.player, listOf(entry), 0)
                if (!entry.isVideo) ui.nowPlayingOpen = true
            }
        }
    }

    companion object {
        const val ACTION_SHOW_PLAYER = "com.noexcusehere.mashwave.SHOW_PLAYER"
        const val ACTION_SHOW_MASHUP = "com.noexcusehere.mashwave.SHOW_MASHUP"
        const val ACTION_SHOW_EDITOR = "com.noexcusehere.mashwave.SHOW_EDITOR"
        const val ACTION_SHOW_CAST = "com.noexcusehere.mashwave.SHOW_CAST"
        private const val ACTION_FLOAT_BUTTON = "com.noexcusehere.mashwave.FLOAT_BUTTON"
        private const val EXTRA_BUTTON = "button"
        private const val BUTTON_PLAY = 1
        private const val BUTTON_PAUSE = 2
        private const val BUTTON_NEXT = 3
    }
}
