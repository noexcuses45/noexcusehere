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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.Mashup
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.data.Playlist
import com.noexcusehere.mashwave.data.countLabel
import com.noexcusehere.mashwave.ui.LocalNav
import com.noexcusehere.mashwave.ui.LocalUi
import com.noexcusehere.mashwave.ui.Routes
import com.noexcusehere.mashwave.ui.components.Artwork
import com.noexcusehere.mashwave.ui.components.EmptyState
import com.noexcusehere.mashwave.ui.components.HeaderIcon
import com.noexcusehere.mashwave.ui.components.SectionTitle
import com.noexcusehere.mashwave.ui.openTab
import com.noexcusehere.mashwave.ui.rememberOpenFiles
import com.noexcusehere.mashwave.ui.theme.Amber
import com.noexcusehere.mashwave.ui.theme.Cyan
import com.noexcusehere.mashwave.ui.theme.DeckA
import com.noexcusehere.mashwave.ui.theme.DeckB
import com.noexcusehere.mashwave.ui.theme.Ink
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextMid
import com.noexcusehere.mashwave.ui.theme.Violet
import java.util.Calendar

@Composable
fun HomeScreen() {
    val app = LocalContext.current.app
    val ui = LocalUi.current
    val nav = LocalNav.current
    val songs by app.library.songs.collectAsStateWithLifecycle()
    val videos by app.library.videos.collectAsStateWithLifecycle()
    val scanned by app.library.scannedOnce.collectAsStateWithLifecycle()
    val data by app.store.data.collectAsStateWithLifecycle()
    val openFiles = rememberOpenFiles()
    var menuOpen by remember { mutableStateOf(false) }

    val recentlyAdded = remember(songs) { songs.sortedByDescending { it.dateAdded }.take(14) }
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Violet.copy(alpha = 0.28f), Ink, Ink), endY = 900f))
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(greeting, style = MaterialTheme.typography.headlineLarge, color = TextHi, modifier = Modifier.weight(1f))
                HeaderIcon(Icons.Rounded.Search, "Search", { nav.navigate(Routes.SEARCH) })
                HeaderIcon(Icons.Rounded.Equalizer, "Equalizer", { nav.navigate(Routes.EQUALIZER) })
                Box {
                    HeaderIcon(Icons.Rounded.Add, "Open", { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = Surface2) {
                        DropdownMenuItem(
                            text = { Text("Open a file") },
                            leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                openFiles()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Open network stream") },
                            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                ui.streamDialogOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Rescan library") },
                            leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                app.library.refresh()
                                app.say("Looking for new music and videos")
                            },
                        )
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickTile("Liked", Icons.Rounded.Favorite, listOf(Violet, Pink), Modifier.weight(1f)) {
                        nav.navigate(Routes.collection("liked"))
                    }
                    QuickTile("Shuffle all", Icons.Rounded.Shuffle, listOf(Color(0xFF0EA5E9), Cyan), Modifier.weight(1f)) {
                        if (songs.isEmpty()) app.say("No songs found yet") else app.player.shuffleAll(songs)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickTile("Recently played", Icons.Rounded.History, listOf(Color(0xFFF97316), Amber), Modifier.weight(1f)) {
                        nav.navigate(Routes.collection("recent"))
                    }
                    QuickTile("Most played", Icons.AutoMirrored.Rounded.TrendingUp, listOf(Color(0xFF10B981), Color(0xFF22D3EE)), Modifier.weight(1f)) {
                        nav.navigate(Routes.collection("top"))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickTile("Mashup Studio", Icons.Rounded.GraphicEq, listOf(DeckA, DeckB), Modifier.weight(1f)) {
                        nav.openTab(Routes.MASHUP)
                    }
                    QuickTile("Videos", Icons.Rounded.VideoLibrary, listOf(Color(0xFFE11D48), Violet), Modifier.weight(1f)) {
                        nav.openTab(Routes.VIDEOS)
                    }
                }
            }
        }

        if (data.history.isNotEmpty()) {
            item {
                SectionTitle("Jump back in", action = "See all", onAction = { nav.navigate(Routes.collection("recent")) })
                MediaCardRow(data.history.take(12)) { index -> ui.play(app.player, data.history.take(12), index) }
            }
        }

        if (data.playlists.isNotEmpty()) {
            item {
                SectionTitle("Your playlists", action = "See all", onAction = { nav.openTab(Routes.PLAYLISTS) })
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(data.playlists, key = { it.id }) { playlist ->
                        PlaylistCard(playlist) { nav.navigate(Routes.collection("playlist", playlist.id)) }
                    }
                }
            }
        }

        if (data.mashups.isNotEmpty()) {
            item {
                SectionTitle("Your mashups", action = "Studio", onAction = { nav.openTab(Routes.MASHUP) })
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(data.mashups, key = { it.id }) { mashup ->
                        MashupCard(mashup) {
                            app.mashup.open(mashup, autoPlay = true)
                            nav.openTab(Routes.MASHUP)
                        }
                    }
                }
            }
        }

        if (recentlyAdded.isNotEmpty()) {
            item {
                SectionTitle("Recently added", action = "Library", onAction = { nav.openTab(Routes.LIBRARY) })
                MediaCardRow(recentlyAdded) { index -> ui.play(app.player, recentlyAdded, index) }
            }
        }

        if (videos.isNotEmpty()) {
            item {
                SectionTitle("Videos", action = "See all", onAction = { nav.openTab(Routes.VIDEOS) })
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val shown = videos.take(10)
                    items(shown.size, key = { shown[it].uri }) { index ->
                        MediaCard(shown[index], width = 200.dp, wide = true) { ui.play(app.player, shown, index) }
                    }
                }
            }
        }

        if (scanned && songs.isEmpty() && videos.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.MusicOff,
                    title = "No music or videos found yet",
                    message = "Copy MP3s or videos onto this phone and they will show up here. You can also open any file or stream right now.",
                    action = "Open a file",
                    onAction = openFiles,
                )
            }
        }
    }
}

