package com.noexcusehere.mashwave.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.automash.AutoMashupMaker
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.data.countLabel
import com.noexcusehere.mashwave.data.describeUri
import com.noexcusehere.mashwave.data.formatDuration
import com.noexcusehere.mashwave.playback.TrackOption
import com.noexcusehere.mashwave.ui.components.Artwork
import com.noexcusehere.mashwave.ui.components.Chip
import com.noexcusehere.mashwave.ui.components.PillButton
import com.noexcusehere.mashwave.ui.components.SheetAction
import com.noexcusehere.mashwave.ui.components.TextPromptDialog
import com.noexcusehere.mashwave.ui.components.TrackRow
import com.noexcusehere.mashwave.ui.components.rowSubtitle
import com.noexcusehere.mashwave.ui.theme.BrandGradient
import com.noexcusehere.mashwave.ui.theme.DeckA
import com.noexcusehere.mashwave.ui.theme.DeckB
import com.noexcusehere.mashwave.ui.theme.Outline
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface1
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.Surface3
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid
import com.noexcusehere.mashwave.ui.theme.Violet
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun MwSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1,
        contentColor = TextHi,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Surface3) },
    ) {
        content()
        Spacer(Modifier.height(12.dp))
    }
}

/** Switches to one of the bottom-bar tabs, keeping each tab's own history. */
fun NavHostController.openTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

// ---- "..." menu for a song or video ------------------------------------------------------

@Composable
fun TrackMenuSheet(entry: MediaEntry, playlistId: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val ui = LocalUi.current
    val nav = LocalNav.current
    val data by app.store.data.collectAsStateWithLifecycle()
    val liked = data.favorites.any { it.uri == entry.uri }
    var showDetails by remember { mutableStateOf(false) }

    MwSheet(onDismiss) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(entry, Modifier.size(54.dp), RoundedCornerShape(10.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(rowSubtitle(entry), style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1)
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = Outline.copy(alpha = 0.5f))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetAction(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next", {
                app.player.playNext(entry)
                app.say("Playing next")
                onDismiss()
            })
            SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", {
                app.player.addToQueue(listOf(entry))
                app.say("Added to queue")
                onDismiss()
            })
            SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", {
                ui.addToPlaylist = listOf(entry)
                onDismiss()
            })
            SheetAction(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (liked) "Remove from Liked" else "Add to Liked",
                {
                    app.store.toggleFavorite(entry)
                    onDismiss()
                },
                tint = if (liked) Pink else TextHi,
            )
            if (!entry.isStream) {
                SheetAction(Icons.Rounded.AutoAwesome, "Add to Auto Mashup", {
                    app.autoMashup.add(listOf(entry))
                    val count = app.autoMashup.state.value.songs.size
                    app.say("Auto Mashup: $count of ${AutoMashupMaker.MAX_SONGS} songs chosen")
                    onDismiss()
                }, tint = Pink)
                SheetAction(Icons.Rounded.GraphicEq, "Manual mashup: load on Deck A", {
                    app.mashup.load(0, entry)
                    ui.nowPlayingOpen = false
                    ui.mashupMode = 1
                    nav.openTab(Routes.MASHUP)
                    onDismiss()
                }, tint = DeckA)
                SheetAction(Icons.Rounded.GraphicEq, "Manual mashup: load on Deck B", {
                    app.mashup.load(1, entry)
                    ui.nowPlayingOpen = false
                    ui.mashupMode = 1
                    nav.openTab(Routes.MASHUP)
                    onDismiss()
                }, tint = DeckB)
            }
            if (!entry.isVideo && !entry.isStream && entry.album.isNotBlank()) {
                SheetAction(Icons.Rounded.Album, "Go to album", {
                    ui.nowPlayingOpen = false
                    val key = if (entry.albumId > 0) "id:${entry.albumId}" else "name:${entry.album}"
                    nav.navigate(Routes.collection("album", key))
                    onDismiss()
                })
            }
            if (!entry.isVideo && !entry.isStream && entry.artist.isNotBlank()) {
                SheetAction(Icons.Rounded.Person, "Go to artist", {
                    ui.nowPlayingOpen = false
                    nav.navigate(Routes.collection("artist", entry.artist))
                    onDismiss()
                })
            }
            if (playlistId != null) {
                SheetAction(Icons.Rounded.RemoveCircleOutline, "Remove from this playlist", {
                    app.store.removeFromPlaylist(playlistId, entry.uri)
                    onDismiss()
                })
            }
            SheetAction(Icons.Rounded.Share, "Share", {
                val intent = if (entry.isStream) {
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, entry.uri)
                } else {
                    Intent(Intent.ACTION_SEND)
                        .setType(if (entry.isVideo) "video/*" else "audio/*")
                        .putExtra(Intent.EXTRA_STREAM, Uri.parse(entry.uri))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }
                onDismiss()
            })
            SheetAction(Icons.Rounded.Info, "Details", { showDetails = true })
        }
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            containerColor = Surface2,
            titleContentColor = TextHi,
            textContentColor = TextMid,
            title = { Text(entry.title, style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (entry.artist.isNotBlank()) Text("Artist: ${entry.artist}")
                    if (entry.album.isNotBlank()) Text("Album: ${entry.album}")
                    if (entry.durationMs > 0) Text("Length: ${formatDuration(entry.durationMs)}")
                    if (entry.width > 0) Text("Resolution: ${entry.width} × ${entry.height}")
                    if (entry.size > 0) Text("Size: %.1f MB".format(entry.size / 1_048_576.0))
                    if (entry.folder.isNotBlank()) Text("Folder: ${entry.folder}")
                    if (entry.isStream) Text(entry.uri)
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Close", color = Pink) } },
        )
    }
}

