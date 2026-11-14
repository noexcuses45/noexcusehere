package com.noexcusehere.mashwave.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noexcusehere.mashwave.APP_NAME
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.automash.AutoMashupMaker
import com.noexcusehere.mashwave.data.AutoMashup
import com.noexcusehere.mashwave.data.formatDuration
import com.noexcusehere.mashwave.ui.LocalUi
import com.noexcusehere.mashwave.ui.SongPickerSheet
import com.noexcusehere.mashwave.ui.components.Artwork
import com.noexcusehere.mashwave.ui.components.ConfirmDialog
import com.noexcusehere.mashwave.ui.components.PillButton
import com.noexcusehere.mashwave.ui.components.SectionTitle
import com.noexcusehere.mashwave.ui.theme.BrandGradient
import com.noexcusehere.mashwave.ui.theme.BrandGradientHorizontal
import com.noexcusehere.mashwave.ui.theme.Outline
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface1
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.Surface3
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid
import com.noexcusehere.mashwave.ui.theme.Violet
import kotlin.math.roundToInt

/** The simple way to make a mashup: choose songs, press one button. */
@Composable
fun AutoMashupSection() {
    val app = LocalContext.current.app
    val ui = LocalUi.current
    val maker = app.autoMashup
    val state by maker.state.collectAsStateWithLifecycle()
    val data by app.store.data.collectAsStateWithLifecycle()
    val player by app.player.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AutoMashup?>(null) }

    // Mixing takes a little while; don't let the screen switch off in the middle.
    val view = LocalView.current
    DisposableEffect(state.working) {
        view.keepScreenOn = state.working
        onDispose { view.keepScreenOn = false }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- what this does ----
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Violet.copy(alpha = 0.35f), Pink.copy(alpha = 0.22f))))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(BrandGradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Auto Mashup", style = MaterialTheme.typography.titleLarge, color = TextHi)
                Text(
                    "Pick 2 to ${AutoMashupMaker.MAX_SONGS} songs. $APP_NAME lines up their beats, matches their keys and blends them into one new track.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid,
                )
            }
        }

        // ---- the chosen songs ----
        state.songs.forEachIndexed { index, entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface1)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${index + 1}", style = MaterialTheme.typography.titleMedium, color = Pink, modifier = Modifier.width(22.dp))
                Artwork(entry, Modifier.size(44.dp), RoundedCornerShape(8.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(entry.subtitle, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { maker.remove(entry.uri) }, enabled = !state.working) {
                    Icon(Icons.Rounded.Close, contentDescription = "Remove ${entry.title}", tint = if (state.working) TextLow else TextMid)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(
                text = if (state.songs.isEmpty()) "Choose songs" else "Add songs",
                onClick = { picking = true },
                icon = Icons.Rounded.Add,
                enabled = !state.working && state.songs.size < AutoMashupMaker.MAX_SONGS,
                filled = state.songs.isEmpty(),
            )
            if (state.songs.isNotEmpty() && !state.working) {
                PillButton("Clear", { maker.clear() })
            }
        }

        // ---- the one button ----
        if (!state.working) {
            val ready = state.songs.size >= 2
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(50))
                    .then(if (ready) Modifier.background(BrandGradient) else Modifier.background(Surface2))
                    .clickable(enabled = ready) { maker.create() },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = if (ready) Color.White else TextLow)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (ready) "Create mashup" else "Choose at least two songs",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (ready) Color.White else TextLow,
                    )
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Surface1)
                    .border(1.dp, Outline, RoundedCornerShape(20.dp))
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.step,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextHi,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${(state.progress * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium, color = Pink)
                }
                Spacer(Modifier.height(10.dp))
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                ) {
                    val radius = CornerRadius(size.height / 2, size.height / 2)
                    drawRoundRect(Surface3, size = size, cornerRadius = radius)
                    drawRoundRect(
                        BrandGradientHorizontal,
                        size = Size(size.width * state.progress.coerceIn(0.02f, 1f), size.height),
                        cornerRadius = radius,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "This can take a minute or two. Keep the app open.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMid,
                        modifier = Modifier.weight(1f),
                    )
                    PillButton("Cancel", { maker.cancel() })
                }
            }
        }

        state.error?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFB4B4),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x33FF6B6B))
                    .padding(14.dp),
            )
        }

        // ---- the latest result ----
        val result = state.result
        if (result != null && !state.working) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Surface1)
                    .border(1.dp, Pink.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(16.dp),
            ) {
                Text("Your mashup is ready", style = MaterialTheme.typography.labelMedium, color = Pink)
                Text(result.name, style = MaterialTheme.typography.titleLarge, color = TextHi, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatDuration(result.entry.durationMs)} · ${state.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                )
                Text(
                    "Saved with your music, in the $APP_NAME folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Play", {
                        app.player.play(listOf(result.entry))
                        ui.nowPlayingOpen = true
                    }, icon = Icons.Rounded.PlayArrow, filled = true)
                    PillButton("Try another version", { maker.create(variation = true) }, icon = Icons.Rounded.Refresh)
                }
            }
        }

        // ---- everything made so far ----
        if (data.autoMashups.isNotEmpty()) {
            SectionTitle("Your auto mashups", Modifier.padding(horizontal = 0.dp))
            data.autoMashups.forEach { mashup ->
                val current = player.current?.uri == mashup.entry.uri
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Surface1)
                        .border(1.dp, if (current) Pink.copy(alpha = 0.6f) else Outline, RoundedCornerShape(16.dp))
                        .clickable { app.player.play(listOf(mashup.entry)) }
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(BrandGradient),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            mashup.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (current) Pink else TextHi,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${mashup.sources.size} songs · ${formatDuration(mashup.entry.durationMs)} · ${mashup.tempo.roundToInt()} BPM",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMid,
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = { ui.openMenu(mashup.entry) }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Options for ${mashup.name}", tint = TextMid)
                    }
                    IconButton(onClick = { deleting = mashup }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete ${mashup.name}", tint = TextLow)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }

    if (picking) {
        SongPickerSheet(
            title = "Songs to mash up",
            multiSelect = true,
            onDismiss = { picking = false },
            onPicked = { maker.add(it) },
        )
    }
    deleting?.let { mashup ->
        ConfirmDialog(
            title = "Delete \"${mashup.name}\"?",
            message = "The mashup file is removed from your phone. The original songs are not touched.",
            confirm = "Delete",
            onDismiss = { deleting = null },
            onConfirm = {
                maker.delete(mashup)
                deleting = null
            },
        )
    }
}