@Composable
private fun QuickTile(label: String, icon: ImageVector, colors: List<Color>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(58.dp)
                .background(Brush.linearGradient(colors)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = TextHi,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

@Composable
fun MediaCardRow(entries: List<MediaEntry>, onClick: (Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(entries.size, key = { entries[it].uri }) { index ->
            val entry = entries[index]
            MediaCard(entry, width = if (entry.isVideo) 200.dp else 132.dp, wide = entry.isVideo) { onClick(index) }
        }
    }
}

@Composable
fun MediaCard(entry: MediaEntry, width: Dp = 132.dp, wide: Boolean = false, onClick: () -> Unit) {
    val ui = LocalUi.current
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = { ui.openMenu(entry) }),
    ) {
        Artwork(
            entry,
            Modifier.size(width = width, height = if (wide) width * 9 / 16 else width),
            RoundedCornerShape(12.dp),
            large = true,
        )
        Spacer(Modifier.height(8.dp))
        Text(entry.title, style = MaterialTheme.typography.titleSmall, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(entry.subtitle, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
fun PlaylistCard(playlist: Playlist, width: Dp = 132.dp, onClick: () -> Unit) {
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        PlaylistCover(playlist.items, Modifier.size(width))
        Spacer(Modifier.height(8.dp))
        Text(playlist.name, style = MaterialTheme.typography.titleSmall, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(countLabel(playlist.items).substringBefore(" · "), style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1)
        Spacer(Modifier.height(4.dp))
    }
}

/** A 2x2 collage of the first four different covers (or a single cover for short lists). */
@Composable
fun PlaylistCover(items: List<MediaEntry>, modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    val covers = remember(items) { items.distinctBy { it.artKey }.take(4) }
    if (covers.size < 4) {
        Artwork(covers.firstOrNull(), modifier, RoundedCornerShape(corner), large = true)
    } else {
        Column(modifier.clip(RoundedCornerShape(corner))) {
            Row(Modifier.weight(1f)) {
                Artwork(covers[0], Modifier.weight(1f).fillMaxSize(), RoundedCornerShape(0.dp))
                Artwork(covers[1], Modifier.weight(1f).fillMaxSize(), RoundedCornerShape(0.dp))
            }
            Row(Modifier.weight(1f)) {
                Artwork(covers[2], Modifier.weight(1f).fillMaxSize(), RoundedCornerShape(0.dp))
                Artwork(covers[3], Modifier.weight(1f).fillMaxSize(), RoundedCornerShape(0.dp))
            }
        }
    }
}

@Composable
fun MashupCard(mashup: Mashup, width: Dp = 190.dp, onClick: () -> Unit) {
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Box(Modifier.height(74.dp).fillMaxWidth()) {
            Artwork(
                mashup.a,
                Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(DeckA)
                    .padding(3.dp),
                CircleShape,
            )
            Artwork(
                mashup.b,
                Modifier
                    .offset(x = 54.dp)
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(DeckB)
                    .padding(3.dp),
                CircleShape,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(mashup.name, style = MaterialTheme.typography.titleSmall, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${mashup.a.title} × ${mashup.b.title}",
            style = MaterialTheme.typography.bodySmall,
            color = TextMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