// ---- choose a playlist -------------------------------------------------------------------

@Composable
fun AddToPlaylistSheet(entries: List<MediaEntry>, onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    val data by app.store.data.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    MwSheet(onDismiss) {
        Text(
            "Add to playlist",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
        )
        SheetAction(Icons.Rounded.Add, "New playlist", { creating = true }, tint = Pink)
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(data.playlists, key = { it.id }) { playlist ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val added = app.store.addToPlaylist(playlist.id, entries)
                            app.say(if (added == 0) "Already in ${playlist.name}" else "Added to ${playlist.name}")
                            onDismiss()
                        }
                        .padding(horizontal = 22.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(playlist.items.firstOrNull(), Modifier.size(46.dp), RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(countLabel(playlist.items), style = MaterialTheme.typography.bodySmall, color = TextMid)
                    }
                }
            }
        }
    }

    if (creating) {
        TextPromptDialog(
            title = "New playlist",
            label = "Playlist name",
            confirm = "Create",
            onDismiss = { creating = false },
            onConfirm = { name ->
                val playlist = app.store.createPlaylist(name, entries)
                app.say("Added to ${playlist.name}")
                creating = false
                onDismiss()
            },
        )
    }
}

// ---- play queue --------------------------------------------------------------------------

@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    val state by app.player.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = max(0, state.index - 1))

    MwSheet(onDismiss) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Queue", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${state.queue.size} items · hold one to play it next",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                )
            }
            TextButton(onClick = {
                app.player.clearQueue()
                onDismiss()
            }) { Text("Clear", color = TextMid) }
        }
        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 520.dp)) {
            itemsIndexed(state.queue) { index, entry ->
                TrackRow(
                    entry = entry,
                    isCurrent = index == state.index,
                    isPlaying = state.isPlaying,
                    onClick = { app.player.skipTo(index) },
                    onLongClick = {
                        if (index != state.index) {
                            val target = if (index < state.index) state.index else state.index + 1
                            app.player.move(index, target.coerceAtMost(state.queue.lastIndex))
                            app.say("Will play next")
                        }
                    },
                    trailing = {
                        IconButton(onClick = { app.player.removeAt(index) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Remove from queue", tint = TextLow)
                        }
                    },
                )
            }
        }
    }
}

// ---- pick songs (for playlists and mashup decks) -----------------------------------------

@Composable
fun SongPickerSheet(
    title: String,
    multiSelect: Boolean,
    onDismiss: () -> Unit,
    onPicked: (List<MediaEntry>) -> Unit,
) {
    val app = LocalContext.current.app
    val songs by app.library.songs.collectAsStateWithLifecycle()
    val videos by app.library.videos.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<MediaEntry>() }
    val all = remember(songs, videos) { songs + videos }
    val shown = remember(all, query) {
        val q = query.trim()
        if (q.isEmpty()) all else all.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
    }

    MwSheet(onDismiss) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (multiSelect) {
                PillButton(
                    text = if (selected.isEmpty()) "Done" else "Add ${selected.size}",
                    onClick = {
                        onPicked(selected.toList())
                        onDismiss()
                    },
                    filled = selected.isNotEmpty(),
                )
            }
        }
        TextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search your library", color = TextLow) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = TextMid) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Surface2,
                unfocusedContainerColor = Surface2,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Pink,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        if (shown.isEmpty()) {
            Text(
                if (all.isEmpty()) "No music on this phone yet." else "Nothing matches \"$query\".",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMid,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 28.dp),
            )
        }
        LazyColumn(Modifier.height(440.dp)) {
            items(shown, key = { it.uri }) { entry ->
                val isSelected = multiSelect && selected.any { it.uri == entry.uri }
                TrackRow(
                    entry = entry,
                    onClick = {
                        if (multiSelect) {
                            if (isSelected) selected.removeAll { it.uri == entry.uri } else selected.add(entry)
                        } else {
                            onPicked(listOf(entry))
                            onDismiss()
                        }
                    },
                    trailing = {
                        if (multiSelect) {
                            Icon(
                                if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                contentDescription = if (isSelected) "Selected" else "Not selected",
                                tint = if (isSelected) Pink else TextLow,
                                modifier = Modifier.padding(end = 14.dp),
                            )
                        } else {
                            Spacer(Modifier.width(12.dp))
                        }
                    },
                )
            }
        }
    }
}

