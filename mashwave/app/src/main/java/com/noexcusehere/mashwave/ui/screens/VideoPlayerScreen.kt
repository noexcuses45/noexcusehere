package com.noexcusehere.mashwave.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.data.formatDuration
import com.noexcusehere.mashwave.playback.ResumeNotice
import com.noexcusehere.mashwave.ui.LocalUi
import com.noexcusehere.mashwave.ui.SpeedSheet
import com.noexcusehere.mashwave.ui.TracksSheet
import com.noexcusehere.mashwave.iptv.TvProgramme
import com.noexcusehere.mashwave.iptv.isLiveTv
import com.noexcusehere.mashwave.ui.components.MwSlider
import com.noexcusehere.mashwave.ui.rememberPlaybackPosition
import com.noexcusehere.mashwave.ui.theme.BrandGradient
import com.noexcusehere.mashwave.ui.theme.Pink
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Full-screen video with VLC-style gestures: tap to show controls, double-tap either side to
 * skip 10 seconds, hold to fast-forward, slide sideways to move through the video, drag up/down
 * on the left for brightness and on the right for volume.
 */
@Composable
fun VideoPlayerScreen() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val app = context.app
    val ui = LocalUi.current
    val state by app.player.state.collectAsStateWithLifecycle()
    val latest by rememberUpdatedState(state)
    val position by rememberPlaybackPosition(250)
    val duration = app.player.durationMs
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var sheet by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<Pair<ImageVector, String>?>(null) }
    var hintTick by remember { mutableIntStateOf(0) }
    var touchTick by remember { mutableIntStateOf(0) }
    var rotationChosen by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    // Holding a finger down plays at double speed until it is lifted.
    var boosting by remember { mutableStateOf(false) }
    var speedBefore by remember { mutableFloatStateOf(1f) }
    // While a finger slides sideways: the place it would jump to, and where it started from.
    var scrub by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    // "Carried on from 12:34", shown for a few seconds after a video resumes.
    // A live channel has no beginning or end: no seeking, and "next" means the next channel.
    val live = state.current?.isLiveTv == true
    val tv by app.tv.state.collectAsStateWithLifecycle()
    var guide by remember { mutableStateOf<List<TvProgramme>>(emptyList()) }
    LaunchedEffect(state.current?.uri, live) {
        guide = emptyList()
        val channel = state.current
        if (live && channel != null) {
            while (true) {
                guide = app.tv.guide(channel)
                delay(60_000)
            }
        }
    }
    val resumed by app.player.resumed.collectAsStateWithLifecycle()
    var resumeNote by remember { mutableStateOf<ResumeNotice?>(null) }
    LaunchedEffect(resumed) {
        val notice = resumed
        // Recent only: reopening the player ten minutes later must not announce it again.
        if (notice != null && System.currentTimeMillis() - notice.at < 15_000) {
            resumeNote = notice
            delay(8000)
            resumeNote = null
        }
    }

    fun close() {
        ui.videoFullscreen = false
    }

    fun showHint(icon: ImageVector, text: String) {
        hint = icon to text
        hintTick++
    }

    BackHandler { if (locked) showHint(Icons.Rounded.Lock, "Screen locked") else close() }

    // Hide the system bars, keep the screen awake, and undo all of it on the way out.
    DisposableEffect(activity) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.let {
                val attributes = it.attributes
                attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                it.attributes = attributes
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Turn the screen to match the shape of the video, unless the user picked a rotation.
    LaunchedEffect(state.videoWidth, state.videoHeight) {
        if (!rotationChosen && state.videoWidth > 0 && state.videoHeight > 0) {
            activity?.requestedOrientation = if (state.videoWidth >= state.videoHeight) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
    }

    // Controls fade away after a few seconds of playback.
    LaunchedEffect(controls, state.isPlaying, touchTick, sheet) {
        if (controls && state.isPlaying && sheet.isEmpty()) {
            delay(3500)
            controls = false
        }
    }
    // Stopped (paused from a headset, or a channel that failed): bring the controls back, so the
    // way out - play again, another channel, back - is on screen instead of a bare picture.
    LaunchedEffect(state.isPlaying, state.isBuffering) {
        if (!state.isPlaying && !state.isBuffering && state.current != null) controls = true
    }
    LaunchedEffect(hintTick) {
        if (hint != null) {
            delay(900)
            hint = null
        }
    }
    // If the queue moves on to a plain song, drop back to the normal player.
    LaunchedEffect(state.current?.uri) {
        val current = state.current
        if (current == null) {
            // Nothing loaded yet (e.g. opened from a file manager): give the player a moment.
            delay(6000)
            if (latest.current == null) close()
        } else if (!current.isVideo) {
            delay(2500)
            if (!latest.hasVideo) {
                close()
                ui.nowPlayingOpen = true
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        VideoSurface(Modifier.fillMaxSize(), texture = false, resizeMode = resizeMode)

        // ---- gesture layer ----
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(
                        onPress = {
                            tryAwaitRelease()
                            if (boosting) {
                                boosting = false
                                app.player.setSpeed(speedBefore)
                            }
                        },
                        onLongPress = {
                            if (!locked && latest.isPlaying && latest.current?.isLiveTv != true) {
                                speedBefore = latest.speed
                                boosting = true
                                app.player.setSpeed(maxOf(2f, speedBefore * 2f).coerceAtMost(4f))
                            }
                        },
                        onTap = {
                            controls = !controls
                            touchTick++
                        },
                        onDoubleTap = { offset ->
                            if (!locked && latest.current?.isLiveTv != true) {
                                if (offset.x < size.width / 2f) {
                                    app.player.seekBy(-10_000)
                                    showHint(Icons.Rounded.Replay10, "-10 s")
                                } else {
                                    app.player.seekBy(10_000)
                                    showHint(Icons.Rounded.Forward10, "+10 s")
                                }
                            }
                        },
                    )
                }
                .pointerInput(locked) {
                    // Slide a finger sideways to move back or forward through the video.
                    if (locked) return@pointerInput
                    var start = 0L
                    var length = 0L
                    var total = 0f
                    var active = false
                    // On a live channel the same slide changes channel instead.
                    var zapping = false
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            // Leave the screen edges to the system's own "back" swipe.
                            val edge = 40.dp.toPx()
                            val inside = offset.x > edge && offset.x < size.width - edge
                            val onLive = latest.current?.isLiveTv == true
                            length = app.player.durationMs
                            active = !onLive && length > 0 && inside
                            zapping = onLive && inside && app.tv.canStep
                            start = app.player.positionMs
                            total = 0f
                        },
                        onDragEnd = {
                            if (active) scrub?.let { app.player.seekTo(it.first) }
                            if (zapping && kotlin.math.abs(total) > 72.dp.toPx()) {
                                val forward = total < 0
                                if (app.tv.zap(app.player, if (forward) 1 else -1)) {
                                    showHint(if (forward) Icons.Rounded.SkipNext else Icons.Rounded.SkipPrevious, if (forward) "Next channel" else "Previous channel")
                                }
                            }
                            scrub = null
                            active = false
                            zapping = false
                        },
                        onDragCancel = {
                            scrub = null
                            active = false
                            zapping = false
                        },
                        onHorizontalDrag = { input, delta ->
                            if (zapping) {
                                input.consume()
                                total += delta
                            }
                            if (active) {
                                input.consume()
                                total += delta
                                // One sweep across the screen covers a fifth of the video (1 to 10 minutes).
                                val span = (length / 5).coerceIn(60_000L, 600_000L).coerceAtMost(length)
                                scrub = (start + (total / size.width * span).toLong()).coerceIn(0, length) to start
                            }
                        },
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    var onLeft = false
                    var startValue = 0f
                    var total = 0f
                    var active = false
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            // Leave the screen edges to the system's own swipe gestures.
                            val edge = 48.dp.toPx()
                            active = offset.y > edge && offset.y < size.height - edge
                            onLeft = offset.x < size.width / 2f
                            total = 0f
                            startValue = if (onLeft) {
                                val current = activity?.window?.attributes?.screenBrightness ?: -1f
                                if (current < 0f) 0.5f else current
                            } else {
                                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
                            }
                        },
                        onVerticalDrag = { input, delta ->
                            if (active) {
                                input.consume()
                                total += delta
                                val value = (startValue - total / (size.height * 0.75f)).coerceIn(0f, 1f)
                                if (onLeft) {
                                    activity?.window?.let {
                                        val attributes = it.attributes
                                        attributes.screenBrightness = value.coerceAtLeast(0.01f)
                                        it.attributes = attributes
                                    }
                                    showHint(Icons.Rounded.BrightnessMedium, "${(value * 100).roundToInt()}%")
                                } else {
                                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    runCatching {
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * max).roundToInt(), 0)
                                    }
                                    showHint(Icons.AutoMirrored.Rounded.VolumeUp, "${(value * 100).roundToInt()}%")
                                }
                            }
                        },
                    )
                },
        )

        if (state.isBuffering) {
            CircularProgressIndicator(color = Pink, modifier = Modifier.align(Alignment.Center).size(52.dp))
        }

        // ---- gesture feedback bubble ----
        hint?.let { (icon, text) ->
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(text, style = MaterialTheme.typography.titleSmall, color = Color.White)
            }
        }

        // ---- fast-forward and seek feedback ----
        if (boosting) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.FastForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Fast forward - let go to play normally", style = MaterialTheme.typography.titleSmall, color = Color.White)
            }
        }
        scrub?.let { (target, from) ->
            val moved = target - from
            Column(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    (if (moved < 0) "−" else "+") + formatDuration(kotlin.math.abs(moved)),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Text(
                    formatDuration(target) + "  /  " + formatDuration(duration),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
        resumeNote?.let { note ->
            if (note.uri == state.current?.uri && !locked) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 76.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Carried on from ${formatDuration(note.positionMs)}", style = MaterialTheme.typography.titleSmall, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Start over",
                        style = MaterialTheme.typography.labelLarge,
                        color = Pink,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                app.store.clearResume(note.uri)
                                app.player.seekTo(0)
                                resumeNote = null
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // ---- controls ----
        AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut()) {
            if (locked) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
                    RoundControl(Icons.Rounded.Lock, "Unlock controls", Modifier.align(Alignment.CenterStart).padding(start = 20.dp)) {
                        locked = false
                        touchTick++
                    }
                }
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                            ),
                        )
                        .windowInsetsPadding(WindowInsets.displayCutout),
                ) {
                    // top bar
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { close() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        Text(
                            state.current?.title ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        com.noexcusehere.mashwave.ui.CastButton(com.noexcusehere.mashwave.cast.CastWhat.VIDEO, tint = Color.White)
                        IconButton(onClick = { sheet = "tracks" }) {
                            Icon(Icons.Rounded.Subtitles, contentDescription = "Audio and subtitles", tint = Color.White)
                        }
                        if (live) {
                            IconButton(onClick = {
                                // The channel that is on becomes the first of up to four pictures.
                                app.multiview.begin(app.tv.itemOf(state.current))
                                close()
                                ui.multiviewOpen = true
                            }) {
                                Icon(Icons.Rounded.GridView, contentDescription = "Multiview: watch up to four channels at once", tint = Color.White)
                            }
                        }
                        if (!live) {
                            IconButton(onClick = { sheet = "speed" }) {
                                Icon(Icons.Rounded.Speed, contentDescription = "Playback speed", tint = Color.White)
                            }
                        }
                        IconButton(onClick = {
                            resizeMode = when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                            showHint(
                                Icons.Rounded.AspectRatio,
                                when (resizeMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom to fill"
                                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
                                    else -> "Fit to screen"
                                },
                            )
                            touchTick++
                        }) {
                            Icon(Icons.Rounded.AspectRatio, contentDescription = "Change picture size", tint = Color.White)
                        }
                        IconButton(onClick = {
                            rotationChosen = true
                            val landscape = activity?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            activity?.requestedOrientation = if (landscape) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                            touchTick++
                        }) {
                            Icon(Icons.Rounded.ScreenRotation, contentDescription = "Rotate screen", tint = Color.White)
                        }
                        IconButton(onClick = {
                            locked = true
                            showHint(Icons.Rounded.Lock, "Controls locked")
                        }) {
                            Icon(Icons.Rounded.LockOpen, contentDescription = "Lock controls", tint = Color.White)
                        }
                    }

                    // centre transport
                    Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        if (live) {
                            if (app.tv.canStep) {
                                RoundControl(Icons.Rounded.SkipPrevious, "Previous channel") {
                                    app.tv.zap(app.player, -1)
                                    touchTick++
                                }
                            }
                        } else {
                            if (state.queue.size > 1) {
                                RoundControl(Icons.Rounded.SkipPrevious, "Previous") {
                                    app.player.previous()
                                    touchTick++
                                }
                            }
                            RoundControl(Icons.Rounded.Replay10, "Back 10 seconds") {
                                app.player.seekBy(-10_000)
                                touchTick++
                            }
                        }
                        Box(
                            Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(BrandGradient)
                                .clickable {
                                    app.player.togglePlay()
                                    touchTick++
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (state.isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(42.dp),
                            )
                        }
                        if (live) {
                            if (app.tv.canStep) {
                                RoundControl(Icons.Rounded.SkipNext, "Next channel") {
                                    app.tv.zap(app.player, 1)
                                    touchTick++
                                }
                            }
                        } else {
                            RoundControl(Icons.Rounded.Forward10, "Forward 10 seconds") {
                                app.player.seekBy(10_000)
                                touchTick++
                            }
                            if (state.queue.size > 1) {
                                RoundControl(Icons.Rounded.SkipNext, "Next") {
                                    app.player.next()
                                    touchTick++
                                }
                            }
                        }
                    }

                    // bottom: what is on (live channels) or the seek bar (everything else)
                    val channel = state.current
                    if (live && channel != null) {
                        val item = remember(channel.uri, tv.recents) { app.tv.itemOf(channel) }
                        LiveBar(
                            entry = channel,
                            guide = guide,
                            favourite = item != null && tv.isFavourite(item),
                            onStar = item?.let { found -> { app.tv.toggleFavourite(found); touchTick++ } },
                            onChannels = if (app.tv.canStep) ({ sheet = "channels" }) else null,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    } else Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                        val shown = if (dragging) dragValue else fraction
                        Text(
                            formatDuration((shown * duration).toLong()),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                        )
                        MwSlider(
                            value = shown,
                            onValueChange = {
                                dragging = true
                                dragValue = it
                                touchTick++
                            },
                            onValueChangeFinished = {
                                if (dragging) app.player.seekTo((dragValue * duration).toLong())
                                dragging = false
                            },
                            enabled = duration > 0,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp),
                        )
                        Text(formatDuration(duration), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                }
            }
        }
    }

    when (sheet) {
        "tracks" -> TracksSheet { sheet = "" }
        "speed" -> SpeedSheet { sheet = "" }
        "channels" -> ChannelsSheet(state.current) { sheet = "" }
    }
}

