package com.noexcusehere.mashwave.ui.screens

import android.content.Intent
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.noexcusehere.mashwave.R
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.iptv.Multiview
import com.noexcusehere.mashwave.iptv.MultiviewTile
import com.noexcusehere.mashwave.iptv.TvItem
import com.noexcusehere.mashwave.iptv.TvSearch
import com.noexcusehere.mashwave.iptv.TvSection
import com.noexcusehere.mashwave.ui.LocalUi
import com.noexcusehere.mashwave.ui.MwSheet
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface1
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextMid
import kotlinx.coroutines.delay

/**
 * Up to four live channels on screen at once. Tap a picture to hear it, double-tap to make it
 * big, and mirror the phone's screen to put all of them on a TV.
 */
@Composable
fun TvMultiviewScreen() {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val app = context.app
    val ui = LocalUi.current
    val view = app.multiview
    val tiles = view.tiles
    var bar by remember { mutableStateOf(true) }
    var touches by remember { mutableIntStateOf(0) }
    // -1: closed; 0..3: choosing a channel for that picture; 4: choosing one to add.
    var picking by remember { mutableIntStateOf(-1) }
    var mirrorHelp by remember { mutableStateOf(false) }
    // The picture shown on its own for a moment, or -1 for all of them.
    var big by remember { mutableIntStateOf(-1) }
    if (big >= tiles.size) big = -1

    fun close() {
        ui.multiviewOpen = false
    }

    BackHandler { if (big >= 0) big = -1 else close() }

    // The whole screen, sideways, kept awake - and every stream closed on the way out.
    DisposableEffect(activity) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            view.close()
        }
    }
    // Out of sight (another app, screen off): stop fetching four streams; rejoin on the way back.
    LifecycleStartEffect(Unit) {
        view.setActive(true)
        onStopOrDispose { view.setActive(false) }
    }
    LaunchedEffect(picking, mirrorHelp) {
        if (picking < 0 && !mirrorHelp) {
            delay(300)
            activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView).hide(WindowInsetsCompat.Type.systemBars()) }
        }
    }
    LaunchedEffect(bar, touches, picking, mirrorHelp) {
        if (bar && picking < 0 && !mirrorHelp && tiles.isNotEmpty()) {
            delay(4500)
            bar = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures {
                    bar = !bar
                    touches++
                }
            },
    ) {
        val shown = if (big >= 0) listOf(big) else tiles.indices.toList()
        // The "add" cell fills a space that would be empty anyway (none, one or three pictures).
        // With two, they share the screen side by side and adding is done from the bar.
        val addCell = big < 0 && !view.isFull && tiles.size != 2
        val cells = shown.size + if (addCell) 1 else 0
        val (rows, columns) = Multiview.grid(cells)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
            for (row in 0 until rows) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (column in 0 until columns) {
                        val cell = row * columns + column
                        Box(Modifier.weight(1f).fillMaxSize().padding(1.5.dp)) {
                            when {
                                cell < shown.size -> {
                                    val index = shown[cell]
                                    val tile = tiles[index]
                                    key(tile) {
                                        TileView(
                                            tile = tile,
                                            hasSound = index == view.sound,
                                            only = tiles.size == 1,
                                            chrome = bar,
                                            onTap = {
                                                view.giveSound(index)
                                                bar = true
                                                touches++
                                            },
                                            onDoubleTap = { big = if (big >= 0) -1 else index },
                                            onChange = { picking = index },
                                            onRemove = {
                                                big = -1
                                                view.remove(index)
                                            },
                                        )
                                    }
                                }
                                cell == shown.size && addCell -> AddCell(first = tiles.isEmpty()) { picking = Multiview.MAX }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = bar, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { close() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Leave multiview", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text("Multiview", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(
                        if (tiles.isEmpty()) "Add up to four channels" else "Tap a picture to hear it · double-tap to make it big",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!view.isFull) {
                    IconButton(onClick = { picking = Multiview.MAX }) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add a channel", tint = Color.White)
                    }
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.16f))
                        .clickable { mirrorHelp = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.ScreenShare, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Show on TV", style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
                Spacer(Modifier.width(6.dp))
            }
        }
    }

    if (picking >= 0) {
        ChannelPicker(
            replacing = tiles.getOrNull(picking)?.item,
            onDismiss = { picking = -1 },
            onPick = { item ->
                if (picking in tiles.indices) view.replace(picking, item) else view.add(item)
                picking = -1
            },
        )
    }
    if (mirrorHelp) {
        AlertDialog(
            onDismissRequest = { mirrorHelp = false },
            containerColor = Surface2,
            titleContentColor = TextHi,
            textContentColor = TextMid,
            title = { Text("All four on the TV", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "A TV can only be sent one channel at a time, so the way to see all of these on it is to mirror this screen: " +
                        "whatever the phone shows, the TV shows, with the sound.\n\n" +
                        "• Samsung phones call it Smart View.\n" +
                        "• Fire TV Stick: hold its Home button and choose Mirroring first.\n" +
                        "• Chromecast / Google TV: use Cast screen, or the Google Home app.\n\n" +
                        "Pick your TV on the next screen, then come back here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    mirrorHelp = false
                    val opened = listOf(Settings.ACTION_CAST_SETTINGS, "android.settings.WIFI_DISPLAY_SETTINGS").any { action ->
                        runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                    }
                    if (!opened) app.say("Swipe down from the top of the screen and tap Smart View, Cast or Screen cast")
                }) { Text("Open screen mirroring", color = Pink) }
            },
            dismissButton = { TextButton(onClick = { mirrorHelp = false }) { Text("Not now", color = TextMid) } },
        )
    }
}

