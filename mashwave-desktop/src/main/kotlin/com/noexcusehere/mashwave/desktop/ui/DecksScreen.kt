package com.noexcusehere.mashwave.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.noexcusehere.mashwave.desktop.Deck
import com.noexcusehere.mashwave.desktop.Desk
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Two decks for mixing two songs by hand: pick a song for each, set its speed, pitch and
 * loudness, slide between them, and keep the mix to open again.
 */
@Composable
fun DecksPanel(desk: Desk) {
    val decks = desk.decks
    val songs = desk.library.songs
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(songs, query) { if (query.isBlank()) songs else songs.filter { songMatches(it, query) } }
    var name by remember(decks.loaded?.id) { mutableStateOf(decks.loaded?.name.orEmpty()) }

    Row(Modifier.fillMaxSize()) {
        // ---- songs: A or B puts one on a deck ----
        Column(Modifier.width(330.dp).fillMaxHeight()) {
            Field(query, { query = it }, "Find a song", Modifier.fillMaxWidth(), icon = Icons.Rounded.Search)
            Spacer(Modifier.height(4.dp))
            Text("Press A or B to put a song on that deck.", style = MaterialTheme.typography.bodySmall, color = TextLow, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            val state = rememberLazyListState()
            Box(Modifier.weight(1f)) {
                LazyColumn(state = state, contentPadding = PaddingValues(bottom = 16.dp, end = 10.dp)) {
                    items(shown, key = { it.path }) { song ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                                Text(song.title, style = MaterialTheme.typography.bodyMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (song.artist.isNotBlank()) Text(song.artist, style = MaterialTheme.typography.labelSmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            listOf("A", "B").forEachIndexed { i, letter ->
                                val on = decks.decks[i].song?.path == song.path
                                Box(
                                    Modifier.padding(start = 6.dp).size(30.dp).clip(CircleShape).background(if (on) Violet else Surface3).clickable { decks.load(i, song) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(letter, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else TextMid)
                                }
                            }
                        }
                    }
                }
                ListBar(state)
            }
        }
        Spacer(Modifier.width(16.dp))

        // ---- the decks ----
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DeckCard(desk, 0, "Deck A", Modifier.weight(1f))
                DeckCard(desk, 1, "Deck B", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(vertical = 12.dp)) {
                SliderRow("Deck A  -  both  -  Deck B", decks.crossfade, "", { decks.changeCrossfade(if (kotlin.math.abs(it - 0.5f) < 0.03f) 0.5f else it) }, mark = 0.5f)
                Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("Play both", { decks.playBoth() }, icon = Icons.Rounded.PlayArrow, enabled = decks.ready)
                    Quiet("Pause both", { decks.pauseBoth() }, icon = Icons.Rounded.Pause, enabled = decks.anyPlaying)
                    Quiet("Start over", { decks.restartBoth() }, icon = Icons.Rounded.Replay, enabled = decks.ready)
                }
                Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(name, { name = it }, "A name for this mix", Modifier.width(260.dp))
                    Spacer(Modifier.width(8.dp))
                    Quiet("Keep this mix", {
                        if (decks.save(name) != null) desk.say("Kept. It is under \"Mixes kept\" below.")
                    }, icon = Icons.Rounded.Save, enabled = decks.ready)
                }
            }
            if (decks.mixes.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("Mixes kept", style = MaterialTheme.typography.titleSmall, color = TextHi, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                decks.mixes.forEach { mix ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(if (decks.loaded?.id == mix.id) Violet.copy(alpha = 0.25f) else Surface1)
                            .clickable { decks.open(mix, autoPlay = false) }.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(mix.name, style = MaterialTheme.typography.bodyMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${mix.a.title}  +  ${mix.b.title}", style = MaterialTheme.typography.labelSmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        RoundIcon(Icons.Rounded.PlayArrow, "Open it and play", { decks.open(mix, autoPlay = true) }, size = 32.dp)
                        RoundIcon(Icons.Rounded.Delete, "Forget this mix (the songs stay)", { decks.delete(mix) }, size = 32.dp, tint = TextLow)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckCard(desk: Desk, i: Int, title: String, modifier: Modifier) {
    val decks = desk.decks
    val deck: Deck = decks.decks[i]
    val song = deck.song
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Surface1).border(1.dp, if (deck.playing) Pink else Outline, RoundedCornerShape(16.dp)).padding(vertical = 12.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = Pink)
            Spacer(Modifier.weight(1f))
            if (song != null) RoundIcon(Icons.Rounded.Close, "Take the song off this deck", { decks.clear(i) }, size = 26.dp, tint = TextLow)
        }
        if (song == null) {
            Text(
                "Empty. Press ${if (i == 0) "A" else "B"} beside a song in the list.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextLow,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            )
            return
        }
        Text(song.title, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
        Text(song.artist.ifBlank { " " }, style = MaterialTheme.typography.labelSmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))

        val length = deck.durationMs.coerceAtLeast(1)
        SliderRow("", (deck.positionMs.toFloat() / length).coerceIn(0f, 1f), clock(deck.positionMs) + " / " + clock(deck.durationMs), { decks.seekTo(i, (it * length).roundToLong()) })
        Row(Modifier.padding(horizontal = 14.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(BrandGradient).clickable { decks.toggle(i) }, contentAlignment = Alignment.Center) {
                Icon(if (deck.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (deck.playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Tip("Slide this song a little earlier, to line up the beats") { Chip("- nudge", false, { decks.nudge(i, -40) }) }
            Tip("Slide this song a little later, to line up the beats") { Chip("nudge +", false, { decks.nudge(i, 40) }) }
            Tip("\"Start over\" will begin this deck from where it is now (${clock(deck.cueMs)} at the moment)") { Chip("Start here", deck.cueMs > 0, { decks.setCue(i) }) }
        }

        // The beat.
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (deck.analysing) {
                CircularProgressIndicator(color = Pink, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text("Finding the beat...", style = MaterialTheme.typography.labelMedium, color = TextMid)
            } else if (deck.bpm > 0f) {
                Text("${deck.heardBpm.roundToInt()} beats a minute", style = MaterialTheme.typography.labelLarge, color = TextHi)
                if (deck.tempo != 1f) Text("  (${deck.bpm.roundToInt()} as recorded)", style = MaterialTheme.typography.labelSmall, color = TextLow)
            } else {
                Text("Beat not known - tap along", style = MaterialTheme.typography.labelMedium, color = TextMid)
            }
            Spacer(Modifier.weight(1f))
            Tip("Press in time with the beat a few times") { Chip("Tap", false, { decks.tap(i) }) }
        }
        Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Quiet("Match the other deck", { if (!decks.matchTempo(i)) desk.say("The beat of both songs has to be known first. Tap along to set it.") }, enabled = decks.decks[1 - i].song != null)
            Quiet("Reset", { decks.resetDeck(i) })
        }

        SliderRow("Speed", (deck.tempo - 0.5f) / 1.5f, "${(deck.tempo * 100).roundToInt()}%", {
            val next = 0.5f + it * 1.5f
            decks.setTempo(i, if (kotlin.math.abs(next - 1f) < 0.02f) 1f else next)
        }, mark = 1f / 3f)
        // Pitch takes effect when the slider is let go: a playing deck picks it up with a short break.
        var sliding by remember(song.path) { mutableFloatStateOf(Float.NaN) }
        val pitch = if (sliding.isNaN()) deck.pitch else sliding
        SliderRow("Pitch", (pitch + 12f) / 24f, (if (pitch > 0) "+" else "") + pitch.roundToInt(), { sliding = (it * 24f - 12f).roundToInt().toFloat() }, onDone = {
            if (!sliding.isNaN()) decks.setPitch(i, sliding)
            sliding = Float.NaN
        }, mark = 0.5f)
        SliderRow("Loudness", deck.volume, "${(deck.volume * 100).roundToInt()}%", { decks.setVolume(i, it) })
    }
}
