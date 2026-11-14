package com.noexcusehere.mashwave.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.Mashup
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.data.formatDuration
import com.noexcusehere.mashwave.playback.DeckState
import com.noexcusehere.mashwave.ui.DeckBadge
import com.noexcusehere.mashwave.ui.LocalUi
import com.noexcusehere.mashwave.ui.SongPickerSheet
import com.noexcusehere.mashwave.ui.components.Artwork
import com.noexcusehere.mashwave.ui.components.Chip
import com.noexcusehere.mashwave.ui.components.ConfirmDialog
import com.noexcusehere.mashwave.ui.components.HeaderIcon
import com.noexcusehere.mashwave.ui.components.MwSlider
import com.noexcusehere.mashwave.ui.components.PillButton
import com.noexcusehere.mashwave.ui.components.ScreenHeader
import com.noexcusehere.mashwave.ui.components.SectionTitle
import com.noexcusehere.mashwave.ui.components.SeekBar
import com.noexcusehere.mashwave.ui.components.TextPromptDialog
import com.noexcusehere.mashwave.ui.theme.DeckA
import com.noexcusehere.mashwave.ui.theme.DeckB
import com.noexcusehere.mashwave.ui.theme.Ink
import com.noexcusehere.mashwave.ui.theme.Outline
import com.noexcusehere.mashwave.ui.theme.Surface1
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.Surface3
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val deckColors = listOf(DeckA, DeckB)
private val deckLetters = listOf("A", "B")

/** A deck's position, polled while the screen is visible. */
@Composable
private fun rememberDeckPosition(index: Int): State<Long> {
    val engine = LocalContext.current.app.mashup
    val owner = LocalLifecycleOwner.current
    return produceState(initialValue = engine.position(index), index, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = engine.position(index)
                delay(200)
            }
        }
    }
}

