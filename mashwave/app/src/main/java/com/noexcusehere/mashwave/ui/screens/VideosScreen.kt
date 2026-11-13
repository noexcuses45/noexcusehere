package com.noexcusehere.mashwave.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoLibrary
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
import com.noexcusehere.mashwave.data.formatDuration
import com.noexcusehere.mashwave.data.toFolders
import com.noexcusehere.mashwave.ui.LocalUi
import com.noexcusehere.mashwave.ui.components.Artwork
import com.noexcusehere.mashwave.ui.components.Chip
import com.noexcusehere.mashwave.ui.components.EmptyState
import com.noexcusehere.mashwave.ui.components.HeaderIcon
import com.noexcusehere.mashwave.ui.components.ScreenHeader
import com.noexcusehere.mashwave.ui.rememberOpenFiles
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextMid

@Composable
fun VideosScreen() {
    val app = LocalContext.current.app
    val ui = LocalUi.current
    val videos by app.library.videos.collectAsStateWithLifecycle()
    val player by app.player.state.collectAsStateWithLifecycle()
    val openFiles = rememberOpenFiles()
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    val folders = remember(videos) { videos.toFolders() }
    val shown = remember(videos, folder) { if (folder == null) videos else videos.filter { it.folder == folder } }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader("Videos") {
            HeaderIcon(Icons.Rounded.Link, "Open network stream", { ui.streamDialogOpen = true })
            HeaderIcon(Icons.Rounded.FolderOpen, "Open a file", openFiles)
        }

        if (videos.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.VideoLibrary,
                title = "No videos found",
                message = "Videos stored on this phone appear here. You can also open any video file or paste a stream address.",
                action = "Open a file",
                onAction = openFiles,
            )
            return@Column
        }

        if (folders.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                item { Chip("All", selected = folder == null, onClick = { folder = null }) }
                items(folders, key = { it.path }) { f ->
                    Chip(f.name, selected = folder == f.path, onClick = { folder = f.path })
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
