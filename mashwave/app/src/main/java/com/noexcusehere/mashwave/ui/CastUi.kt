package com.noexcusehere.mashwave.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.cast.CastKind
import com.noexcusehere.mashwave.cast.CastState
import com.noexcusehere.mashwave.ui.components.Artwork
import com.noexcusehere.mashwave.ui.components.GradientPlayButton
import com.noexcusehere.mashwave.ui.components.MwSlider
import com.noexcusehere.mashwave.ui.components.PillButton
import com.noexcusehere.mashwave.ui.components.SeekBar
import com.noexcusehere.mashwave.ui.components.TextPromptDialog
import com.noexcusehere.mashwave.ui.theme.Cyan
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid

/** The cast button used in headers and players; lights up while connected to a TV. */
@Composable
fun CastButton(tint: Color = TextHi) {
    val ui = LocalUi.current
    val state by LocalContext.current.app.cast.state.collectAsStateWithLifecycle()
    IconButton(onClick = { ui.castOpen = true }) {
        Icon(
            if (state.device != null) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
            contentDescription = if (state.device != null) "Playing on ${state.device?.name}" else "Play on a TV",
            tint = if (state.device != null) Cyan else tint,
        )
    }
}

/** Pick a TV, or control what is playing on the one that is connected. */
@Composable
fun CastSheet(onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    val state by app.cast.state.collectAsStateWithLifecycle()
    var helpOpen by remember { mutableStateOf(false) }
    var addressOpen by remember { mutableStateOf(false) }

    // Only look for devices while the list is on screen.
    val connected = state.device != null
    DisposableEffect(connected) {
        if (!connected) app.cast.startScan()
        onDispose { app.cast.stopScan() }
    }

    MwSheet(onDismiss) {
        if (connected) {
            Remote(state)
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 18.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Play on a TV", style = MaterialTheme.typography.titleLarge, color = TextHi)
                    Text("TVs and speakers on your Wi-Fi", style = MaterialTheme.typography.bodySmall, color = TextMid)
                }
                if (state.scanning) CircularProgressIndicator(Modifier.size(22.dp), color = Pink, strokeWidth = 2.5.dp)
            }
            if (!state.onNetwork) {
                Note("This phone isn't on Wi-Fi. Connect it to the same Wi-Fi as your TV.")
            }
            state.problem?.let { Note(it) }
            state.devices.forEach { device ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = state.connecting == null) { app.cast.connect(device) }
                        .padding(horizontal = 22.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Tv, contentDescription = null, tint = if (device.kind == CastKind.CHROMECAST) Cyan else Pink)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (device.kind == CastKind.CHROMECAST) "Chromecast" else "Smart TV") + "  ·  " + device.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMid,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.connecting == device.id) CircularProgressIndicator(Modifier.size(20.dp), color = Pink, strokeWidth = 2.5.dp)
                }
            }
            if (state.devices.isEmpty()) {
                Text(
                    if (state.scanning) "Looking for TVs…" else "No TVs found yet. Make sure the TV is on and on the same Wi-Fi as this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                )
            }
            Text(
                if (helpOpen) "Hide help" else "Can't see your TV or Fire Stick?",
                style = MaterialTheme.typography.labelLarge,
                color = Pink,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { helpOpen = !helpOpen }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
            if (helpOpen) {
                Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Help("Chromecast / Google TV", "Shows up by itself when the phone and the Chromecast are on the same Wi-Fi.")
                    Help("Smart TVs (Samsung, LG, Sony, Hisense…)", "Most appear by themselves. On some you first switch on “media sharing” or “DLNA” in the TV's network settings.")
                    Help("Fire TV Stick", "It has no built-in receiver. Install a free receiver app such as AirScreen on the Fire Stick and open it - the Fire Stick then appears in this list.")
                    Help("Still nothing?", "Guest Wi-Fi and some routers stop devices from seeing each other. You can also type the TV's address in by hand.")
                    PillButton("Enter the TV's address", onClick = { addressOpen = true })
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    if (addressOpen) {
        TextPromptDialog(
            title = "Add a TV by address",
            label = "Address",
            confirm = "Add",
            capitalize = false,
            onDismiss = { addressOpen = false },
            onConfirm = {
                app.cast.addByAddress(it)
                addressOpen = false
            },
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = TextHi,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Pink.copy(alpha = 0.16f))
            .padding(12.dp),
    )
}

@Composable
private fun Help(title: String, text: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = TextHi)
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextMid)
    }
}

/** Remote control for what is playing on the TV. */
@Composable
private fun Remote(state: CastState) {
    val app = LocalContext.current.app
    val entry = state.entry
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CastConnected, contentDescription = null, tint = Cyan)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Playing on", style = MaterialTheme.typography.bodySmall, color = TextMid)
            Text(state.device?.name.orEmpty(), style = MaterialTheme.typography.titleLarge, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PillButton("Stop casting", onClick = { app.cast.disconnect() }, tint = Pink)
    }
    state.problem?.let { Note(it) }
    if (entry == null) {
        Text(
            "Connected. Pick any song or video in the app and it plays on the TV.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMid,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
        )
    } else {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(entry, if (entry.isVideo) Modifier.size(width = 96.dp, height = 60.dp) else Modifier.size(60.dp), RoundedCornerShape(10.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (state.loading) "Starting on the TV…" else entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                    maxLines = 1,
                )
            }
        }
        SeekBar(
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            onSeek = { app.cast.seekTo(it) },
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { app.cast.previous() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = TextHi, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.width(18.dp))
            GradientPlayButton(playing = state.playing, onClick = { app.cast.togglePlay() }, size = 64.dp)
            Spacer(Modifier.width(18.dp))
            IconButton(onClick = { app.cast.next() }, modifier = Modifier.size(56.dp), enabled = state.index < state.queue.lastIndex) {
                Icon(
                    Icons.Rounded.SkipNext,
                    contentDescription = "Next",
                    tint = if (state.index < state.queue.lastIndex) TextHi else TextLow,
                    modifier = Modifier.size(38.dp),
                )
            }
        }
    }
    val volume = state.volume
    if (volume != null) {
        var dragging by remember { mutableStateOf(false) }
        var shown by remember { mutableFloatStateOf(volume) }
        if (!dragging) shown = volume
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "TV volume", tint = TextMid, modifier = Modifier.padding(start = 6.dp))
            MwSlider(
                value = shown,
                onValueChange = {
                    dragging = true
                    shown = it
                },
                onValueChangeFinished = {
                    dragging = false
                    app.cast.setVolume(shown)
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Sits above the bottom bar while connected to a TV, like the mini player does for the phone. */
@Composable
fun CastBar(state: CastState, onOpen: () -> Unit) {
    val app = LocalContext.current.app
    val entry = state.entry
    Row(
        Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .background(Brush.horizontalGradient(listOf(Cyan.copy(alpha = 0.38f), Cyan.copy(alpha = 0.06f))))
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (entry?.isVideo == false) Icons.Rounded.Speaker else Icons.Rounded.Tv, contentDescription = null, tint = Cyan)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry?.title ?: "Connected - pick something to play",
                style = MaterialTheme.typography.titleSmall,
                color = TextHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("On ${state.device?.name.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (entry != null) {
            if (state.loading) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Cyan, strokeWidth = 2.5.dp)
                }
            } else {
                IconButton(onClick = { app.cast.togglePlay() }) {
                    Icon(
                        if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Play",
                        tint = TextHi,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
    }
}