@Composable
fun MashupScreen() {
    val app = LocalContext.current.app
    val ui = LocalUi.current
    val engine = app.mashup
    val state by engine.state.collectAsStateWithLifecycle()
    val data by app.store.data.collectAsStateWithLifecycle()
    var pickingDeck by remember { mutableIntStateOf(-1) }
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Mashup?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DeckA.copy(alpha = 0.16f), Ink, DeckB.copy(alpha = 0.10f))))
            .statusBarsPadding(),
    ) {
        ScreenHeader("Mashup") {
            if (ui.mashupMode == 1) {
                HeaderIcon(Icons.Rounded.Save, "Save this mashup", {
                    if (state.ready) saving = true else app.say("Load a song on both decks first")
                }, tint = if (state.ready) TextHi else TextLow)
            }
        }
        Row(
            Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("Auto", selected = ui.mashupMode == 0, onClick = { ui.mashupMode = 0 })
            Chip("Manual decks", selected = ui.mashupMode == 1, onClick = { ui.mashupMode = 1 })
        }

        if (ui.mashupMode == 0) {
            AutoMashupSection()
        } else Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                state.loadedName?.let { "Mix: $it" } ?: "Layer two songs, match their tempo, blend and save.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMid,
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            DeckCard(0, state.decks[0], otherHasBpm = state.decks[1].bpm > 0f, onPick = { pickingDeck = 0 })

            // ---- mixer ----
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Surface1)
                    .border(1.dp, Outline, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeckBadge("A", DeckA)
                    MwSlider(
                        value = state.crossfade,
                        onValueChange = { engine.setCrossfade(if (abs(it - 0.5f) < 0.03f) 0.5f else it) },
                        activeBrush = Brush.horizontalGradient(listOf(DeckA, DeckB)),
                        mark = 0.5f,
                        fillFromMark = true,
                        thumbRadius = 10.dp,
                        trackHeight = 6.dp,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    DeckBadge("B", DeckB)
                }
                Text(
                    when {
                        abs(state.crossfade - 0.5f) < 0.01f -> "Crossfader centred - both songs at full volume"
                        state.crossfade < 0.5f -> "Leaning towards Deck A"
                        else -> "Leaning towards Deck B"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PillButton("Restart", { engine.restartBoth() }, icon = Icons.Rounded.Replay, enabled = state.ready)
                    Box(
                        Modifier
                            .size(66.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(DeckA, DeckB)))
                            .clickable {
                                when {
                                    state.anyPlaying -> engine.pauseBoth()
                                    state.decks.any { it.entry != null } -> engine.playBoth()
                                    else -> app.say("Choose a song for each deck first")
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (state.anyPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.anyPlaying) "Pause both decks" else "Play both decks",
                            tint = Color.Black,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    PillButton("Swap", {
                        val a = state.decks[0].entry
                        val b = state.decks[1].entry
                        engine.pauseBoth()
                        if (b != null) engine.load(0, b, state.decks[1].bpm) else engine.clear(0)
                        if (a != null) engine.load(1, a, state.decks[0].bpm) else engine.clear(1)
                    }, icon = Icons.Rounded.SwapHoriz, enabled = state.decks.any { it.entry != null })
                }
            }

            DeckCard(1, state.decks[1], otherHasBpm = state.decks[0].bpm > 0f, onPick = { pickingDeck = 1 })

            if (data.mashups.isNotEmpty()) {
                SectionTitle("Saved mashups", Modifier.padding(horizontal = 0.dp))
                data.mashups.forEach { mashup ->
                    SavedMashupRow(
                        mashup = mashup,
                        isLoaded = state.loadedId == mashup.id,
                        onOpen = { engine.open(mashup, autoPlay = true) },
                        onDelete = { deleting = mashup },
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }

    if (pickingDeck >= 0) {
        val index = pickingDeck
        SongPickerSheet(
            title = "Song for Deck ${deckLetters[index]}",
            multiSelect = false,
            onDismiss = { pickingDeck = -1 },
            onPicked = { picked -> picked.firstOrNull()?.let { engine.load(index, it) } },
        )
    }
    if (saving) {
        val a = state.decks[0].entry
        val b = state.decks[1].entry
        TextPromptDialog(
            title = "Save mashup",
            label = "Name",
            confirm = "Save",
            initial = state.loadedName ?: "${a?.title ?: "A"} × ${b?.title ?: "B"}",
            onDismiss = { saving = false },
            onConfirm = { name ->
                engine.snapshot(name, state.loadedId)?.let { mashup ->
                    app.store.saveMashup(mashup)
                    engine.markSaved(mashup)
                    app.say("Mashup saved")
                }
                saving = false
            },
        )
    }
    deleting?.let { mashup ->
        ConfirmDialog(
            title = "Delete \"${mashup.name}\"?",
            message = "Only the saved mix is removed, not the songs.",
            confirm = "Delete",
            onDismiss = { deleting = null },
            onConfirm = {
                app.store.deleteMashup(mashup.id)
                deleting = null
            },
        )
    }
}

@Composable
private fun DeckCard(index: Int, deck: DeckState, otherHasBpm: Boolean, onPick: () -> Unit) {
    val app = LocalContext.current.app
    val engine = app.mashup
    val color = deckColors[index]
    val letter = deckLetters[index]
    val entry = deck.entry
    val position by rememberDeckPosition(index)
    val shape = RoundedCornerShape(20.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Surface1)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.14f), Color.Transparent)))
            .border(1.dp, color.copy(alpha = 0.4f), shape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Disc(entry, deck.playing, deck.tempo, color)
            Spacer(Modifier.width(12.dp))
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onPick)
                    .padding(vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeckBadge(letter, color, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("DECK $letter", style = MaterialTheme.typography.labelSmall, color = color)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            deck.analysing -> "finding beat…"
                            deck.bpm > 0f -> "%.1f BPM".format(deck.effectiveBpm)
                            entry != null -> "BPM unknown"
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMid,
                    )
                }
                Text(
                    entry?.title ?: "Tap to choose a song",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry?.subtitle ?: "Anything from your library",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry != null) {
                Box(
                    Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable { engine.toggle(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (deck.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (deck.playing) "Pause deck $letter" else "Play deck $letter",
                        tint = Color.Black,
                        modifier = Modifier.size(30.dp),
                    )
                }
            } else {
                IconButton(onClick = onPick) {
                    Icon(Icons.Rounded.Add, contentDescription = "Choose a song for deck $letter", tint = color)
                }
            }
        }

        if (entry == null) return@Column

        SeekBar(
            positionMs = position,
            durationMs = engine.duration(index),
            onSeek = { engine.seekTo(index, it) },
            activeBrush = SolidColor(color),
            modifier = Modifier.padding(top = 4.dp),
        )

        TuneRow(
            label = "Tempo",
            valueText = "%.2f×".format(deck.tempo),
            fraction = (deck.tempo - 0.5f).coerceIn(0f, 1f),
            color = color,
            onChange = { f ->
                val tempo = 0.5f + f
                engine.setTempo(index, if (abs(tempo - 1f) < 0.015f) 1f else (tempo * 100).roundToInt() / 100f)
            },
            onStep = { direction -> engine.setTempo(index, ((deck.tempo + direction * 0.01f) * 100).roundToInt() / 100f) },
            onReset = { engine.setTempo(index, 1f) },
        )
        TuneRow(
            label = "Pitch",
            valueText = (if (deck.pitch > 0) "+" else "") + "${deck.pitch.roundToInt()} st",
            fraction = (deck.pitch + 12f) / 24f,
            color = color,
            onChange = { f -> engine.setPitch(index, (f * 24f - 12f).roundToInt().toFloat()) },
            onStep = { direction -> engine.setPitch(index, deck.pitch + direction) },
            onReset = { engine.setPitch(index, 0f) },
        )
        TuneRow(
            label = "Volume",
            valueText = "${(deck.volume * 100).roundToInt()}%",
            fraction = deck.volume,
            color = color,
            mark = -1f,
            onChange = { f -> engine.setVolume(index, f) },
            onStep = null,
            onReset = { engine.setVolume(index, 1f) },
        )

        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallAction(Icons.Rounded.Sync, "Match tempo", enabled = deck.bpm > 0f && otherHasBpm, tint = color) {
                if (!engine.matchTempo(index)) app.say("Both decks need a BPM first - tap along to set it")
            }
            SmallAction(Icons.Rounded.TouchApp, "Tap BPM", tint = TextHi) { engine.tap(index) }
            SmallAction(
                Icons.Rounded.Flag,
                if (deck.cueMs > 0) "Start ${formatDuration(deck.cueMs)}" else "Set start",
                tint = TextHi,
            ) {
                engine.setCue(index)
                app.say("Deck $letter starts from ${formatDuration(engine.position(index))}")
            }
            SmallAction(Icons.Rounded.Close, "Unload", tint = TextMid) { engine.clear(index) }
        }
    }
}

