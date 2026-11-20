package com.noexcusehere.mashwave.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.ui.AspectRatioFrameLayout
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.formatDuration
import com.noexcusehere.mashwave.iptv.TvProgramme
import com.noexcusehere.mashwave.iptv.isLiveTv
import com.noexcusehere.mashwave.iptv.zap
import com.noexcusehere.mashwave.ui.rememberPlaybackPosition
import com.noexcusehere.mashwave.ui.screens.VideoSurface
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface1
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextMid
import kotlinx.coroutines.delay

/**
 * The player on a TV, worked with the remote:
 * OK pauses and plays; left and right skip ten seconds in a film; up and down change channel;
 * the menu button (or holding OK) opens the choices for sound, subtitles and so on; Back leaves.
 */
@Composable
fun TvPlayer(ui: TvUi) {
    val app = LocalContext.current.app
    val state by app.player.state.collectAsStateWithLifecycle()
    val tv by app.tv.state.collectAsStateWithLifecycle()
    val entry = state.current
    val live = entry?.isLiveTv == true
    val position by rememberPlaybackPosition(500)
    val duration = app.player.durationMs

    var asked by remember { mutableIntStateOf(0) }
    var bar by remember { mutableStateOf(true) }
    var options by remember { mutableStateOf(false) }
    var fill by remember { mutableStateOf(false) }
    var heldOk by remember { mutableStateOf(false) }
    var okDown by remember { mutableStateOf(false) }
    val keys = remember { FocusRequester() }

    fun leave() {
        // Nothing carries on behind the lists on a TV: the film keeps its place, the channel lets go of its line.
        app.player.pause()
        app.player.stop()
        ui.playerOpen = false
    }
    BackHandler {
        if (options) options = false else leave()
    }

    // A TV starts its screensaver after a few minutes without a button press. Not while something plays.
    val view = LocalView.current
    val busy = state.isPlaying || state.isBuffering
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    // The bar with the name shows for a few seconds whenever something is pressed, and while paused.
    LaunchedEffect(asked, state.isPlaying, options) {
        bar = true
        if (state.isPlaying && !options) {
            delay(5000)
            bar = false
        }
    }
    // The picture holds the remote until the list of choices opens, and takes it back afterwards.
    if (!options) LandOn(keys)

    fun pressed(event: KeyEvent): Boolean {
        val down = event.type == KeyEventType.KeyDown
        when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                // Holding OK opens the choices; a short press pauses or plays.
                if (down) {
                    if (event.nativeKeyEvent.repeatCount == 0) {
                        okDown = true
                        heldOk = false
                    } else if (okDown && !heldOk) {
                        heldOk = true
                        options = true
                    }
                } else {
                    // Only a press that began here counts: the OK that opened the player lets go
                    // a moment after the player appears, and must not pause what it just started.
                    if (okDown && !heldOk) {
                        app.player.togglePlay()
                        asked++
                    }
                    okDown = false
                }
            }
            Key.MediaPlayPause, Key.Spacebar -> if (down) {
                app.player.togglePlay()
                asked++
            }
            Key.MediaPlay -> if (down && !state.isPlaying) app.player.togglePlay()
            Key.MediaPause -> if (down) app.player.pause()
            Key.DirectionLeft -> if (down) {
                if (!live) app.player.seekBy(-10_000)
                asked++
            }
            Key.DirectionRight -> if (down) {
                if (!live) app.player.seekBy(10_000)
                asked++
            }
            Key.MediaRewind -> if (down && !live) {
                app.player.seekBy(-30_000)
                asked++
            }
            Key.MediaFastForward -> if (down && !live) {
                app.player.seekBy(30_000)
                asked++
            }
            Key.DirectionDown, Key.ChannelDown -> if (down) {
                if (live) app.tv.zap(app.player, 1)
                asked++
            }
            Key.DirectionUp, Key.ChannelUp -> if (down) {
                if (live) app.tv.zap(app.player, -1)
                asked++
            }
            Key.MediaNext -> if (down) {
                if (live) app.tv.zap(app.player, 1) else app.player.next()
                asked++
            }
            Key.MediaPrevious -> if (down) {
                if (live) app.tv.zap(app.player, -1) else app.player.previous()
                asked++
            }
            Key.Menu -> if (!down) options = true
            else -> return false
        }
        return true
    }

    /** While the list of choices is open the arrows and OK belong to it; only these still reach the player. */
    fun pressedInChoices(event: KeyEvent): Boolean {
        val down = event.type == KeyEventType.KeyDown
        when (event.key) {
            Key.Menu -> if (!down) options = false
            Key.MediaPlayPause -> if (down) app.player.togglePlay()
            Key.MediaPlay -> if (down && !state.isPlaying) app.player.togglePlay()
            Key.MediaPause -> if (down) app.player.pause()
            else -> return false
        }
        return true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Buttons arrive here from whichever part holds the remote: the picture or the list of choices.
            .onKeyEvent { if (options) pressedInChoices(it) else pressed(it) },
    ) {
        VideoSurface(
            Modifier.fillMaxSize(),
            resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT,
        )
        // What holds the remote while only the picture is showing. It sits beside the list of
        // choices rather than around it, so that Back inside the list closes the list straight away.
        Box(Modifier.fillMaxSize().focusRequester(keys).focusable(enabled = !options))

        // While a stream is loading the picture shows its own spinner; this one covers the moment before there is a stream at all.
        if (entry == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 4.dp, modifier = Modifier.align(Alignment.Center).size(52.dp))
        }

        AnimatedVisibility(bar && entry != null && !options, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
                    .padding(start = 40.dp, end = 40.dp, top = 44.dp, bottom = 26.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (live) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Pink))
                        Spacer(Modifier.width(8.dp))
                        Text("LIVE", style = MaterialTheme.typography.labelLarge, color = Color.White)
                        Spacer(Modifier.width(14.dp))
                    }
                    Text(entry?.title.orEmpty(), style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (!state.isPlaying && !state.isBuffering) Text("Paused", style = MaterialTheme.typography.titleMedium, color = Pink)
                }
                if (live) {
                    // What the channel is showing now, when its service has a guide.
                    var now by remember(entry?.uri) { mutableStateOf<TvProgramme?>(null) }
                    LaunchedEffect(entry?.uri) { now = entry?.let { app.tv.guide(it) }?.firstOrNull() }
                    val line = now?.title?.takeIf { it.isNotBlank() } ?: entry?.artist.orEmpty()
                    if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                } else {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDuration(position), style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.width(84.dp))
                        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.25f))) {
                            val done = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                            Box(Modifier.fillMaxHeight().fillMaxWidth(done).background(Pink))
                        }
                        Text(if (duration > 0) formatDuration(duration) else "", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.width(84.dp).padding(start = 14.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                val ok = if (state.isPlaying || state.isBuffering) "OK pause" else "OK play"
                Text(
                    if (live) "$ok  ·  ▲ ▼ change channel  ·  hold OK for more  ·  Back to the list"
                    else "$ok  ·  ◀ ▶ skip 10 seconds  ·  hold OK for more  ·  Back to the list",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid,
                )
            }
        }

        if (options && entry != null) {
            val first = remember { FocusRequester() }
            LandOn(first)
            val item = app.tv.itemOf(entry)
            val starred = item != null && tv.isFavourite(item)
            // Read afresh each time the list is shown.
            val sound = remember(entry.uri, options) { app.player.tracks(C.TRACK_TYPE_AUDIO) }
            val words = remember(entry.uri, options) { app.player.tracks(C.TRACK_TYPE_TEXT) }
            var soundAt by remember(entry.uri, options) { mutableIntStateOf(sound.indexOfFirst { it.selected }) }
            var wordsAt by remember(entry.uri, options) { mutableIntStateOf(words.indexOfFirst { it.selected }) }
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(380.dp)
                    .background(Surface1.copy(alpha = 0.96f))
                    .padding(vertical = 24.dp),
            ) {
                Text("More", style = MaterialTheme.typography.headlineSmall, color = TextHi, modifier = Modifier.padding(start = 22.dp, bottom = 10.dp))
                LazyColumn {
                    if (sound.size > 1) {
                        item {
                            TvSideRow("Sound: " + (sound.getOrNull(soundAt)?.label ?: "the usual"), false, {
                                soundAt = (soundAt + 1) % sound.size
                                app.player.selectTrack(C.TRACK_TYPE_AUDIO, sound[soundAt])
                            }, requester = first)
                        }
                    }
                    if (words.isNotEmpty()) {
                        item {
                            TvSideRow("Subtitles: " + (words.getOrNull(wordsAt)?.label ?: "off"), false, {
                                // Round and round: each set of subtitles in turn, then off.
                                wordsAt = if (wordsAt + 1 >= words.size) -1 else wordsAt + 1
                                app.player.selectTrack(C.TRACK_TYPE_TEXT, words.getOrNull(wordsAt))
                            }, requester = if (sound.size > 1) null else first)
                        }
                    }
                    item {
                        TvSideRow("Picture: " + if (fill) "fills the screen" else "fits the screen", false, { fill = !fill }, requester = if (sound.size > 1 || words.isNotEmpty()) null else first)
                    }
                    if (item != null) {
                        item { TvSideRow(if (starred) "Remove from favourites" else "Add to favourites", false, { app.tv.toggleFavourite(item) }) }
                    }
                    if (!live && state.index < state.queue.lastIndex) {
                        item {
                            TvSideRow("Next episode", false, {
                                app.player.next()
                                options = false
                            })
                        }
                    }
                    if (!live) {
                        item {
                            TvSideRow("Start from the beginning", false, {
                                app.player.seekTo(0)
                                options = false
                            })
                        }
                    }
                    item { TvSideRow("Close", false, { options = false }) }
                }
            }
        }
    }
}
