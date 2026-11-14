package com.noexcusehere.mashwave.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Shuffle
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.data.UserData
import com.noexcusehere.mashwave.data.countLabel
import com.noexcusehere.mashwave.ui.LocalNav
import com.noexcusehere.mashwave.ui.LocalUi
import com.noexcusehere.mashwave.ui.SongPickerSheet
import com.noexcusehere.mashwave.ui.components.ConfirmDialog
import com.noexcusehere.mashwave.ui.components.EmptyState
import com.noexcusehere.mashwave.ui.components.GradientPlayButton
import com.noexcusehere.mashwave.ui.components.PillButton
import com.noexcusehere.mashwave.ui.components.TextPromptDialog
import com.noexcusehere.mashwave.ui.components.TrackRow
import com.noexcusehere.mashwave.ui.components.rememberAccent
import com.noexcusehere.mashwave.ui.theme.Ink
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid

/** What a detail page shows: an album, artist, folder, playlist or one of the automatic lists. */
data class CollectionModel(
    val kind: String,
    val title: String,
    val subtitle: String,
    val entries: List<MediaEntry>,
    val playlistId: String? = null,
    val emptyMessage: String = "Nothing here yet.",
    /** A playlist of videos. */
    val watchList: Boolean = false,
)

fun resolveCollection(
    type: String,
    key: String,
    songs: List<MediaEntry>,
    videos: List<MediaEntry>,
    data: UserData,
): CollectionModel = when (type) {
    "album" -> {
        val items = if (key.startsWith("id:")) {
            val id = key.removePrefix("id:").toLongOrNull() ?: -1
            songs.filter { it.albumId == id }
        } else {
            val name = key.removePrefix("name:")
            songs.filter { it.albumId <= 0 && it.album == name }
        }.sortedWith(compareBy({ it.track }, { it.title.lowercase() }))
        val artists = items.map { it.artist }.filter { it.isNotBlank() }.distinct()
        CollectionModel(
            kind = "Album",
            title = items.firstOrNull()?.album?.ifBlank { null } ?: "Unknown album",
            subtitle = when (artists.size) {
                0 -> "Unknown artist"
                1 -> artists.first()
                else -> "Various artists"
            },
            entries = items,
        )
    }

    "artist" -> {
        val items = songs.filter { it.artist.ifBlank { "Unknown artist" } == key }
            .sortedWith(compareBy({ it.album.lowercase() }, { it.track }, { it.title.lowercase() }))
        CollectionModel("Artist", key, "", items)
    }

    "folder" -> {
        val items = (songs + videos).filter { it.folder == key }.sortedBy { it.title.lowercase() }
        CollectionModel("Folder", key.trimEnd('/').substringAfterLast('/').ifBlank { "Internal storage" }, key, items)
    }

    "playlist" -> {
        val playlist = data.playlists.firstOrNull { it.id == key }
        val watchList = playlist?.video == true
        CollectionModel(
            kind = if (watchList) "Watch list" else "Playlist",
            title = playlist?.name ?: "Playlist",
            subtitle = "",
            entries = playlist?.items ?: emptyList(),
            playlistId = playlist?.id,
            emptyMessage = if (watchList) {
                "This watch list is empty. Tap \"Add videos\" to fill it."
            } else {
                "This playlist is empty. Tap \"Add songs\" to fill it."
            },
            watchList = watchList,
        )
    }

    "liked" -> CollectionModel(
        "Playlist", "Liked", "", data.favorites,
        emptyMessage = "Tap the heart on anything you love and it lands here.",
    )

    "recent" -> CollectionModel(
        "History", "Recently played", "", data.history,
        emptyMessage = "Things you play show up here.",
    )

    else -> {
        val known = HashMap<String, MediaEntry>()
        (data.history + data.favorites + videos + songs).forEach { known[it.uri] = it }
        val items = data.playCounts.entries.sortedByDescending { it.value }.take(50).mapNotNull { known[it.key] }
        CollectionModel("Your top 50", "Most played", "", items, emptyMessage = "Play some music and your favourites rise to the top.")
    }
}

