package com.noexcusehere.mashwave.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.noexcusehere.mashwave.desktop.ui.AppLogo
import com.noexcusehere.mashwave.desktop.ui.CastBar
import com.noexcusehere.mashwave.desktop.ui.CastButton
import com.noexcusehere.mashwave.desktop.ui.CastSheet
import com.noexcusehere.mashwave.desktop.ui.DeskTheme
import com.noexcusehere.mashwave.desktop.ui.DetailsPage
import com.noexcusehere.mashwave.desktop.ui.Ink
import com.noexcusehere.mashwave.desktop.ui.LiveScreen
import com.noexcusehere.mashwave.desktop.ui.MusicBar
import com.noexcusehere.mashwave.desktop.ui.MusicScreen
import com.noexcusehere.mashwave.desktop.ui.MyVideosScreen
import com.noexcusehere.mashwave.desktop.ui.Outline
import com.noexcusehere.mashwave.desktop.ui.Pictures
import com.noexcusehere.mashwave.desktop.ui.Pink
import com.noexcusehere.mashwave.desktop.ui.PlayerScreen
import com.noexcusehere.mashwave.desktop.ui.RoundIcon
import com.noexcusehere.mashwave.desktop.ui.SavedScreen
import com.noexcusehere.mashwave.desktop.ui.SearchScreen
import com.noexcusehere.mashwave.desktop.ui.SettingsScreen
import com.noexcusehere.mashwave.desktop.ui.SignInSheet
import com.noexcusehere.mashwave.desktop.ui.Surface1
import com.noexcusehere.mashwave.desktop.ui.Surface3
import com.noexcusehere.mashwave.desktop.ui.TextHi
import com.noexcusehere.mashwave.desktop.ui.TextLow
import com.noexcusehere.mashwave.desktop.ui.TextMid
import com.noexcusehere.mashwave.desktop.ui.UnlockScreen
import com.noexcusehere.mashwave.desktop.ui.VideoPicture
import com.noexcusehere.mashwave.desktop.ui.Violet
import com.noexcusehere.mashwave.desktop.ui.VlcNeeded
import com.noexcusehere.mashwave.desktop.ui.VodScreen
import com.noexcusehere.mashwave.desktop.ui.Welcome
import com.noexcusehere.mashwave.desktop.ui.handleKey
import com.noexcusehere.mashwave.desktop.vlc.VlcPlayer
import com.noexcusehere.mashwave.iptv.TvSection
import com.noexcusehere.mashwave.iptv.TvState
import com.noexcusehere.mashwave.iptv.isLiveTv
import com.noexcusehere.mashwave.licence.Lock
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private const val SMALLEST_WIDTH = 1040
private const val SMALLEST_HEIGHT = 640

