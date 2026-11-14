package com.noexcusehere.mashwave.ui.screens

import android.os.Build
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.noexcusehere.mashwave.R
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.ui.LocalNav
import com.noexcusehere.mashwave.ui.LocalUi
import com.noexcusehere.mashwave.ui.Routes
import com.noexcusehere.mashwave.ui.SleepTimerSheet
import com.noexcusehere.mashwave.ui.SpeedSheet
import com.noexcusehere.mashwave.ui.TracksSheet
import com.noexcusehere.mashwave.ui.components.Artwork
import com.noexcusehere.mashwave.ui.components.GradientPlayButton
import com.noexcusehere.mashwave.ui.components.SeekBar
import com.noexcusehere.mashwave.ui.components.rememberAccent
import com.noexcusehere.mashwave.ui.rememberPlaybackPosition
import com.noexcusehere.mashwave.ui.theme.Ink
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextMid

/** Shows the main player's video picture. Only one of these should be on screen at a time. */
@Composable
fun VideoSurface(
    modifier: Modifier = Modifier,
    texture: Boolean = false,
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
) {
    val controller = LocalContext.current.app.player.controller
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val layout = if (texture) R.layout.view_video_texture else R.layout.view_video_surface
            (LayoutInflater.from(context).inflate(layout, null) as PlayerView).apply {
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view ->
            if (view.player !== controller) view.player = controller
            view.resizeMode = resizeMode
        },
        onRelease = { view -> view.player = null },
    )
}

@Composable
fun NowPlayingScreen() {
    val app = LocalContext.current.app
    val ui = LocalUi.current
    val nav = LocalNav.current
    val state by app.player.state.collectAsStateWithLifecycle()
    val data by app.store.data.collectAsStateWithLifecycle()
    val entry = state.current ?: return
    val accent = rememberAccent(entry)
    val position by rememberPlaybackPosition(250)
    val duration = app.player.durationMs
    val liked = data.favorites.any { it.uri == entry.uri }
    var sheet by remember { mutableStateOf("") }

    BackHandler { ui.nowPlayingOpen = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            // Swallow touches so nothing underneath reacts.
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount -> if (dragAmount > 28f) ui.nowPlayingOpen = false }
            },
    ) {
        // Backdrop: a huge blurred copy of the cover (newer phones) under a colour wash.
        if (Build.VERSION.SDK_INT >= 31 && !state.hasVideo) {
            Artwork(
                entry,
                Modifier
                    .fillMaxSize()
                    .blur(90.dp),
                RoundedCornerShape(0.dp),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.18f), Ink.copy(alpha = 0.92f), Ink),
                    ),
                ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- top bar ----
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { ui.nowPlayingOpen = false }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close player", tint = TextHi, modifier = Modifier.size(32.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = TextMid)
                    if (state.queue.size > 1) {
                        Text(
                            "${state.index + 1} of ${state.queue.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextHi,
                        )
                    }
                }
                com.noexcusehere.mashwave.ui.CastButton()
                IconButton(onClick = { ui.openMenu(entry) }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More options", tint = TextHi)
                }
            }

            // ---- cover or video ----
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.hasVideo) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black)
                            .clickable { ui.videoFullscreen = true },
                    ) {
                        VideoSurface(Modifier.fillMaxSize(), texture = true)
                        Icon(
                            Icons.Rounded.Fullscreen,
                            contentDescription = "Full screen",
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(6.dp),
                        )
                    }
                } else {
                    val side = min(maxWidth, maxHeight)
                    val scale by animateFloatAsState(
                        targetValue = if (state.isPlaying) 1f else 0.86f,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = 220f),
                        label = "coverScale",
                    )
                    Artwork(
                        entry,
                        Modifier
                            .size(side)
                            .scale(scale)
                            .shadow(30.dp, RoundedCornerShape(22.dp), ambientColor = accent, spotColor = accent),
                        RoundedCornerShape(22.dp),
                        large = true,
                    )
                }
            }

            // ---- title + like ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextHi,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    Text(
                        listOf(entry.subtitle, entry.album).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { app.store.toggleFavorite(entry) }) {
                    Icon(
                        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (liked) "Remove from Liked" else "Add to Liked",
                        tint = if (liked) Pink else TextHi,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            SeekBar(position, duration, onSeek = { app.player.seekTo(it) }, modifier = Modifier.fillMaxWidth())

            // ---- transport ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = { app.player.toggleShuffle() }) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = if (state.shuffle) "Shuffle is on" else "Shuffle is off",
                        tint = if (state.shuffle) Pink else TextMid,
                    )
                }
                IconButton(onClick = { app.player.previous() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = TextHi, modifier = Modifier.size(42.dp))
                }
                GradientPlayButton(playing = state.isPlaying, onClick = { app.player.togglePlay() }, size = 72.dp)
                IconButton(onClick = { app.player.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = TextHi, modifier = Modifier.size(42.dp))
                }
                IconButton(onClick = { app.player.cycleRepeat() }) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = when (state.repeatMode) {
                            Player.REPEAT_MODE_ONE -> "Repeating this track"
                            Player.REPEAT_MODE_ALL -> "Repeating the queue"
                            else -> "Repeat is off"
                        },
                        tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) TextMid else Pink,
                    )
                }
            }

            // ---- extras ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ExtraButton(
                    Icons.Rounded.Speed,
                    if (kotlin.math.abs(state.speed - 1f) < 0.01f) "Speed" else "%.2f×".format(state.speed),
                    active = kotlin.math.abs(state.speed - 1f) >= 0.01f,
                ) { sheet = "speed" }
                ExtraButton(Icons.Rounded.Bedtime, "Sleep", active = state.sleepTimerEndsAt > 0) { sheet = "sleep" }
                ExtraButton(Icons.Rounded.Equalizer, "Sound", active = data.fx.enabled) {
                    ui.nowPlayingOpen = false
                    nav.navigate(Routes.EQUALIZER)
                }
                if (state.hasVideo) {
                    ExtraButton(Icons.Rounded.Subtitles, "Tracks", active = false) { sheet = "tracks" }
                }
                ExtraButton(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", active = false) { ui.queueOpen = true }
            }
        }
    }

    when (sheet) {
        "speed" -> SpeedSheet { sheet = "" }
        "sleep" -> SleepTimerSheet { sheet = "" }
        "tracks" -> TracksSheet { sheet = "" }
    }
}

@Composable
private fun ExtraButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) Pink else TextHi, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) Pink else TextMid, maxLines = 1)
    }
}
