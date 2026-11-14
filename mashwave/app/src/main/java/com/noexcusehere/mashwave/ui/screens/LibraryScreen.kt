package com.noexcusehere.mashwave.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.data.toAlbums
import com.noexcusehere.mashwave.data.toArtists
import com.noexcusehere.mashwave.data.toFolders
import com.noexcusehere.mashwave.ui.LocalNav
import com.noexcusehere.mashwave.ui.ImportKind
import com.noexcusehere.mashwave.ui.LocalUi
import com.noexcusehere.mashwave.ui.rememberMediaImporter
import com.noexcusehere.mashwave.ui.Routes
import com.noexcusehere.mashwave.ui.components.Artwork
import com.noexcusehere.mashwave.ui.components.Chip
import com.noexcusehere.mashwave.ui.components.EmptyState
import com.noexcusehere.mashwave.ui.components.HeaderIcon
import com.noexcusehere.mashwave.ui.components.ScreenHeader
import com.noexcusehere.mashwave.ui.components.TrackRow
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextMid
import com.noexcusehere.mashwave.ui.theme.Violet

private val sortLabels = listOf(
    "TITLE" to "Title",
    "ARTIST" to "Artist",
    "ALBUM" to "Album",
    "NEWEST" to "Recently added",
    "LONGEST" to "Longest first",
)

fun sortSongs(songs: List<MediaEntry>, sort: String): List<MediaEntry> = when (sort) {
    "ARTIST" -> songs.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.track }))
    "ALBUM" -> songs.sortedWith(compareBy({ it.album.lowercase() }, { it.track }, { it.title.lowercase() }))
    "NEWEST" -> songs.sortedByDescending { it.dateAdded }
    "LONGEST" -> songs.sortedByDescending { it.durationMs }
    else -> songs
}

@Composable
fun LibraryScreen() {
    val app = LocalContext.current.app
    val ui = LocalUi.current
    val nav = LocalNav.current
    val songs by app.library.songs.collectAsStateWithLifecycle()
    val data by app.store.data.collectAsStateWithLifecycle()
    val player by app.player.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var sortOpen by remember { mutableStateOf(false) }
    val importer = rememberMediaImporter()
    val tabs = listOf("Songs", "Albums", "Artists", "Folders")

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader("Library") {
            HeaderIcon(Icons.Rounded.CloudDownload, "Add music from Google Drive or files", { importer.fromFiles(ImportKind.MUSIC) })
            HeaderIcon(Icons.Rounded.Search, "Search", { nav.navigate(Routes.SEARCH) })
            if (tab == 0) {
                Box {
                    HeaderIcon(Icons.AutoMirrored.Rounded.Sort, "Sort", { sortOpen = true })
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }, containerColor = Surface2) {
                        sortLabels.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label, color = if (data.songSort == key) Pink else TextHi) },
                                onClick = {
                                    app.store.setSongSort(key)
                                    sortOpen = false
                                },
                            )
                        }
                    }
                }
            }
            HeaderIcon(Icons.Rounded.Shuffle, "Shuffle all songs", {
                if (songs.isEmpty()) app.say("No songs found yet") else app.player.shuffleAll(songs)
            }, tint = Pink)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            itemsIndexed(tabs) { index, label ->
                Chip(label, selected = tab == index, onClick = { tab = index })
            }
        }

        if (songs.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.LibraryMusic,
                title = "Your library is empty",
                message = "Songs stored on this phone appear here automatically.",
                action = "Rescan",
                onAction = { app.library.refresh() },
            )
            return@Column
        }

        when (tab) {
            0 -> {
                val sorted = remember(songs, data.songSort) { sortSongs(songs, data.songSort) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Text(
                            "${sorted.size} songs",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMid,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    itemsIndexed(sorted, key = { _, e -> e.uri }) { index, entry ->
                        TrackRow(
                            entry = entry,
                            isCurrent = player.current?.uri == entry.uri,
                            isPlaying = player.isPlaying,
                            onClick = { ui.play(app.player, sorted, index) },
                            onMore = { ui.openMenu(entry) },
                        )
                    }
                }
            }

            1 -> {
                val albums = remember(songs) { songs.toAlbums() }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(albums, key = { it.key }) { album ->
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { nav.navigate(Routes.collection("album", album.key)) },
                        ) {
                            Artwork(
                                album.songs.firstOrNull(),
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f),
                                RoundedCornerShape(12.dp),
                                large = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(album.title, style = MaterialTheme.typography.titleSmall, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(album.artist, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            2 -> {
                val artists = remember(songs) { songs.toArtists() }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(artists, key = { it.name }) { artist ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { nav.navigate(Routes.collection("artist", artist.name)) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(artist.songs.firstOrNull(), Modifier.size(54.dp), CircleShape)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(artist.name, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${artist.songs.size} ${if (artist.songs.size == 1) "song" else "songs"} · " +
                                        "${artist.albumCount} ${if (artist.albumCount == 1) "album" else "albums"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMid,
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                val folders = remember(songs) { songs.toFolders() }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(folders, key = { it.path }) { folder ->
                        FolderRow(folder.name, folder.path, "${folder.items.size} songs") {
                            nav.navigate(Routes.collection("folder", folder.path))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FolderRow(name: String, path: String, count: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Violet.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = Violet)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$count · ${path.removePrefix("/storage/emulated/0/")}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
