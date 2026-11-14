package com.noexcusehere.mashwave.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoLibrary
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.data.MediaSource
import com.noexcusehere.mashwave.data.source
import com.noexcusehere.mashwave.data.formatDuration
import com.noexcusehere.mashwave.data.toFolders
import com.noexcusehere.mashwave.ui.ImportKind
import com.noexcusehere.mashwave.ui.LocalNav
import com.noexcusehere.mashwave.ui.LocalUi
import com.noexcusehere.mashwave.ui.Routes
import com.noexcusehere.mashwave.ui.rememberMediaImporter
import com.noexcusehere.mashwave.ui.components.Artwork
import com.noexcusehere.mashwave.ui.components.Chip
import com.noexcusehere.mashwave.ui.components.EmptyState
import com.noexcusehere.mashwave.ui.components.HeaderIcon
import com.noexcusehere.mashwave.ui.components.ScreenHeader
import com.noexcusehere.mashwave.ui.components.TextPromptDialog
import com.noexcusehere.mashwave.ui.theme.BrandGradient
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextMid

@Composable
fun VideosScreen() {
    val app = LocalContext.current.app
    val ui = LocalUi.current
    val nav = LocalNav.current
    val videos by app.library.videos.collectAsStateWithLifecycle()
    val data by app.store.data.collectAsStateWithLifecycle()
    val player by app.player.state.collectAsStateWithLifecycle()
    val importer = rememberMediaImporter()
    var addMenu by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    // Which videos to show: null = all, "src:WHATSAPP" = one source, "dir:/path" = one folder.
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val watchLists = remember(data.playlists) { data.playlists.filter { it.video } }
    val sources = remember(videos) { MediaSource.entries.filter { s -> s != MediaSource.OTHER && videos.any { it.source == s } } }
    val otherFolders = remember(videos) { videos.filter { it.source == MediaSource.OTHER }.toFolders() }
    val shown = remember(videos, filter) {
        val f = filter
        when {
            f == null -> videos
            f.startsWith("src:") -> videos.filter { it.source.name == f.removePrefix("src:") }
            else -> videos.filter { it.folder == f.removePrefix("dir:") }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader("Videos") {
            com.noexcusehere.mashwave.ui.CastButton()
            HeaderIcon(Icons.Rounded.Link, "Open network stream", { ui.streamDialogOpen = true })
            Box {
                HeaderIcon(Icons.Rounded.Add, "Add videos", { addMenu = true }, tint = Pink)
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }, containerColor = Surface2) {
                    DropdownMenuItem(
                        text = { Text("From Google Photos or gallery") },
                        leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) },
                        onClick = {
                            addMenu = false
                            importer.fromPhotos()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("From Google Drive or files") },
                        leadingIcon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                        onClick = {
                            addMenu = false
                            importer.fromFiles(ImportKind.VIDEO)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("New watch list") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
                        onClick = {
                            addMenu = false
                            creating = true
                        },
                    )
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // ---- watch lists: playlists for videos ----
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Watch lists", style = MaterialTheme.typography.titleLarge, color = TextHi, modifier = Modifier.weight(1f))
                        Text(
                            "New",
                            style = MaterialTheme.typography.labelLarge,
                            color = Pink,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { creating = true }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    if (watchLists.isEmpty()) {
                        Text(
                            "Line up videos to watch one after another, like a playlist. Use a video's menu and choose “Add to watch list”.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMid,
                            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                            items(watchLists, key = { it.id }) { list ->
                                Column(
                                    Modifier
                                        .width(150.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .combinedClickable(
                                            onClick = { nav.navigate(Routes.collection("playlist", list.id)) },
                                            onLongClick = {
                                                if (list.items.isEmpty()) app.say("This watch list is empty") else ui.play(app.player, list.items, 0)
                                            },
                                        ),
                                ) {
                                    Box {
                                        PlaylistCover(
                                            list.items,
                                            Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(16f / 9f),
                                            corner = 12.dp,
                                        )
                                        Box(
                                            Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(6.dp)
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(BrandGradient)
                                                .clickable {
                                                    if (list.items.isEmpty()) app.say("This watch list is empty") else ui.play(app.player, list.items, 0)
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Play ${list.name}", tint = Color.White)
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(list.name, style = MaterialTheme.typography.titleSmall, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (list.items.size == 1) "1 video" else "${list.items.size} videos",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMid,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (videos.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        icon = Icons.Rounded.VideoLibrary,
                        title = "No videos found",
                        message = "Videos stored on this phone appear here. Use + to add videos from Google Photos or Google Drive.",
                        action = "Add from Google Drive or files",
                        onAction = { importer.fromFiles(ImportKind.VIDEO) },
                    )
                }
            } else {
                // ---- where the videos came from ----
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        item { Chip("All", selected = filter == null, onClick = { filter = null }) }
                        items(sources, key = { it.name }) { source ->
                            val key = "src:${source.name}"
                            Chip(source.label, selected = filter == key, onClick = { filter = key })
                        }
                        items(otherFolders, key = { it.path }) { f ->
                            val key = "dir:${f.path}"
                            Chip(f.name, selected = filter == key, onClick = { filter = key })
                        }
                    }
                }
                itemsIndexed(shown, key = { _, e -> e.uri }) { index, entry ->
                    VideoCard(
                        entry = entry,
                        isCurrent = player.current?.uri == entry.uri,
                        onClick = { ui.play(app.player, shown, index) },
                        onMore = { ui.openMenu(entry) },
                    )
                }
            }
        }
    }

    if (creating) {
        TextPromptDialog(
            title = "New watch list",
            label = "Watch list name",
            confirm = "Create",
            onDismiss = { creating = false },
            onConfirm = { name ->
                val list = app.store.createPlaylist(name, video = true)
                creating = false
                nav.navigate(Routes.collection("playlist", list.id))
            },
        )
    }
}

@Composable
fun VideoCard(entry: MediaEntry, isCurrent: Boolean, onClick: () -> Unit, onMore: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onMore),
    ) {
        Box {
            Artwork(
                entry,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                RoundedCornerShape(12.dp),
                large = true,
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White)
            }
            if (entry.durationMs > 0) {
                Text(
                    formatDuration(entry.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isCurrent) Pink else TextHi,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.height > 0) {
                    Text(
                        "${minOf(entry.width, entry.height).takeIf { it > 0 } ?: entry.height}p · ${entry.folderName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = "More options",
                tint = TextMid,
                modifier = Modifier
                    .clip(CircleShape)
                    .combinedClickable(onClick = onMore)
                    .padding(6.dp)
                    .size(20.dp),
            )
        }
    }
}
