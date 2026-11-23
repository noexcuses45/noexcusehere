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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VideoLibrary
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noexcusehere.mashwave.desktop.Desk
import com.noexcusehere.mashwave.desktop.LocalFile
import com.noexcusehere.mashwave.desktop.vlc.PlayStatus
import com.noexcusehere.mashwave.desktop.vlc.Repeat
import com.noexcusehere.mashwave.desktop.vlc.VlcPlayer
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.UIManager

/** The boxes that ask for a file or a folder: the computer's own, so they look familiar. */
object Choosers {
    fun files(owner: Frame?): List<File> = runCatching {
        val dialog = FileDialog(owner, "Open music or videos", FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.isVisible = true
        dialog.files?.toList().orEmpty()
    }.getOrDefault(emptyList())

    fun folder(owner: Frame?): File? = runCatching {
        if (System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("mac")) {
            // On a Mac the ordinary "open" box can be told to pick folders instead.
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            try {
                val dialog = FileDialog(owner, "Choose a folder", FileDialog.LOAD)
                dialog.isVisible = true
                dialog.files?.firstOrNull()
            } finally {
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
            }
        } else {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            val chooser = JFileChooser(File(System.getProperty("user.home").orEmpty()))
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            chooser.dialogTitle = "Choose a folder"
            if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        }
    }.getOrNull()
}

/** One album or one artist: its name and the songs that belong to it. */
private class Shelf(val key: String, val name: String, val under: String, val cover: String, val songs: List<LocalFile>)

private fun albumsOf(songs: List<LocalFile>): List<Shelf> = songs.filter { it.album.isNotBlank() }
    .groupBy { it.album.lowercase(Locale.ROOT) }
    .map { (key, list) ->
        val artists = list.map { it.artist }.filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }
        Shelf(
            key = "album:$key",
            name = list.first().album,
            under = when (artists.size) {
                0 -> ""
                1 -> artists.first()
                else -> "Various artists"
            },
            cover = list.firstOrNull { it.cover.isNotEmpty() }?.cover.orEmpty(),
            songs = list.sortedWith(compareBy<LocalFile> { it.track }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }),
        )
    }
    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

private fun artistsOf(songs: List<LocalFile>): List<Shelf> = songs.filter { it.artist.isNotBlank() }
    .groupBy { it.artist.lowercase(Locale.ROOT) }
    .map { (key, list) ->
        Shelf(
            key = "artist:$key",
            name = list.first().artist,
            under = if (list.size == 1) "1 song" else "${list.size} songs",
            cover = list.firstOrNull { it.cover.isNotEmpty() }?.cover.orEmpty(),
            songs = list,
        )
    }
    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

/** True when every word of [query] is somewhere in the song's title, artist or album. */
internal fun songMatches(song: LocalFile, query: String): Boolean {
    val words = query.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return true
    val text = (song.title + " " + song.artist + " " + song.album).lowercase(Locale.ROOT)
    return words.all { it in text }
}

