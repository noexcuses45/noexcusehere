package com.noexcusehere.mashwave.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.countLabel
import com.noexcusehere.mashwave.data.toAlbums
import com.noexcusehere.mashwave.data.toArtists
import com.noexcusehere.mashwave.ui.LocalNav
import com.noexcusehere.mashwave.ui.LocalUi
import com.noexcusehere.mashwave.ui.Routes
import com.noexcusehere.mashwave.ui.components.Artwork
import com.noexcusehere.mashwave.ui.components.EmptyState
import com.noexcusehere.mashwave.ui.components.SectionTitle
import com.noexcusehere.mashwave.ui.components.TrackRow
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid

@Composable
fun SearchScreen() {
    val app = LocalContext.current.app
    val ui = LocalUi.current
    val nav = LocalNav.current
    val songs by app.library.songs.collectAsStateWithLifecycle()
    val videos by app.library.videos.collectAsStateWithLifecycle()
    val data by app.store.data.collectAsStateWithLifecycle()
    val player by app.player.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val q = query.trim()
    val albums = remember(songs) { songs.toAlbums() }
    val artists = remember(songs) { songs.toArtists() }
    val songHits = remember(q, songs) {
        if (q.isEmpty()) emptyList() else songs.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
        }.take(60)
    }
    val albumHits = remember(q, albums) { if (q.isEmpty()) emptyList() else albums.filter { it.title.contains(q, true) }.take(8) }
    val artistHits = remember(q, artists) { if (q.isEmpty()) emptyList() else artists.filter { it.name.contains(q, true) }.take(8) }
    val videoHits = remember(q, videos) { if (q.isEmpty()) emptyList() else videos.filter { it.title.contains(q, true) }.take(30) }
    val playlistHits = remember(q, data.playlists) {
        if (q.isEmpty()) emptyList() else data.playlists.filter { it.name.contains(q, true) }
    }
    val nothing = q.isNotEmpty() && songHits.isEmpty() && albumHits.isEmpty() && artistHits.isEmpty() &&
        videoHits.isEmpty() && playlistHits.isEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = TextHi)
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Songs, artists, albums, videos", color = TextLow) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = TextMid) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = TextMid)
                        }
                    }
                },
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
                    .weight(1f)
                    .focusRequester(focus),
            )
        }

        if (q.isEmpty()) {
            EmptyState(Icons.Rounded.Search, "Find anything", "Search everything on this phone by title, artist or album.")
            return@Column
        }
        if (nothing) {
            EmptyState(Icons.Rounded.Search, "No results", "Nothing matches \"$q\". Check the spelling or try fewer letters.")
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (artistHits.isNotEmpty()) {
                item { SectionTitle("Artists") }
                items(artistHits, key = { "artist:" + it.name }) { artist ->
                    ResultRow(artist.name, "${artist.songs.size} songs", { Artwork(artist.songs.firstOrNull(), Modifier.size(48.dp), CircleShape) }) {
                        nav.navigate(Routes.collection("artist", artist.name))
                    }
                }
            }
            if (albumHits.isNotEmpty()) {
                item { SectionTitle("Albums") }
                items(albumHits, key = { "album:" + it.key }) { album ->
                    ResultRow(album.title, album.artist, { Artwork(album.songs.firstOrNull(), Modifier.size(48.dp)) }) {
                        nav.navigate(Routes.collection("album", album.key))
                    }
                }
            }
            if (playlistHits.isNotEmpty()) {
                item { SectionTitle("Playlists") }
                items(playlistHits, key = { "playlist:" + it.id }) { playlist ->
                    ResultRow(playlist.name, countLabel(playlist.items), { PlaylistCover(playlist.items, Modifier.size(48.dp), corner = 8.dp) }) {
                        nav.navigate(Routes.collection("playlist", playlist.id))
                    }
                }
            }
            if (songHits.isNotEmpty()) {
                item { SectionTitle("Songs") }
                itemsIndexed(songHits, key = { _, e -> e.uri }) { index, entry ->
                    TrackRow(
                        entry = entry,
                        isCurrent = player.current?.uri == entry.uri,
                        isPlaying = player.isPlaying,
                        onClick = { ui.play(app.player, songHits, index) },
                        onMore = { ui.openMenu(entry) },
                    )
                }
            }
            if (videoHits.isNotEmpty()) {
                item { SectionTitle("Videos") }
                itemsIndexed(videoHits, key = { _, e -> e.uri }) { index, entry ->
                    TrackRow(
                        entry = entry,
                        isCurrent = player.current?.uri == entry.uri,
                        isPlaying = player.isPlaying,
                        onClick = { ui.play(app.player, videoHits, index) },
                        onMore = { ui.openMenu(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(title: String, subtitle: String, art: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        art()
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