// ---- sleep timer / speed / tracks --------------------------------------------------------

@Composable
fun SleepTimerSheet(onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    val state by app.player.state.collectAsStateWithLifecycle()
    val active = state.sleepTimerEndsAt > 0
    MwSheet(onDismiss) {
        Text(
            "Sleep timer",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        Text(
            if (active) {
                val left = ((state.sleepTimerEndsAt - System.currentTimeMillis()) / 60_000.0).roundToInt().coerceAtLeast(1)
                "Music stops in about $left min"
            } else {
                "Stop the music automatically after a while"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextMid,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
        )
        FlowRow(
            Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(5, 10, 15, 30, 45, 60, 90, 120).forEach { minutes ->
                Chip("$minutes min", selected = false, onClick = {
                    app.player.setSleepTimer(minutes)
                    app.say("Sleep timer set for $minutes min")
                    onDismiss()
                })
            }
            if (active) {
                Chip("Turn off", selected = true, onClick = {
                    app.player.setSleepTimer(0)
                    onDismiss()
                })
            }
        }
    }
}

@Composable
fun SpeedSheet(onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    val state by app.player.state.collectAsStateWithLifecycle()
    MwSheet(onDismiss) {
        Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Playback speed", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("%.2f×".format(state.speed), style = MaterialTheme.typography.titleLarge, color = Pink)
        }
        Slider(
            value = state.speed,
            onValueChange = { app.player.setSpeed((it * 20).roundToInt() / 20f) },
            valueRange = 0.5f..2f,
            colors = SliderDefaults.colors(thumbColor = Pink, activeTrackColor = Violet, inactiveTrackColor = Surface3),
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        FlowRow(
            Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                Chip(
                    if (speed == 1f) "Normal" else "$speed×",
                    selected = kotlin.math.abs(state.speed - speed) < 0.01f,
                    onClick = { app.player.setSpeed(speed) },
                )
            }
        }
    }
}

/** Audio-track and subtitle chooser for the current file. */
@Composable
fun TracksSheet(onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    // Re-read the tracks whenever the player reports a change.
    val state by app.player.state.collectAsStateWithLifecycle()
    var version by remember { mutableStateOf(0) }
    val audio = remember(state, version) { app.player.tracks(C.TRACK_TYPE_AUDIO) }
    val text = remember(state, version) { app.player.tracks(C.TRACK_TYPE_TEXT) }

    MwSheet(onDismiss) {
        Column(
            Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Audio", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
            if (audio.isEmpty()) {
                Text("No audio tracks", color = TextMid, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
            }
            audio.forEach { option ->
                TrackOptionRow(option.label, option.selected) {
                    app.player.selectTrack(C.TRACK_TYPE_AUDIO, option)
                    version++
                }
            }
            Text("Subtitles", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 6.dp))
            TrackOptionRow("Off", text.none { it.selected }) {
                app.player.selectTrack(C.TRACK_TYPE_TEXT, null)
                version++
            }
            text.forEach { option: TrackOption ->
                TrackOptionRow(option.label, option.selected) {
                    app.player.selectTrack(C.TRACK_TYPE_TEXT, option)
                    version++
                }
            }
        }
    }
}

@Composable
private fun TrackOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) Pink else TextHi,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = Pink)
    }
}

// ---- opening things that aren't in the library -------------------------------------------

/** Returns a function that opens the system file picker and plays whatever is chosen. */
@Composable
fun rememberOpenFiles(): () -> Unit {
    val context = LocalContext.current
    val app = context.app
    val ui = LocalUi.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val entries = uris.map { uri ->
                // Keep access after a restart so the file can live in playlists.
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                describeUri(context, uri)
            }
            ui.play(app.player, entries, 0)
            if (!entries.first().isVideo) ui.nowPlayingOpen = true
        }
    }
    return { runCatching { launcher.launch(arrayOf("audio/*", "video/*", "application/ogg", "application/x-matroska")) } }
}

@Composable
fun OpenStreamDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val ui = LocalUi.current
    TextPromptDialog(
        title = "Open network stream",
        label = "Address (http, https or rtsp)",
        confirm = "Play",
        capitalize = false,
        onDismiss = onDismiss,
        onConfirm = { typed ->
            val address = if (typed.contains("://")) typed else "https://$typed"
            val entry = describeUri(context, Uri.parse(address))
            ui.play(app.player, listOf(entry), 0)
            if (!entry.isVideo) ui.nowPlayingOpen = true
            onDismiss()
        },
    )
}

/** Small round coloured badge, used for deck letters. */
@Composable
fun DeckBadge(letter: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = MaterialTheme.typography.labelLarge, color = Color.Black)
    }
}