/** The music on this computer: every song, or sorted into albums and artists. */
@Composable
fun MusicScreen(desk: Desk) {
    val library = desk.library
    val songs = library.songs
    var view by rememberSaveable { mutableStateOf("songs") }
    var query by rememberSaveable { mutableStateOf("") }
    var opened by rememberSaveable { mutableStateOf("") }

    val found = remember(songs, query) { if (query.isBlank()) songs else songs.filter { songMatches(it, query) } }
    val albums = remember(found) { albumsOf(found) }
    val artists = remember(found) { artistsOf(found) }
    val shelf = remember(opened, albums, artists) { (albums + artists).firstOrNull { it.key == opened } }

    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 20.dp, top = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Music", style = MaterialTheme.typography.headlineSmall, color = TextHi)
                Text(
                    when {
                        library.looking -> "Looking through your folders... ${library.foundSoFar} files so far"
                        songs.size == 1 -> "1 song on this computer"
                        else -> "${songs.size} songs on this computer"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextLow,
                )
            }
            Spacer(Modifier.weight(1f))
            Field(query, { query = it }, "Find a song, artist or album", Modifier.width(280.dp), icon = Icons.Rounded.Search)
            Spacer(Modifier.width(10.dp))
            Quiet("Open a file", { Choosers.files(desk.window).takeIf { it.isNotEmpty() }?.let { desk.openFiles(it) } }, icon = Icons.Rounded.FolderOpen)
            Spacer(Modifier.width(6.dp))
            Pill(
                "Shuffle all",
                {
                    if (!desk.shuffle) desk.toggleShuffle()
                    if (found.isNotEmpty()) desk.playSongs(found, found.indices.random())
                },
                icon = Icons.Rounded.Shuffle,
                enabled = found.isNotEmpty(),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Songs", view == "songs" && shelf == null, {
                view = "songs"
                opened = ""
            })
            Chip("Albums", view == "albums" && shelf == null, {
                view = "albums"
                opened = ""
            })
            Chip("Artists", view == "artists" && shelf == null, {
                view = "artists"
                opened = ""
            })
            Chip("Mashups", view == "mashups" && shelf == null, {
                view = "mashups"
                opened = ""
            })
        }
        Spacer(Modifier.height(10.dp))
        when {
            view == "mashups" && shelf == null -> MashupPanel(desk)
            songs.isEmpty() && library.looking -> Busy("Looking for music...")
            songs.isEmpty() -> Note(
                Icons.Rounded.MusicNote,
                "No music found yet",
                if (desk.folders.isEmpty()) "Choose the folder your music is kept in." else "Looked in: " + desk.folders.joinToString(", ") + ". If your music is somewhere else, choose that folder.",
                action = "Choose a folder",
                onAction = { Choosers.folder(desk.window)?.let { desk.addFolder(it) } },
            )
            shelf != null -> Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { opened = "" })
                    Spacer(Modifier.width(10.dp))
                    Cover(shelf.cover, 54.dp, if (shelf.key.startsWith("artist")) Icons.Rounded.Person else Icons.Rounded.Album)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(shelf.name, style = MaterialTheme.typography.titleLarge, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (shelf.under.isNotEmpty()) Text(shelf.under, style = MaterialTheme.typography.bodyMedium, color = TextMid, maxLines = 1)
                    }
                    Pill("Play", { desk.playSongs(shelf.songs, 0) }, icon = Icons.Rounded.PlayArrow)
                }
                SongList(desk, shelf.songs, numbered = shelf.key.startsWith("album"))
            }
            found.isEmpty() -> Note(Icons.Rounded.Search, "Nothing found", "No song, artist or album has \"$query\" in its name.")
            view == "albums" -> ShelfGrid(albums, Icons.Rounded.Album, "No albums", "None of these songs say which album they are from.") { opened = it.key }
            view == "artists" -> ShelfGrid(artists, Icons.Rounded.Person, "No artists", "None of these songs say who they are by.") { opened = it.key }
            else -> SongList(desk, found, numbered = false)
        }
    }
}

@Composable
private fun SongList(desk: Desk, songs: List<LocalFile>, numbered: Boolean) {
    val state = rememberLazyListState()
    val playing = desk.player?.current?.uri
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, contentPadding = PaddingValues(bottom = 20.dp, end = 12.dp)) {
            itemsIndexed(songs, key = { _, song -> song.path }) { index, song ->
                val current = song.path == playing
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (current) Violet.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable { desk.playSongs(songs, index) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                        when {
                            current -> Icon(Icons.Rounded.GraphicEq, contentDescription = "Playing", tint = Pink, modifier = Modifier.size(18.dp))
                            numbered && song.track > 0 -> Text(song.track.toString(), style = MaterialTheme.typography.bodyMedium, color = TextLow)
                            else -> Text((index + 1).toString(), style = MaterialTheme.typography.bodySmall, color = TextLow)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Cover(song.cover, 38.dp, Icons.Rounded.MusicNote)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, style = MaterialTheme.typography.bodyLarge, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val under = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString("  ·  ")
                        if (under.isNotEmpty()) Text(under, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (song.durationMs > 0) {
                        Spacer(Modifier.width(10.dp))
                        Text(clock(song.durationMs), style = MaterialTheme.typography.bodySmall, color = TextLow)
                    }
                }
            }
        }
        ListBar(state)
    }
}