@Composable
fun CollectionScreen(type: String, key: String) {
    val app = LocalContext.current.app
    val ui = LocalUi.current
    val nav = LocalNav.current
    val songs by app.library.songs.collectAsStateWithLifecycle()
    val videos by app.library.videos.collectAsStateWithLifecycle()
    val data by app.store.data.collectAsStateWithLifecycle()
    val player by app.player.state.collectAsStateWithLifecycle()
    val model = remember(type, key, songs, videos, data) { resolveCollection(type, key, songs, videos, data) }
    val accent = rememberAccent(model.entries.firstOrNull())
    var editing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val playlistId = model.playlistId

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.55f), Ink)))
                    .statusBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = TextHi)
                    }
                    Spacer(Modifier.weight(1f))
                    if (playlistId != null) {
                        IconButton(onClick = { renaming = true }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Rename playlist", tint = TextHi)
                        }
                        IconButton(onClick = { deleting = true }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete playlist", tint = TextHi)
                        }
                    }
                    if (type == "recent" && model.entries.isNotEmpty()) {
                        IconButton(onClick = { deleting = true }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Clear history", tint = TextHi)
                        }
                    }
                }
                PlaylistCover(
                    model.entries,
                    Modifier
                        .padding(top = 4.dp)
                        .size(188.dp)
                        .shadow(24.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                    corner = 14.dp,
                )
                Spacer(Modifier.height(16.dp))
                Text(model.kind.uppercase(), style = MaterialTheme.typography.labelSmall, color = TextMid)
                Text(
                    model.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextHi,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                )
                val line = listOf(model.subtitle.takeIf { it.isNotBlank() && type != "folder" }, countLabel(model.entries))
                    .filterNotNull().joinToString(" · ")
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (playlistId != null) {
                        PillButton(if (model.watchList) "Add videos" else "Add songs", { picking = true }, icon = Icons.Rounded.Add)
                        if (model.entries.size > 1) {
                            PillButton(if (editing) "Done" else "Reorder", { editing = !editing }, filled = editing)
                        }
                    } else if (model.entries.isNotEmpty()) {
                        PillButton("Add to playlist", { ui.addToPlaylist = model.entries }, icon = Icons.AutoMirrored.Rounded.PlaylistAdd)
                    }
                    Spacer(Modifier.weight(1f))
                    if (model.entries.isNotEmpty()) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            IconButton(onClick = { app.player.shuffleAll(model.entries) }) {
                                Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle", tint = Pink)
                            }
                        }
                        GradientPlayButton(playing = false, onClick = { ui.play(app.player, model.entries, 0) })
                    }
                }
            }
        }

        if (model.entries.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.MusicNote,
                    title = "Nothing here yet",
                    message = model.emptyMessage,
                    action = if (playlistId != null) (if (model.watchList) "Add videos" else "Add songs") else null,
                    onAction = if (playlistId != null) ({ picking = true }) else null,
                )
            }
        }

        itemsIndexed(model.entries, key = { _, e -> e.uri }) { index, entry ->
            TrackRow(
                entry = entry,
                isCurrent = player.current?.uri == entry.uri,
                isPlaying = player.isPlaying,
                onClick = { ui.play(app.player, model.entries, index) },
                onMore = { ui.openMenu(entry, playlistId) },
                trailing = if (editing && playlistId != null) {
                    {
                        IconButton(onClick = { app.store.moveInPlaylist(playlistId, index, index - 1) }, enabled = index > 0) {
                            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up", tint = if (index > 0) TextHi else TextLow)
                        }
                        IconButton(
                            onClick = { app.store.moveInPlaylist(playlistId, index, index + 1) },
                            enabled = index < model.entries.lastIndex,
                        ) {
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Move down",
                                tint = if (index < model.entries.lastIndex) TextHi else TextLow,
                            )
                        }
                        IconButton(onClick = { app.store.removeFromPlaylist(playlistId, entry.uri) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = TextMid)
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier.animateItem(),
            )
        }
    }

    if (picking && playlistId != null) {
        SongPickerSheet(
            title = "Add to ${model.title}",
            multiSelect = true,
            onDismiss = { picking = false },
            videosOnly = model.watchList,
            onPicked = { picked ->
                if (picked.isNotEmpty()) {
                    val added = app.store.addToPlaylist(playlistId, picked)
                    app.say(if (added == 1) "Added 1 item" else "Added $added items")
                }
            },
        )
    }
    if (renaming && playlistId != null) {
        TextPromptDialog(
            title = if (model.watchList) "Rename watch list" else "Rename playlist",
            label = "Name",
            confirm = "Save",
            initial = model.title,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                app.store.renamePlaylist(playlistId, name)
                renaming = false
            },
        )
    }
    if (deleting) {
        if (playlistId != null) {
            ConfirmDialog(
                title = "Delete \"${model.title}\"?",
                message = if (model.watchList) {
                    "The watch list is removed. The videos themselves stay where they are."
                } else {
                    "The playlist is removed. The songs themselves stay on your phone."
                },
                confirm = "Delete",
                onDismiss = { deleting = false },
                onConfirm = {
                    deleting = false
                    app.store.deletePlaylist(playlistId)
                    nav.popBackStack()
                },
            )
        } else {
            ConfirmDialog(
                title = "Clear history?",
                message = "This empties Recently played and resets Most played.",
                confirm = "Clear",
                onDismiss = { deleting = false },
                onConfirm = {
                    deleting = false
                    app.store.clearHistory()
                },
            )
        }
    }
}
