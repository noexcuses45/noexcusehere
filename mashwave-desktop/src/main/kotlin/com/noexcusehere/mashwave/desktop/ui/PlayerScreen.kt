package com.noexcusehere.mashwave.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noexcusehere.mashwave.desktop.Desk
import com.noexcusehere.mashwave.desktop.Look
import com.noexcusehere.mashwave.desktop.Tab
import com.noexcusehere.mashwave.desktop.vlc.PlayStatus
import com.noexcusehere.mashwave.desktop.vlc.TrackChoice
import com.noexcusehere.mashwave.desktop.vlc.VlcPlayer
import com.noexcusehere.mashwave.iptv.isLiveTv
import kotlinx.coroutines.delay
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

private val hiddenCursor: PointerIcon by lazy {
    runCatching {
        PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "hidden"))
    }.getOrDefault(PointerIcon.Default)
}

/** Leaving the big player: a channel carries on in the corner, a film stops (its place is kept). */
fun leavePlayer(desk: Desk) {
    desk.fullscreen = false
    if (desk.player?.current?.isLiveTv == true) desk.watching = false else desk.stopPlaying()
}

/** The keys that work while the player fills the window, and the few that work elsewhere. True when the key was used. */
fun handleKey(desk: Desk, event: KeyEvent): Boolean {
    // The photo editor covers the window and has keys of its own.
    desk.editing?.let { return it.key(event) }
    desk.videoEditing?.let { editor ->
        if (event.type != KeyEventType.KeyDown) return false
        val letter = when (event.key) {
            Key.Z -> 'z'
            Key.Y -> 'y'
            else -> null
        }
        return editor.key(event.isCtrlPressed || event.isMetaPressed, event.isShiftPressed, letter, event.key == Key.Escape)
    }
    if (event.type != KeyEventType.KeyDown) return false
    val player = desk.player
    if (!desk.watching || player == null) {
        return when {
            player != null && desk.playingMusic && event.key == Key.MediaPlayPause -> {
                player.toggle()
                true
            }
            player != null && desk.playingMusic && event.key == Key.MediaNext -> {
                player.next()
                true
            }
            player != null && desk.playingMusic && event.key == Key.MediaPrevious -> {
                desk.previousSong()
                true
            }
            event.key == Key.Escape && desk.signIn != null -> false
            event.key == Key.Escape && desk.page != null -> {
                desk.page = null
                true
            }
            event.key == Key.F && event.isCtrlPressed -> {
                desk.page = null
                desk.tab = Tab.SEARCH
                true
            }
            else -> false
        }
    }
    val live = player.current?.isLiveTv == true
    when (event.key) {
        Key.Spacebar, Key.K, Key.MediaPlayPause -> player.toggle()
        Key.F, Key.F11 -> desk.fullscreen = !desk.fullscreen
        Key.Escape -> if (desk.fullscreen) desk.fullscreen = false else leavePlayer(desk)
        Key.DirectionLeft -> if (!live) player.seekBy(if (event.isShiftPressed) -60_000 else -10_000)
        Key.DirectionRight -> if (!live) player.seekBy(if (event.isShiftPressed) 60_000 else 10_000)
        Key.DirectionUp -> {
            player.changeVolume(player.volume + 5)
            desk.rememberSound()
        }
        Key.DirectionDown -> {
            player.changeVolume(player.volume - 5)
            desk.rememberSound()
        }
        Key.M -> {
            player.toggleMute()
            desk.rememberSound()
        }
        Key.PageUp, Key.MediaPrevious -> if (live) desk.step(-1) else player.previous()
        Key.PageDown, Key.N, Key.MediaNext -> if (live) desk.step(1) else player.next()
        Key.P -> if (live) desk.step(-1) else player.previous()
        else -> return false
    }
    return true
}