fun main(args: Array<String>) {
    // "--self-test <video file> <folder>" starts the app, plays the file and writes down how it went.
    val selfTest = SelfTest.from(args)
    val dataDir = selfTest?.let { File(it.out, "data") }?.apply { mkdirs() } ?: AppFolders.data()
    val vlcArgs = (System.getProperty("umm.vlc.args") ?: System.getenv("UMM_VLC_ARGS")).orEmpty().split(' ').filter { it.isNotBlank() }
    Pictures.keepIn(File(dataDir, "pictures"))

    application {
        val desk = remember { Desk(CoroutineScope(SupervisorJob() + Dispatchers.Main), dataDir, vlcArgs, asksForCode = selfTest == null) }
        val saved = remember { desk.store.saved }
        // The room there is on this screen once the taskbar is taken off. A small laptop screen
        // is smaller than the window would like to be: then it simply fills the screen.
        val room = remember { runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull() }
        val roomWidth = room?.width?.takeIf { it > 200 } ?: 1280
        val roomHeight = room?.height?.takeIf { it > 200 } ?: 800
        val cramped = roomWidth < SMALLEST_WIDTH + 60 || roomHeight < SMALLEST_HEIGHT + 60
        val windowState = rememberWindowState(
            placement = if (saved.maximised || cramped) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = WindowPosition.Aligned(Alignment.Center),
            size = DpSize(
                saved.windowWidth.coerceIn(minOf(SMALLEST_WIDTH, roomWidth), roomWidth).dp,
                saved.windowHeight.coerceIn(minOf(SMALLEST_HEIGHT, roomHeight), roomHeight).dp,
            ),
        )
        // Full screen comes and goes; underneath, the window is either maximised or not.
        val resting = remember { arrayOf(windowState.placement) }
        LaunchedEffect(desk.fullscreen) {
            if (desk.fullscreen) {
                if (windowState.placement != WindowPlacement.Fullscreen) resting[0] = windowState.placement
                windowState.placement = WindowPlacement.Fullscreen
            } else if (windowState.placement == WindowPlacement.Fullscreen) {
                windowState.placement = resting[0]
            }
        }

        fun close() {
            val placement = if (windowState.placement == WindowPlacement.Fullscreen) resting[0] else windowState.placement
            desk.store.edit {
                if (placement == WindowPlacement.Maximized) {
                    // Filling a small screen was not the viewer's choice; do not hold them to it on a bigger one.
                    it.copy(maximised = !cramped)
                } else {
                    it.copy(maximised = false, windowWidth = windowState.size.width.value.toInt(), windowHeight = windowState.size.height.value.toInt())
                }
            }
            desk.shutDown()
            quitNow(0)
        }

        Window(
            onCloseRequest = ::close,
            state = windowState,
            title = AppFolders.NAME,
            icon = remember { AppLogo?.let { BitmapPainter(it) } },
            onPreviewKeyEvent = { handleKey(desk, it) },
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(minOf(SMALLEST_WIDTH, roomWidth), minOf(SMALLEST_HEIGHT, roomHeight))
                desk.window = window
                // Music and video files dropped anywhere on the window are played.
                runCatching {
                    window.contentPane.dropTarget = object : DropTarget() {
                        @Synchronized
                        override fun drop(event: DropTargetDropEvent) {
                            runCatching {
                                event.acceptDrop(DnDConstants.ACTION_COPY)
                                val dropped = (event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>).orEmpty().filterIsInstance<File>()
                                event.dropComplete(true)
                                if (dropped.isNotEmpty() && desk.licence.state.value != Lock.LOCKED) desk.openFiles(dropped)
                            }.onFailure { runCatching { event.dropComplete(false) } }
                        }
                    }
                }
            }
            DeskTheme {
                // Until a code has been put in, the unlock screen is all there is.
                val lock by desk.licence.state.collectAsState()
                if (lock == Lock.LOCKED) UnlockScreen(desk) else AppBody(desk)
            }
            if (selfTest != null) {
                LaunchedEffect(Unit) {
                    val code = selfTest.run(desk, window)
                    desk.shutDown()
                    quitNow(code)
                }
            }
        }
    }
    quitNow(0)
}

@Suppress("FunctionName")
private interface EndOnWindows : Library {
    fun GetCurrentProcess(): Pointer

    fun TerminateProcess(process: Pointer, code: Int): Boolean
}

@Suppress("FunctionName")
private interface EndElsewhere : Library {
    fun _exit(code: Int)
}

/**
 * Ends the program this instant. By now everything is saved and VLC has been let go. The polite
 * way out (asking every loaded library to tidy up first) can wait for ever: a graphics library
 * and a window thread end up waiting for each other, and a closed app stays running unseen.
 */
fun quitNow(code: Int): Nothing {
    runCatching {
        if (Platform.isWindows()) {
            val system = Native.load("kernel32", EndOnWindows::class.java)
            system.TerminateProcess(system.GetCurrentProcess(), code)
        } else {
            Native.load("c", EndElsewhere::class.java)._exit(code)
        }
    }
    Runtime.getRuntime().halt(code)
    exitProcess(code)
}

