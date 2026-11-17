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

    /** The multiview: up to four TV channels side by side, covering the whole screen. */
    var multiviewOpen by mutableStateOf(false)

    /** The "play on a TV" sheet, and which kind of media the button that opened it was for. */
    var castOpen by mutableStateOf(false)
    var castWhat by mutableStateOf(com.noexcusehere.mashwave.cast.CastWhat.VIDEO)

    fun openCast(what: com.noexcusehere.mashwave.cast.CastWhat) {
        castWhat = what
        castOpen = true
    }

    /** Id of the video project open in the editor, which covers the whole screen. */
    var editingProjectId by mutableStateOf<String?>(null)

    /** A bottom-bar tab to jump to (set when a notification is tapped). */
    var pendingTab by mutableStateOf<String?>(null)

    /** Which half of the mashup studio is showing: 0 = Auto Mashup, 1 = the two manual decks. */
    var mashupMode by mutableStateOf(0)

    /** Which button of the music library is chosen (see [LibraryTabs]). */
    var libraryTab by mutableStateOf(LibraryTabs.SONGS)

    /** The photo open in the photo editor (picked on the Photo editor tab), which covers the whole screen. */
    var editingPhoto by mutableStateOf<MediaEntry?>(null)

    /** True while the app is shrunk to the small floating video window. */
    var inPip by mutableStateOf(false)

    /** Entry whose "..." menu is showing, and the playlist it was opened from (if any). */
    var menuEntry by mutableStateOf<MediaEntry?>(null)
    var menuPlaylistId by mutableStateOf<String?>(null)

    /** Entries waiting for the user to choose a playlist. */
    var addToPlaylist by mutableStateOf<List<MediaEntry>?>(null)

    fun openMenu(entry: MediaEntry, playlistId: String? = null) {
        menuEntry = entry
        menuPlaylistId = playlistId
    }

    /** Set by the app shell: the TV connection, when there is one, takes over playback. */
    var castRoute: ((List<MediaEntry>, Int) -> Boolean)? = null

    /** Starts a list playing; videos jump straight to the full-screen player. */
    fun play(player: PlayerConnection, entries: List<MediaEntry>, index: Int) {
        if (entries.isEmpty()) return
        // While connected to a TV, everything the user starts plays there instead of on the phone.
        if (castRoute?.invoke(entries, index.coerceIn(0, entries.lastIndex)) == true) return
        player.play(entries, index)
        if (entries[index.coerceIn(0, entries.lastIndex)].isVideo) {
            videoFullscreen = true
        }
    }
}

/** The buttons under the music library's title, in order. */
object LibraryTabs {
    const val SONGS = 0
    const val ALBUMS = 1
    const val ARTISTS = 2
    const val FOLDERS = 3
    const val PLAYLISTS = 4
    const val MASHUPS = 5
}

val LocalUi = staticCompositionLocalOf<UiState> { error("UiState not provided") }
val LocalNav = staticCompositionLocalOf<NavHostController> { error("NavController not provided") }

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val VIDEOS = "videos"
    const val TV = "tv"
    const val TV_LIST = "tv/list/{section}/{category}"
    const val TV_DETAIL = "tv/detail"
    const val TV_SEARCH = "tv/search"
    const val EDITOR = "editor"
    const val PHOTOS = "photos"
    const val SEARCH = "search"
    const val EQUALIZER = "equalizer"
    const val COLLECTION = "collection/{type}/{key}"

    fun tvList(section: com.noexcusehere.mashwave.iptv.TvSection, category: String) = "tv/list/${section.name}/${Uri.encode(category.ifEmpty { "-" })}"

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