/** The player over the whole window: the picture, and controls that appear when the mouse moves. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(desk: Desk, player: VlcPlayer) {
    val entry = player.current
    if (entry == null) {
        LaunchedEffect(Unit) { desk.watching = false }
        return
    }
    val state by desk.tv.state.collectAsState()
    val live = entry.isLiveTv
    var moved by remember { mutableLongStateOf(System.nanoTime()) }
    var controls by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var overControls by remember { mutableStateOf(false) }
    LaunchedEffect(moved, player.status, menuOpen, overControls) {
        controls = true
        if (player.status == PlayStatus.PLAYING && !menuOpen && !overControls) {
            delay(3000)
            controls = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPointerEvent(PointerEventType.Move) { moved = System.nanoTime() }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val step = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (step != 0f) {
                    player.changeVolume(player.volume + if (step < 0) 5 else -5)
                    desk.rememberSound()
                    moved = System.nanoTime()
                }
            }
            .pointerHoverIcon(if (controls) PointerIcon.Default else hiddenCursor)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { moved = System.nanoTime() },
                onDoubleClick = { desk.fullscreen = !desk.fullscreen },
            ),
    ) {
        VideoPicture(player, desk.look, Modifier.fillMaxSize())

        when (player.status) {
            PlayStatus.LOADING -> CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(44.dp))
            PlayStatus.FAILED -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(player.problem, style = MaterialTheme.typography.titleLarge, color = TextHi, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Try again", { player.retry() }, icon = Icons.Rounded.Replay)
                    if (live && desk.tv.canStep) Quiet("Next channel", { desk.step(1) }, icon = Icons.Rounded.KeyboardArrowUp)
                }
            }
            PlayStatus.ENDED -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Finished", style = MaterialTheme.typography.titleLarge, color = TextHi)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Watch again", { player.retry() }, icon = Icons.Rounded.Replay)
                    Quiet("Back", { leavePlayer(desk) })
                }
            }
            else -> {}
        }

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // ---- top: back, what is on, and the smaller choices ---------------------------
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                        .onPointerEvent(PointerEventType.Enter) { overControls = true }
                        .onPointerEvent(PointerEventType.Exit) { overControls = false }
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, if (live) "Back to the list (Esc)" else "Stop and go back (Esc)", { leavePlayer(desk) }, size = 42.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (entry.artist.isNotBlank()) Text(entry.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    desk.tv.itemOf(entry)?.let { item ->
                        val starred = state.isFavourite(item)
                        RoundIcon(
                            if (starred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (starred) "Remove from favourites" else "Add to favourites",
                            { desk.tv.toggleFavourite(item) },
                            size = 42.dp,
                            tint = if (starred) Pink else Color.White,
                        )
                    }
                    val voices = player.audioTracks.filter { it.id >= 0 }
                    if (voices.size > 1) {
                        TrackMenu(Icons.Rounded.Audiotrack, "Sound track", voices, player.audioTrack, { menuOpen = it }) { player.chooseAudio(it) }
                    }
                    if (player.subtitles.any { it.id >= 0 }) {
                        TrackMenu(Icons.Rounded.Subtitles, "Subtitles", player.subtitles, player.subtitle, { menuOpen = it }) { player.chooseSubtitle(it) }
                    }
                    RoundIcon(Icons.Rounded.AspectRatio, "Picture shape: ${desk.look.label} (click to change)", {
                        val all = Look.entries
                        desk.changeLook(all[(desk.look.ordinal + 1) % all.size])
                        desk.say("Picture shape: ${desk.look.label}")
                    }, size = 42.dp)
                    RoundIcon(
                        if (desk.fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        if (desk.fullscreen) "Leave full screen (F)" else "Full screen (F)",
                        { desk.fullscreen = !desk.fullscreen },
                        size = 42.dp,
                    )
                }

                // ---- bottom: where it is up to, and the main buttons ---------------------------
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
                        .onPointerEvent(PointerEventType.Enter) { overControls = true }
                        .onPointerEvent(PointerEventType.Exit) { overControls = false }
                        .padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 14.dp),
                ) {
                    if (!live) {
                        var dragging by remember { mutableStateOf<Float?>(null) }
                        val duration = player.durationMs
                        val shown = dragging?.let { (it * duration).toLong() } ?: player.positionMs
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(clock(shown), style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.width(64.dp))
                            Track(
                                fraction = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                                enabled = player.seekable && duration > 0,
                                onChange = { dragging = it },
                                onDone = {
                                    player.seekTo((it * duration).toLong())
                                    dragging = null
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Text(if (duration > 0) clock(duration) else "", style = MaterialTheme.typography.labelMedium, color = Color.White, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Box(Modifier.fillMaxWidth()) {
                        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (live) {
                                RoundIcon(Icons.Rounded.KeyboardArrowDown, "Previous channel (Page Up)", { desk.step(-1) }, size = 46.dp, enabled = desk.tv.canStep)
                            } else {
                                RoundIcon(Icons.Rounded.SkipPrevious, "Previous episode", { player.previous() }, size = 46.dp, enabled = player.hasPrevious)
                                RoundIcon(Icons.Rounded.Replay10, "Back 10 seconds (left arrow)", { player.seekBy(-10_000) }, size = 46.dp, enabled = player.seekable)
                            }
                            val paused = player.status == PlayStatus.PAUSED || player.status == PlayStatus.ENDED || player.status == PlayStatus.FAILED
                            RoundIcon(
                                if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                if (paused) "Play (space bar)" else "Pause (space bar)",
                                { player.toggle() },
                                size = 60.dp,
                                tint = Color.Black,
                                backing = Color.White,
                            )
                            if (live) {
                                RoundIcon(Icons.Rounded.KeyboardArrowUp, "Next channel (Page Down)", { desk.step(1) }, size = 46.dp, enabled = desk.tv.canStep)
                            } else {
                                RoundIcon(Icons.Rounded.Forward10, "Forward 10 seconds (right arrow)", { player.seekBy(10_000) }, size = 46.dp, enabled = player.seekable)
                                RoundIcon(Icons.Rounded.SkipNext, "Next episode (N)", { player.next() }, size = 46.dp, enabled = player.hasNext)
                            }
                        }
                        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                            val silent = player.muted || player.volume == 0
                            RoundIcon(
                                if (silent) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                                if (silent) "Sound on (M)" else "Sound off (M)",
                                {
                                    player.toggleMute()
                                    desk.rememberSound()
                                },
                                size = 40.dp,
                            )
                            Track(
                                fraction = if (player.muted) 0f else player.volume / 100f,
                                enabled = true,
                                onChange = { player.changeVolume((it * 100).toInt()) },
                                onDone = { desk.rememberSound() },
                                modifier = Modifier.width(120.dp),
                            )
                        }
                        if (live) {
                            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(Pink, androidx.compose.foundation.shape.CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text("LIVE", style = MaterialTheme.typography.labelLarge, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackMenu(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tip: String,
    choices: List<TrackChoice>,
    chosen: Int,
    onOpen: (Boolean) -> Unit,
    onChoose: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundIcon(icon, tip, {
            open = true
            onOpen(true)
        }, size = 42.dp)
        DropdownMenu(
            expanded = open,
            onDismissRequest = {
                open = false
                onOpen(false)
            },
            containerColor = Surface2,
        ) {
            for (choice in choices) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (choice.id < 0) "Off" else choice.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (choice.id == chosen) Pink else TextHi,
                        )
                    },
                    onClick = {
                        onChoose(choice.id)
                        open = false
                        onOpen(false)
                    },
                )
            }
        }
    }
}

/** A line with a knob that can be dragged: how far through a film, how loud. */
@Composable
fun Track(fraction: Float, enabled: Boolean, onChange: (Float) -> Unit, onDone: (Float) -> Unit, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .height(22.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var at = (down.position.x / size.width).coerceIn(0f, 1f)
                    onChange(at)
                    drag(down.id) { change ->
                        at = (change.position.x / size.width).coerceIn(0f, 1f)
                        onChange(at)
                        change.consume()
                    }
                    onDone(at)
                }
            },
    ) {
        val middle = size.height / 2
        val thick = 4.dp.toPx()
        val reach = size.width * fraction.coerceIn(0f, 1f)
        drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(0f, middle - thick / 2), Size(size.width, thick), androidx.compose.ui.geometry.CornerRadius(thick / 2))
        drawRoundRect(Pink, Offset(0f, middle - thick / 2), Size(reach, thick), androidx.compose.ui.geometry.CornerRadius(thick / 2))
        if (enabled) drawCircle(Color.White, 7.dp.toPx(), Offset(reach.coerceIn(7.dp.toPx(), size.width - 7.dp.toPx()), middle))
    }
}
