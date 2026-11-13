package com.noexcusehere.mashwave

import com.noexcusehere.mashwave.data.AppJson
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.data.Playlist
import com.noexcusehere.mashwave.data.UserData
import com.noexcusehere.mashwave.data.countLabel
import com.noexcusehere.mashwave.data.formatDuration
import com.noexcusehere.mashwave.data.toAlbums
import com.noexcusehere.mashwave.data.toArtists
import com.noexcusehere.mashwave.data.toFolders
import com.noexcusehere.mashwave.ui.screens.resolveCollection
import com.noexcusehere.mashwave.ui.screens.sortSongs
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    private fun song(n: Int, title: String, artist: String, album: String, albumId: Long, track: Int = 0, ms: Long = 180_000) =
        MediaEntry(
            uri = "content://media/external/audio/media/$n",
            title = title,
            artist = artist,
            album = album,
            albumId = albumId,
            durationMs = ms,
            folder = "/storage/emulated/0/Music/$artist",
            dateAdded = n.toLong(),
            track = track,
        )

    private val library = listOf(
        song(1, "Zebra", "Alpha", "First", 10, track = 2),
        song(2, "Apple", "Alpha", "First", 10, track = 1),
        song(3, "Mango", "Beta", "Second", 20),
        song(4, "Loose", "", "", 0),
    )

    @Test
    fun formatsDurations() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:05", formatDuration(5_000))
        assertEquals("3:07", formatDuration(187_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }

    @Test
    fun groupsSongsIntoAlbumsInTrackOrder() {
        val albums = library.toAlbums()
        assertEquals(listOf("First", "Second", "Unknown album"), albums.map { it.title })
        assertEquals(listOf("Apple", "Zebra"), albums.first().songs.map { it.title })
        assertEquals("Alpha", albums.first().artist)
    }

    @Test
    fun groupsSongsIntoArtistsAndFolders() {
        assertEquals(listOf("Alpha", "Beta", "Unknown artist"), library.toArtists().map { it.name })
        assertEquals(3, library.toFolders().size)
    }

    @Test
    fun sortsSongs() {
        assertEquals(listOf(4, 3, 2, 1), sortSongs(library, "NEWEST").map { it.dateAdded.toInt() })
        assertEquals("Loose", sortSongs(library, "ARTIST").first().title)
    }

    @Test
    fun describesCounts() {
        assertEquals("Empty", countLabel(emptyList()))
        assertEquals("1 song · 3 min", countLabel(library.take(1)))
        val mixed = library.take(2) + MediaEntry(uri = "v", title = "Clip", isVideo = true, durationMs = 60_000)
        assertEquals("2 songs, 1 video · 7 min", countLabel(mixed))
    }

    @Test
    fun resolvesAlbumArtistAndPlaylistPages() {
        val data = UserData(
            playlists = listOf(Playlist(id = "p1", name = "Road trip", items = library.take(2))),
            favorites = library.takeLast(1),
            playCounts = mapOf(library[2].uri to 9, library[0].uri to 3),
        )
        assertEquals(listOf("Apple", "Zebra"), resolveCollection("album", "id:10", library, emptyList(), data).entries.map { it.title })
        assertEquals("Beta", resolveCollection("artist", "Beta", library, emptyList(), data).title)
        assertEquals(listOf("Loose"), resolveCollection("artist", "Unknown artist", library, emptyList(), data).entries.map { it.title })
        val playlist = resolveCollection("playlist", "p1", library, emptyList(), data)
        assertEquals("Road trip", playlist.title)
        assertEquals("p1", playlist.playlistId)
        assertEquals(listOf("Loose"), resolveCollection("liked", "-", library, emptyList(), data).entries.map { it.title })
        assertEquals(listOf("Mango", "Zebra"), resolveCollection("top", "-", library, emptyList(), data).entries.map { it.title })
    }

    @Test
    fun savedDataSurvivesARoundTripAndUnknownFields() {
        val data = UserData(playlists = listOf(Playlist(id = "p1", name = "Mix", items = library)))
        val text = AppJson.encodeToString(UserData.serializer(), data)
        assertEquals(data, AppJson.decodeFromString(UserData.serializer(), text))
        // A file written by a newer version with extra fields must still load.
        val newer = text.replaceFirst("{", "{\"futureField\":42,")
        assertEquals(data, AppJson.decodeFromString(UserData.serializer(), newer))
    }
}