/** What a live channel shows instead of a seek bar: the channel, and what is on now and next. */
@Composable
private fun LiveBar(
    entry: MediaEntry,
    guide: List<TvProgramme>,
    favourite: Boolean,
    onStar: (() -> Unit)?,
    onChannels: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val now = System.currentTimeMillis()
    val current = guide.firstOrNull { it.startMs <= now && it.endMs > now }
    val next = guide.firstOrNull { it.startMs > now }
    val clock = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.noexcusehere.mashwave.ui.components.WebPicture(
            entry.art,
            Modifier.size(width = 76.dp, height = 52.dp),
            RoundedCornerShape(10.dp),
            seed = entry.title,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "LIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFFE11D48))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(entry.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (current != null) {
                Text(
                    current.title + "  ·  " + clock.format(java.util.Date(current.startMs)) + " – " + clock.format(java.util.Date(current.endMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Box(Modifier.fillMaxWidth(current.progress(now)).height(3.dp).background(BrandGradient))
                }
            }
            if (next != null) {
                Text(
                    "Next: " + next.title + " at " + clock.format(java.util.Date(next.startMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (current == null && next == null) {
                Text(entry.subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onStar != null) {
            IconButton(onClick = onStar) {
                Icon(
                    if (favourite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = if (favourite) "Remove from favourites" else "Add to favourites",
                    tint = if (favourite) Color(0xFFFFB648) else Color.White,
                )
            }
        }
        if (onChannels != null) {
            IconButton(onClick = onChannels) {
                Icon(Icons.Rounded.FormatListBulleted, contentDescription = "Channel list", tint = Color.White)
            }
        }
    }
}

/** The list the current channel came from, to jump straight to another one. */
@Composable
private fun ChannelsSheet(current: MediaEntry?, onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    val tv by app.tv.state.collectAsStateWithLifecycle()
    val channels = remember { app.tv.channels }
    val playingId = current?.let { app.tv.itemOf(it)?.id }
    val at = channels.indexOfFirst { it.id == playingId }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (at - 2).coerceAtLeast(0))
    com.noexcusehere.mashwave.ui.MwSheet(onDismiss) {
        Text(
            "Channels",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(420.dp)) {
            items(channels.size, key = { channels[it].id }) { index ->
                val item = channels[index]
                ChannelRow(
                    item = item,
                    number = index + 1,
                    playing = item.id == playingId,
                    animate = true,
                    favourite = tv.isFavourite(item),
                    onStar = { app.tv.toggleFavourite(item) },
                    onClick = {
                        app.tv.tune(channels, index)?.let { app.player.play(listOf(it)) }
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun RoundControl(icon: ImageVector, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}
