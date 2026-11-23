package com.noexcusehere.mashwave.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noexcusehere.mashwave.desktop.Desk
import com.noexcusehere.mashwave.iptv.isLiveTv

/** The box for choosing a TV to play on. TVs are looked for while it is open. */
@Composable
fun CastSheet(desk: Desk) {
    val cast = desk.cast
    var address by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        cast.startScan()
        onDispose { cast.stopScan() }
    }
    Sheet("Play on a TV", { desk.castOpen = false }) {
        Text(
            "Songs, videos and TV channels can be played on a smart TV on your home network instead of on this computer. Choose the TV; whatever you start after that plays there.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMid,
        )
        Spacer(Modifier.height(12.dp))

        val connected = cast.device
        if (connected != null) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2).border(1.dp, Violet, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CastConnected, contentDescription = null, tint = Violet, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Playing on ${connected.name}", style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(connected.detail, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Quiet("Stop playing on the TV", { cast.disconnect() }, icon = Icons.Rounded.Close)
            }
            Spacer(Modifier.height(12.dp))
        }

        if (!cast.onNetwork) {
            Text("This computer is not on a home network, so no TV can be reached. Connect it to the same network as the TV.", style = MaterialTheme.typography.bodyMedium, color = Amber)
            Spacer(Modifier.height(10.dp))
        }

        val others = cast.devices.filter { it.id != connected?.id }
        if (others.isNotEmpty()) {
            Text(if (connected == null) "TVs found" else "Other TVs", style = MaterialTheme.typography.titleSmall, color = TextHi)
            Spacer(Modifier.height(6.dp))
        }
        for (found in others) {
            val busy = cast.connecting == found.id
            Row(
                Modifier
                    .padding(vertical = 3.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface1)
                    .border(1.dp, Outline, RoundedCornerShape(12.dp))
                    .clickable(enabled = cast.connecting == null) { cast.connect(found) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Tv, contentDescription = null, tint = TextMid, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(found.name, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(found.detail, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (busy) CircularProgressIndicator(color = Violet, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (cast.scanning) {
                CircularProgressIndicator(color = Violet, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text("Looking for TVs...", style = MaterialTheme.typography.bodyMedium, color = TextMid)
            } else if (cast.devices.isEmpty() && cast.onNetwork) {
                Text(
                    "No TV found yet. Make sure the TV is switched on and on the same network as this computer. The first time, Windows may ask whether to let the app through its firewall: say yes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid,
                )
            }
        }

        cast.problem?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Amber)
        }

        Spacer(Modifier.height(16.dp))
        Text("TV not in the list? Add it by its address", style = MaterialTheme.typography.titleSmall, color = TextHi)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(address, { address = it }, "For example 192.168.1.20:8080/description.xml", Modifier.weight(1f), onEnter = {
                cast.addByAddress(address)
                address = ""
            })
            Spacer(Modifier.width(8.dp))
            Quiet("Add", {
                cast.addByAddress(address)
                address = ""
            }, icon = Icons.Rounded.Add, enabled = address.isNotBlank())
        }
    }
}

/** What is playing on the TV, along the bottom of the window, with its controls. */
@Composable
fun CastBar(desk: Desk) {
    val cast = desk.cast
    val tv = cast.device ?: return
    val entry = cast.entry
    val live = entry?.isLiveTv == true
    var dragging by remember { mutableStateOf(false) }
    var dragAt by remember { mutableFloatStateOf(0f) }
    val length = cast.durationMs
    val fraction = if (dragging) dragAt else if (length > 0) (cast.positionMs.toFloat() / length).coerceIn(0f, 1f) else 0f

    Row(
        Modifier.fillMaxWidth().height(78.dp).background(Surface1).border(1.dp, Outline).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Violet.copy(alpha = 0.25f)).clickable { desk.castOpen = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.CastConnected, contentDescription = "Playing on a TV", tint = TextHi, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(entry?.title ?: "Connected to ${tv.name}", style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val problem = cast.problem
                Text(
                    when {
                        problem != null -> problem
                        entry == null -> "Pick something and it plays on the TV."
                        cast.loading -> "Starting on ${tv.name}..."
                        cast.playing -> "Playing on ${tv.name}"
                        else -> "On ${tv.name} - not playing"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem != null) Amber else TextMid,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(Modifier.weight(1.2f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundIcon(Icons.Rounded.SkipPrevious, if (live) "Previous channel" else "Previous", { cast.previous() }, size = 36.dp, enabled = entry != null)
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color.White).dimmedUnless(entry != null).clickable(enabled = entry != null) { cast.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (cast.loading) {
                        CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(if (cast.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (cast.playing) "Pause" else "Play", tint = Ink, modifier = Modifier.size(24.dp))
                    }
                }
                RoundIcon(Icons.Rounded.SkipNext, if (live) "Next channel" else "Next", { cast.next() }, size = 36.dp, enabled = entry != null && cast.hasNext)
            }
            if (entry != null && !live) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(clock(if (dragging) (dragAt * length).toLong() else cast.positionMs), style = MaterialTheme.typography.labelSmall, color = TextLow, modifier = Modifier.width(52.dp))
                    Track(
                        fraction,
                        enabled = length > 0,
                        onChange = {
                            dragging = true
                            dragAt = it
                        },
                        onDone = {
                            cast.seekTo((it * length).toLong())
                            dragging = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (length > 0) clock(length) else "", style = MaterialTheme.typography.labelSmall, color = TextLow, modifier = Modifier.width(52.dp).padding(start = 8.dp))
                }
            }
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            if (cast.volume >= 0f) {
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "The TV's loudness", tint = TextMid, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Track(cast.volume, enabled = true, onChange = { cast.changeVolume(it) }, onDone = { cast.changeVolume(it) }, modifier = Modifier.width(110.dp))
                Spacer(Modifier.width(10.dp))
            }
            Quiet("Stop playing on the TV", { cast.disconnect() }, icon = Icons.Rounded.Close, tint = TextMid)
        }
    }
}

/** The entry in the side menu that opens the "play on a TV" box; it shows when a TV is in use. */
@Composable
fun CastButton(desk: Desk) {
    val tv = desk.cast.device
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (tv != null) Violet.copy(alpha = 0.24f) else Color.Transparent)
            .clickable { desk.castOpen = true }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (tv != null) Icons.Rounded.CastConnected else Icons.Rounded.Cast, contentDescription = null, tint = if (tv != null) TextHi else TextMid, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(tv?.name ?: "Play on a TV", style = MaterialTheme.typography.titleMedium, color = if (tv != null) TextHi else TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (tv != null) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.CheckCircle, contentDescription = "In use", tint = Violet, modifier = Modifier.size(15.dp))
        }
    }
}