@Composable
private fun AppBody(desk: Desk) {
    val state by desk.tv.state.collectAsState()
    val player = desk.player
    val keeper = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize().background(Ink)) {
      Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Rail(desk, state)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize()) {
                    // Music and videos on this computer need the player too, service or no service.
                    if (player == null && !desk.lookingForVlc && desk.tab != Tab.SETTINGS && (state.active != null || !desk.tab.needsService)) {
                        VlcNeeded(desk, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth())
                    }
                    Box(Modifier.weight(1f)) {
                        if (state.active == null && desk.tab.needsService) {
                            Welcome(desk)
                        } else {
                            // Each screen keeps its place (the group chosen, how far it was scrolled) while another is open.
                            keeper.SaveableStateProvider(desk.tab.name + "/" + state.active?.id) {
                                when (desk.tab) {
                                    Tab.LIVE -> LiveScreen(desk)
                                    Tab.MOVIES -> VodScreen(desk, TvSection.MOVIES)
                                    Tab.SHOWS -> VodScreen(desk, TvSection.SHOWS)
                                    Tab.SEARCH -> SearchScreen(desk)
                                    Tab.SAVED -> SavedScreen(desk)
                                    Tab.MUSIC -> MusicScreen(desk)
                                    Tab.VIDEOS -> MyVideosScreen(desk)
                                    Tab.SETTINGS -> SettingsScreen(desk)
                                }
                            }
                        }
                    }
                }
                desk.page?.let { item ->
                    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})) {
                        DetailsPage(desk, item)
                    }
                }
            }
        }
        // Connected to a TV: its controls sit along the bottom. Otherwise, while a song plays here, the song's do.
        if (desk.cast.connected) CastBar(desk) else if (player != null && desk.playingMusic) MusicBar(desk, player)
      }

        if (desk.watching && player != null && !desk.playingMusic) PlayerScreen(desk, player)
        desk.signIn?.let { SignInSheet(desk, it) }
        if (desk.castOpen) CastSheet(desk)

        if (desk.notice.isNotEmpty()) {
            Text(
                desk.notice,
                style = MaterialTheme.typography.bodyMedium,
                color = TextHi,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 26.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Surface3)
                    .border(1.dp, Outline, RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

/** The menu down the left side, with whatever is playing tucked in at the bottom. */
@Composable
private fun Rail(desk: Desk, state: TvState) {
    Column(Modifier.width(212.dp).fillMaxHeight().background(Color(0xFF0C0C13)).padding(vertical = 14.dp)) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppLogo?.let { Image(it, contentDescription = null, modifier = Modifier.size(38.dp).clip(RoundedCornerShape(9.dp))) }
            Spacer(Modifier.width(10.dp))
            Text("Ultimate\nMedia Manager", style = MaterialTheme.typography.titleSmall, color = TextHi, maxLines = 2)
        }
        Spacer(Modifier.height(18.dp))
        RailButton(desk, Tab.LIVE, Icons.Rounded.LiveTv)
        RailButton(desk, Tab.MOVIES, Icons.Rounded.Movie)
        RailButton(desk, Tab.SHOWS, Icons.Rounded.VideoLibrary)
        RailButton(desk, Tab.SEARCH, Icons.Rounded.Search)
        RailButton(desk, Tab.SAVED, Icons.Rounded.Favorite)
        Spacer(Modifier.height(10.dp))
        Text("ON THIS COMPUTER", style = MaterialTheme.typography.labelSmall, color = TextLow, modifier = Modifier.padding(start = 22.dp, bottom = 4.dp))
        RailButton(desk, Tab.MUSIC, Icons.Rounded.MusicNote)
        RailButton(desk, Tab.VIDEOS, Icons.Rounded.VideoFile)
        Spacer(Modifier.weight(1f))
        val player = desk.player
        val entry = player?.current
        // On the Live TV screen the channel already has its own picture; a song has its own bar along the bottom.
        if (player != null && entry != null && !entry.isSong && !desk.watching && !(desk.tab == Tab.LIVE && entry.isLiveTv && desk.page == null)) {
            NowPlaying(desk, player)
            Spacer(Modifier.height(10.dp))
        }
        CastButton(desk)
        RailButton(desk, Tab.SETTINGS, Icons.Rounded.Settings)
        state.active?.let { account ->
            Text(
                account.name,
                style = MaterialTheme.typography.labelSmall,
                color = TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 6.dp),
            )
        }
    }
}

@Composable
private fun RailButton(desk: Desk, tab: Tab, icon: ImageVector) {
    val chosen = desk.tab == tab && desk.page == null
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (chosen) Violet.copy(alpha = 0.24f) else Color.Transparent)
            .clickable {
                desk.page = null
                desk.tab = tab
            }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (chosen) TextHi else TextMid, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(tab.title, style = MaterialTheme.typography.titleMedium, color = if (chosen) TextHi else TextMid, maxLines = 1)
    }
}

/** What is playing, small, while another screen is open. Click to bring it back. */
@Composable
private fun NowPlaying(desk: Desk, player: VlcPlayer) {
    val entry = player.current ?: return
    Column(
        Modifier
            .padding(horizontal = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .border(1.dp, Outline, RoundedCornerShape(12.dp))
            .clickable { desk.watching = true },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            VideoPicture(player, desk.look, Modifier.fillMaxSize())
            RoundIcon(Icons.Rounded.Close, "Stop", { desk.stopPlaying() }, Modifier.align(Alignment.TopEnd).padding(4.dp), size = 26.dp, backing = Color.Black.copy(alpha = 0.6f))
        }
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (entry.isLiveTv) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Pink))
                Spacer(Modifier.width(6.dp))
            }
            Text(entry.title, style = MaterialTheme.typography.labelMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