@Composable
private fun TileView(
    tile: MultiviewTile,
    hasSound: Boolean,
    only: Boolean,
    chrome: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onChange: () -> Unit,
    onRemove: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0B0B12))
            .then(if (hasSound && !only) Modifier.border(2.dp, Pink, RoundedCornerShape(6.dp)) else Modifier),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.view_video_texture, null) as PlayerView).apply {
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    player = tile.player
                }
            },
            update = { view -> if (view.player !== tile.player) view.player = tile.player },
            onRelease = { view -> view.player = null },
        )
        // Touches are caught by a clear layer over the picture, so the video view never swallows them.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(tile) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { onDoubleTap() }) },
        )
        val problem = tile.problem
        if (problem != null) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(problem, style = MaterialTheme.typography.bodySmall, color = Color.White, textAlign = TextAlign.Center)
                TextButton(onClick = { tile.retry() }) { Text("Try again", color = Pink) }
            }
        } else if (tile.loading) {
            CircularProgressIndicator(color = Pink, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(34.dp))
        }
        // name and whether this is the one being heard
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (hasSound) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                contentDescription = if (hasSound) "Sound is on this channel" else "Muted",
                tint = if (hasSound) Pink else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(tile.item.name, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (chrome) {
            // Bottom right: the bar along the top of the screen would cover them anywhere higher.
            Row(Modifier.align(Alignment.BottomEnd).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SmallRound(Icons.Rounded.SwapHoriz, "Change this channel", onChange)
                SmallRound(Icons.Rounded.Close, "Remove this channel", onRemove)
            }
        }
    }
}

@Composable
private fun SmallRound(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun AddCell(first: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(6.dp))
            .background(Surface1)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = Pink, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(if (first) "Add a channel" else "Add another channel", style = MaterialTheme.typography.labelLarge, color = TextHi)
        if (first) {
            Text("Up to four at once", style = MaterialTheme.typography.bodySmall, color = TextMid)
        }
    }
}

/** Favourites and recent channels first; type to search every live channel. */
@Composable
private fun ChannelPicker(replacing: TvItem?, onDismiss: () -> Unit, onPick: (TvItem) -> Unit) {
    val app = LocalContext.current.app
    val state by app.tv.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val catalog = state.shelf(TvSection.LIVE).catalog
    LaunchedEffect(Unit) { app.tv.ensure(TvSection.LIVE) }
    val choices = remember(query, catalog, state.favourites, state.recents, app.multiview.tiles.size) {
        val base = if (query.trim().length >= 2) {
            TvSearch.find(catalog?.items.orEmpty(), query, limit = 80)
        } else {
            (state.favourites + state.recents + app.tv.channels.take(60) + catalog?.items.orEmpty().take(60))
                .filter { it.section == TvSection.LIVE }
                .distinctBy { it.id }
        }
        base.filterNot { app.multiview.has(it) }
    }
    MwSheet(onDismiss) {
        Text(
            if (replacing != null) "Change “${replacing.name}” to" else "Add a channel",
            style = MaterialTheme.typography.titleLarge,
            color = TextHi,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        TvSearchBox(query, { query = it }, "Search live channels", autoFocus = false, onClose = onDismiss)
        if (choices.isEmpty()) {
            Text(
                when {
                    catalog == null -> "Getting the channel list..."
                    query.isNotBlank() -> "No channel matches “${query.trim()}”."
                    else -> "No channels to add."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextMid,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().height(260.dp)) {
            items(choices, key = { it.id }) { item ->
                ChannelRow(
                    item = item,
                    number = 0,
                    playing = false,
                    animate = false,
                    favourite = state.isFavourite(item),
                    onStar = null,
                    subtitle = catalog.categoryOf(item),
                    onClick = { onPick(item) },
                )
            }
        }
    }
}