@Composable
private fun TuneRow(
    label: String,
    valueText: String,
    fraction: Float,
    color: Color,
    mark: Float = 0.5f,
    onChange: (Float) -> Unit,
    onStep: ((Int) -> Unit)?,
    onReset: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(38.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextMid, modifier = Modifier.width(50.dp))
        if (onStep != null) {
            StepButton(Icons.Rounded.Remove, "Lower $label") { onStep(-1) }
        }
        MwSlider(
            value = fraction,
            onValueChange = onChange,
            activeBrush = SolidColor(color),
            mark = mark,
            fillFromMark = mark >= 0f,
            modifier = Modifier.weight(1f),
        )
        if (onStep != null) {
            StepButton(Icons.Rounded.Add, "Raise $label") { onStep(1) }
        }
        Text(
            valueText,
            style = MaterialTheme.typography.labelLarge,
            color = TextHi,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier
                .width(58.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "Reset $label", onClick = onReset)
                .padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Surface3)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = TextHi, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SmallAction(icon: ImageVector, text: String, enabled: Boolean = true, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = if (enabled) tint else TextLow
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

/** The cover as a spinning record; it turns faster when the tempo is raised. */
@Composable
private fun Disc(entry: MediaEntry?, playing: Boolean, tempo: Float, color: Color) {
    val rotation = remember { Animatable(0f) }
    val speed by rememberUpdatedState(tempo)
    LaunchedEffect(playing) {
        while (playing) {
            rotation.animateTo(
                targetValue = rotation.value + 360f,
                animationSpec = tween((3600 / speed.coerceIn(0.5f, 2f)).toInt(), easing = LinearEasing),
            )
            if (rotation.value >= 36_000f) rotation.snapTo(rotation.value % 360f)
        }
    }
    Box(
        Modifier
            .size(66.dp)
            .clip(CircleShape)
            .background(color)
            .padding(2.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Artwork(
            entry,
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation.value },
            CircleShape,
        )
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(Ink)
                .border(2.dp, color, CircleShape),
        )
    }
}

@Composable
private fun SavedMashupRow(mashup: Mashup, isLoaded: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .border(1.dp, if (isLoaded) DeckB.copy(alpha = 0.6f) else Outline, RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 70.dp, height = 46.dp)) {
            Artwork(
                mashup.a,
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(DeckA)
                    .padding(2.dp),
                CircleShape,
            )
            Artwork(
                mashup.b,
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(DeckB)
                    .padding(2.dp),
                CircleShape,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(mashup.name, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${mashup.a.title} × ${mashup.b.title}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onOpen) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = "Play this mashup", tint = TextHi)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Delete, contentDescription = "Delete this mashup", tint = TextLow)
        }
    }
}