@Composable
private fun ShelfGrid(shelves: List<Shelf>, icon: ImageVector, emptyTitle: String, emptyText: String, onOpen: (Shelf) -> Unit) {
    if (shelves.isEmpty()) {
        Note(icon, emptyTitle, emptyText)
        return
    }
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = state,
            contentPadding = PaddingValues(bottom = 20.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shelves, key = { it.key }) { shelf ->
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { onOpen(shelf) }.padding(6.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(Surface2), contentAlignment = Alignment.Center) {
                        Picture(shelf.cover, Modifier.fillMaxSize(), side = 300) {
                            Icon(icon, contentDescription = null, tint = TextLow, modifier = Modifier.size(46.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(shelf.name, style = MaterialTheme.typography.bodyLarge, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (shelf.under.isNotEmpty()) Text(shelf.under, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        GridBar(state)
    }
}

/** A small square picture: the album's cover, or [fallback] when there is none. */
@Composable
private fun Cover(path: String, size: Dp, fallback: ImageVector) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 6)).background(Surface2), contentAlignment = Alignment.Center) {
        Picture(path, Modifier.fillMaxSize(), side = 120) {
            Icon(fallback, contentDescription = null, tint = TextLow, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/** The video files on this computer, folder by folder. */
@Composable
fun MyVideosScreen(desk: Desk) {
    val library = desk.library
    val videos = library.videos
    var query by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf("") }

    val folders = remember(videos) {
        videos.groupBy { File(it.path).parent.orEmpty() }.map { (path, list) -> Triple(path, File(path).name.ifEmpty { path }, list.size) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })
    }
    val shown = remember(videos, query, folder) {
        videos.filter { (folder.isEmpty() || File(it.path).parent.orEmpty() == folder) && songMatches(it, query) }
    }

    Row(Modifier.fillMaxSize()) {
        if (folders.size > 1) {
            Column(Modifier.width(230.dp).fillMaxHeight().background(Surface1).padding(top = 18.dp)) {
                Text("Folders", style = MaterialTheme.typography.titleMedium, color = TextHi, modifier = Modifier.padding(start = 18.dp, bottom = 8.dp))
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    item { SideRow("All videos", folder.isEmpty(), { folder = "" }, count = videos.size, icon = Icons.Rounded.VideoLibrary) }
                    items(folders, key = { it.first }) { (path, name, count) ->
                        SideRow(name, folder == path, { folder = path }, count = count, icon = Icons.Rounded.Folder)
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp, end = 20.dp, top = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("My videos", style = MaterialTheme.typography.headlineSmall, color = TextHi)
                    Text(
                        when {
                            library.looking -> "Looking through your folders... ${library.foundSoFar} files so far"
                            videos.size == 1 -> "1 video on this computer"
                            else -> "${videos.size} videos on this computer"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextLow,
                    )
                }
                Spacer(Modifier.weight(1f))
                Field(query, { query = it }, "Find a video", Modifier.width(240.dp), icon = Icons.Rounded.Search)
                Spacer(Modifier.width(10.dp))
                Pill("Open a file", { Choosers.files(desk.window).takeIf { it.isNotEmpty() }?.let { desk.openFiles(it) } }, icon = Icons.Rounded.FolderOpen)
            }
            Spacer(Modifier.height(12.dp))
            when {
                videos.isEmpty() && library.looking -> Busy("Looking for videos...")
                videos.isEmpty() -> Note(
                    Icons.Rounded.Movie,
                    "No videos found yet",
                    (if (desk.folders.isEmpty()) "Choose the folder your videos are kept in." else "Looked in: " + desk.folders.joinToString(", ") + ". If your videos are somewhere else, choose that folder.") +
                        " You can also drop a video onto this window to play it.",
                    action = "Choose a folder",
                    onAction = { Choosers.folder(desk.window)?.let { desk.addFolder(it) } },
                )
                shown.isEmpty() -> Note(Icons.Rounded.Search, "Nothing found", "No video here has \"$query\" in its name.")
                else -> {
                    val state = rememberLazyGridState()
                    Box(Modifier.fillMaxSize()) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(210.dp),
                            state = state,
                            contentPadding = PaddingValues(bottom = 20.dp, end = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(shown, key = { _, video -> video.path }) { index, video ->
                                val place = desk.resume[video.path]
                                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { desk.playVideos(shown, index) }.padding(6.dp)) {
                                    Box(
                                        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Surface2),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Rounded.Movie, contentDescription = null, tint = TextLow, modifier = Modifier.size(40.dp))
                                        if (place != null && place.durationMs > 0) {
                                            Bar((place.positionMs.toFloat() / place.durationMs).coerceIn(0f, 1f), Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp))
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(video.title, style = MaterialTheme.typography.bodyLarge, color = TextHi, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (place != null) "stopped at ${clock(place.positionMs)}" else video.folder,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (place != null) Pink else TextLow,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        GridBar(state)
                    }
                }
            }
        }
    }
}

/** What is playing, along the bottom of the window, while it is a song. */
@Composable
fun MusicBar(desk: Desk, player: VlcPlayer) {
    val entry = player.current ?: return
    var dragging by remember { mutableStateOf(false) }
    var dragAt by remember { mutableFloatStateOf(0f) }
    val length = player.durationMs
    val fraction = if (dragging) dragAt else if (length > 0) (player.positionMs.toFloat() / length).coerceIn(0f, 1f) else 0f

    Row(
        Modifier.fillMaxWidth().height(78.dp).background(Surface1).border(1.dp, Outline).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Cover(entry.art, 52.dp, Icons.Rounded.MusicNote)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val under = when {
                    player.status == PlayStatus.FAILED -> player.problem.ifEmpty { "This could not be played." }
                    else -> listOf(entry.artist, entry.album).filter { it.isNotBlank() }.joinToString("  ·  ")
                }
                if (under.isNotEmpty()) {
                    Text(under, style = MaterialTheme.typography.bodySmall, color = if (player.status == PlayStatus.FAILED) Amber else TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundIcon(Icons.Rounded.Shuffle, if (desk.shuffle) "Shuffle is on" else "Shuffle", { desk.toggleShuffle() }, size = 32.dp, tint = if (desk.shuffle) Pink else TextMid)
                RoundIcon(Icons.Rounded.SkipPrevious, "Previous", { desk.previousSong() }, size = 36.dp)
                val paused = player.status != PlayStatus.PLAYING && player.status != PlayStatus.LOADING
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color.White).clickable { if (player.status == PlayStatus.ENDED || player.status == PlayStatus.FAILED) player.retry() else player.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = if (paused) "Play" else "Pause", tint = Ink, modifier = Modifier.size(24.dp))
                }
                RoundIcon(Icons.Rounded.SkipNext, "Next", { player.next() }, size = 36.dp, enabled = player.hasNext)
                RoundIcon(
                    if (desk.repeat == Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    when (desk.repeat) {
                        Repeat.OFF -> "Repeat"
                        Repeat.ALL -> "Repeating them all"
                        Repeat.ONE -> "Repeating this song"
                    },
                    { desk.nextRepeat() },
                    size = 32.dp,
                    tint = if (desk.repeat == Repeat.OFF) TextMid else Pink,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(if (dragging) (dragAt * length).toLong() else player.positionMs), style = MaterialTheme.typography.labelSmall, color = TextLow, modifier = Modifier.width(44.dp))
                Track(
                    fraction,
                    enabled = player.seekable && length > 0,
                    onChange = {
                        dragging = true
                        dragAt = it
                    },
                    onDone = {
                        player.seekTo((it * length).toLong())
                        dragging = false
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(if (length > 0) clock(length) else "", style = MaterialTheme.typography.labelSmall, color = TextLow, modifier = Modifier.width(44.dp).padding(start = 8.dp))
            }
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            RoundIcon(
                if (player.muted || player.volume == 0) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                if (player.muted) "Sound on" else "Sound off",
                {
                    player.toggleMute()
                    desk.rememberSound()
                },
                size = 32.dp,
            )
            Track(
                if (player.muted) 0f else player.volume / 100f,
                enabled = true,
                onChange = { player.changeVolume((it * 100).toInt()) },
                onDone = { desk.rememberSound() },
                modifier = Modifier.width(110.dp),
            )
            Spacer(Modifier.width(8.dp))
            RoundIcon(Icons.Rounded.Close, "Stop", { desk.stopPlaying() }, size = 32.dp, tint = TextMid)
        }
    }
}
