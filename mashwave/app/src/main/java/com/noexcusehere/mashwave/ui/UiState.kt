package com.noexcusehere.mashwave.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.playback.PlayerConnection
import kotlinx.coroutines.delay

/** What is currently open on top of the main screens (player, sheets, dialogs). */
class UiState {
    var nowPlayingOpen by mutableStateOf(false)
    var videoFullscreen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    var streamDialogOpen by mutableStateOf(false)

    /** A bottom-bar tab to jump to (set when a notification is tapped). */
    var pendingTab by mutableStateOf<String?>(null)

    /** Entry whose "..." menu is showing, and the playlist it was opened from (if any). */
    var menuEntry by mutableStateOf<MediaEntry?>(null)
    var menuPlaylistId by mutableStateOf<String?>(null)

    /** Entries waiting for the user to choose a playlist. */
    var addToPlaylist by mutableStateOf<List<MediaEntry>?>(null)

    fun openMenu(entry: MediaEntry, playlistId: String? = null) {
        menuEntry = entry
        menuPlaylistId = playlistId
    }

    /** Starts a list playing; videos jump straight to the full-screen player. */
    fun play(player: PlayerConnection, entries: List<MediaEntry>, index: Int) {
        if (entries.isEmpty()) return
        player.play(entries, index)
        if (entries[index.coerceIn(0, entries.lastIndex)].isVideo) {
            videoFullscreen = true
        }
    }
}

val LocalUi = staticCompositionLocalOf<UiState> { error("UiState not provided") }
val LocalNav = staticCompositionLocalOf<NavHostController> { error("NavController not provided") }

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val VIDEOS = "videos"
    const val MASHUP = "mashup"
    const val PLAYLISTS = "playlists"
    const val SEARCH = "search"
    const val EQUALIZER = "equalizer"
    const val COLLECTION = "collection/{type}/{key}"

    fun collection(type: String, key: String = "-") = "collection/$type/${Uri.encode(key.ifEmpty { "-" })}"
}

/** The main player's position, refreshed a few times a second while the screen is visible. */
@Composable
fun rememberPlaybackPosition(intervalMs: Long = 500): State<Long> {
    val player = LocalContext.current.app.player
    val owner = LocalLifecycleOwner.current
    return produceState(initialValue = player.positionMs, owner, intervalMs) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = player.positionMs
                delay(intervalMs)
            }
        }
    }
}
