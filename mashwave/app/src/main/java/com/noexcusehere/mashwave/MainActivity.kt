package com.noexcusehere.mashwave

import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.noexcusehere.mashwave.data.describeUri
import com.noexcusehere.mashwave.ui.AppRoot
import com.noexcusehere.mashwave.ui.Routes
import com.noexcusehere.mashwave.ui.UiState
import com.noexcusehere.mashwave.ui.theme.MashwaveTheme

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
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MashwaveTheme {
                AppRoot(ui)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.player.connect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        // Let the playback service shut down when the app is really closed and nothing is playing.
        if (isFinishing) app.player.release()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_SHOW_PLAYER -> ui.nowPlayingOpen = true
            ACTION_SHOW_MASHUP -> {
                ui.mashupMode = 1
                ui.pendingTab = Routes.MASHUP
            }
            Intent.ACTION_VIEW -> {
                // "Open with Mashwave" from a file manager, browser or chat app.
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
    }
}
