package com.noexcusehere.mashwave.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noexcusehere.mashwave.desktop.Desk
import com.noexcusehere.mashwave.desktop.DeskMashup
import com.noexcusehere.mashwave.desktop.MadeMashup

/** "Pick songs, get a mashup": songs to choose from on the left, the mashup being put together on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MashupPanel(desk: Desk) {
    val mashup = desk.mashup
    val songs = desk.library.songs
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(songs, query) { if (query.isBlank()) songs else songs.filter { songMatches(it, query) } }
    val chosen = mashup.songs
    val full = chosen.size >= DeskMashup.MOST_SONGS

    Row(Modifier.fillMaxSize()) {
        // ---- the songs to pick from
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pick 2 to ${DeskMashup.MOST_SONGS} songs", style = MaterialTheme.typography.titleMedium, color = TextHi, modifier = Modifier.weight(1f))
                Field(query, { query = it }, "Find a song", Modifier.width(240.dp), icon = Icons.Rounded.Search)
            }
            Spacer(Modifier.height(8.dp))
            if (songs.isEmpty()) {
                Note(Icons.Rounded.MusicNote, "No music to mix yet", "Once your songs show under Songs, they can be picked here.")
            } else {
                val state = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 20.dp, end = 12.dp)) {
                        items(shown, key = { it.path }) { song ->
                            val picked = chosen.any { it.path == song.path }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 1.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (picked) Violet.copy(alpha = 0.22f) else Color.Transparent)
                                    .dimmedUnless(picked || (!full && !mashup.working))
                                    .clickable(enabled = !mashup.working && (picked || !full)) { if (picked) mashup.remove(song.path) else mashup.add(song) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                    if (picked) Icon(Icons.Rounded.CheckCircle, contentDescription = "Picked", tint = Violet, modifier = Modifier.size(20.dp))
                                    else Box(Modifier.size(18.dp).clip(RoundedCornerShape(50)).border(1.5.dp, Outline, RoundedCornerShape(50)))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, style = MaterialTheme.typography.bodyLarge, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val under = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString("  ·  ")
                                    if (under.isNotEmpty()) Text(under, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (song.durationMs > 0) Text(clock(song.durationMs), style = MaterialTheme.typography.bodySmall, color = TextLow)
                            }
                        }
                    }
                    ListBar(state)
                }
            }
        }
        Spacer(Modifier.width(18.dp))

        // ---- the mashup itself
        Column(
            Modifier.width(400.dp).fillMaxHeight().padding(bottom = 16.dp).clip(RoundedCornerShape(14.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(14.dp))
                .verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text("Your mashup", style = MaterialTheme.typography.titleLarge, color = TextHi)
            Text(
                "The app finds each song's beat and key, plans the mix the way a DJ would, and saves one new song. Songs with a clear, steady beat work best.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMid,
            )
            Spacer(Modifier.height(12.dp))

            if (chosen.isEmpty()) {
                Text("Nothing picked yet. Click songs on the left.", style = MaterialTheme.typography.bodyMedium, color = TextLow)
            }
            chosen.forEachIndexed { index, song ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", style = MaterialTheme.typography.bodyMedium, color = TextLow, modifier = Modifier.width(20.dp))
                    Text(song.title, style = MaterialTheme.typography.bodyLarge, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    RoundIcon(Icons.Rounded.Close, "Take it out", { mashup.remove(song.path) }, size = 26.dp, tint = TextMid, enabled = !mashup.working)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("How long", style = MaterialTheme.typography.titleSmall, color = TextHi)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Auto", mashup.lengthSeconds == 0, { if (!mashup.working) mashup.lengthSeconds = 0 })
                for (minutes in 1..7) {
                    Chip("$minutes min", mashup.lengthSeconds == minutes * 60, { if (!mashup.working) mashup.lengthSeconds = minutes * 60 })
                }
            }

            Spacer(Modifier.height(14.dp))
            if (mashup.working) {
                Text(mashup.step, style = MaterialTheme.typography.bodyMedium, color = TextHi, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Bar(mashup.progress, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${(mashup.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = TextLow, modifier = Modifier.weight(1f))
                    Quiet("Stop", { mashup.cancel() }, icon = Icons.Rounded.Close)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("Create mashup", { mashup.create() }, icon = Icons.Rounded.AutoAwesome, enabled = chosen.size >= 2)
                    if (chosen.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Quiet("Start over", { mashup.clear() })
                    }
                }
                if (chosen.size == 1) {
                    Text("Pick at least one more song.", style = MaterialTheme.typography.bodySmall, color = TextLow, modifier = Modifier.padding(top = 6.dp))
                }
            }
            mashup.error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Amber)
            }

            val latest = mashup.result
            if (latest != null && !mashup.working) {
                Spacer(Modifier.height(16.dp))
                MadeCard(desk, latest, fresh = true)
            }
            val earlier = mashup.made.filter { it.path != latest?.path }
            if (earlier.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Made earlier", style = MaterialTheme.typography.titleSmall, color = TextHi)
                Spacer(Modifier.height(6.dp))
                for (one in earlier) {
                    MadeCard(desk, one, fresh = false)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** One finished mashup: play it, see how it was mixed, make another version, or delete it. */
@Composable
private fun MadeCard(desk: Desk, made: MadeMashup, fresh: Boolean) {
    val mashup = desk.mashup
    var open by remember(made.path) { mutableStateOf(false) }
    var sure by remember(made.path) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2).border(1.dp, if (fresh) Violet else Outline, RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        if (fresh) Text("Done - it has been added to your music", style = MaterialTheme.typography.labelMedium, color = Pink)
        Text(made.name, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (made.summary.isNotEmpty()) Text(made.summary, style = MaterialTheme.typography.bodySmall, color = TextMid)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("Play", { desk.playSongs(listOf(mashup.fileOf(made)), 0) }, icon = Icons.Rounded.PlayArrow)
            Spacer(Modifier.width(6.dp))
            if (fresh) {
                Quiet("Try another version", { mashup.create(variation = true) }, icon = Icons.Rounded.Refresh, enabled = mashup.songs.size >= 2)
            }
            Spacer(Modifier.weight(1f))
            RoundIcon(Icons.Rounded.Delete, if (sure) "Click again to delete it" else "Delete", { if (sure) mashup.delete(made) else sure = true }, size = 30.dp, tint = if (sure) Amber else TextMid)
        }
        if (made.story.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (open) "Hide how it was mixed" else "See how it was mixed",
                style = MaterialTheme.typography.labelLarge,
                color = Violet,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { open = !open }.padding(vertical = 4.dp, horizontal = 2.dp),
            )
            if (open) {
                for (line in made.story) {
                    Text(line, style = MaterialTheme.typography.bodySmall, color = TextMid, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}
