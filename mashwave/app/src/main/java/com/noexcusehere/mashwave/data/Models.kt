package com.noexcusehere.mashwave.data

import android.net.Uri
import kotlinx.serialization.Serializable

/**
 * One playable thing: a song, a video, a file picked from storage or a network stream.
 * [uri] is both the playback address and the identity used everywhere (playlists, likes, history).
 */
@Serializable
data class MediaEntry(
    val uri: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val albumId: Long = 0,
    val durationMs: Long = 0,
    val isVideo: Boolean = false,
    val folder: String = "",
    val dateAdded: Long = 0,
    val size: Long = 0,
    val track: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
) {
    val isStream: Boolean
        get() = uri.startsWith("http://") || uri.startsWith("https://") || uri.startsWith("rtsp://")

    val subtitle: String
        get() = when {
            isStream -> "Network stream"
            isVideo -> "Video"
            artist.isNotBlank() -> artist
            else -> "Unknown artist"
        }

    /** Album-art address understood by the system media controls (lock screen, notification). */
    val artworkUri: Uri?
        get() = if (!isVideo && albumId > 0) {
            Uri.parse("content://media/external/audio/albumart/$albumId")
        } else {
            null
        }

    /** Songs from one album share artwork, so they share a cache slot. */
    val artKey: String
        get() = if (!isVideo && albumId > 0) "album:$albumId" else uri

    val folderName: String
        get() = folder.trimEnd('/').substringAfterLast('/').ifBlank { "Internal storage" }
}

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val items: List<MediaEntry> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** A saved two-deck mix: which songs, and how each deck was tuned. */
@Serializable
data class Mashup(
    val id: String,
    val name: String,
    val a: MediaEntry,
    val b: MediaEntry,
    val tempoA: Float = 1f,
    val tempoB: Float = 1f,
    val pitchA: Float = 0f,
    val pitchB: Float = 0f,
    val volumeA: Float = 1f,
    val volumeB: Float = 1f,
    val crossfade: Float = 0.5f,
    val startA: Long = 0,
    val startB: Long = 0,
    val bpmA: Float = 0f,
    val bpmB: Float = 0f,
    val createdAt: Long = 0,
)

/** A finished automatic mashup: one audio file made from several songs. */
@Serializable
data class AutoMashup(
    val id: String,
    val name: String,
    /** The rendered file, playable like any other song. */
    val entry: MediaEntry,
    val sources: List<MediaEntry> = emptyList(),
    val tempo: Float = 0f,
    val createdAt: Long = 0,
)

@Serializable
data class FxSettings(
    val enabled: Boolean = false,
    val preset: Int = -1,
    val bandLevels: List<Int> = emptyList(),
    val bass: Int = 0,
    val virtualizer: Int = 0,
)

@Serializable
data class UserData(
    val playlists: List<Playlist> = emptyList(),
    val favorites: List<MediaEntry> = emptyList(),
    val history: List<MediaEntry> = emptyList(),
    val playCounts: Map<String, Int> = emptyMap(),
    val mashups: List<Mashup> = emptyList(),
    val autoMashups: List<AutoMashup> = emptyList(),
    val fx: FxSettings = FxSettings(),
    val songSort: String = "TITLE",
    val onboarded: Boolean = false,
)

@Serializable
data class SavedQueue(
    val items: List<MediaEntry> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
)

data class Album(
    val key: String,
    val title: String,
    val artist: String,
    val songs: List<MediaEntry>,
)

data class Artist(
    val name: String,
    val songs: List<MediaEntry>,
    val albumCount: Int,
)

data class Folder(
    val path: String,
    val name: String,
    val items: List<MediaEntry>,
)

fun List<MediaEntry>.toAlbums(): List<Album> =
    filter { !it.isVideo }
        .groupBy { if (it.albumId > 0) "id:${it.albumId}" else "name:${it.album}" }
        .map { (key, songs) ->
            val sorted = songs.sortedWith(compareBy({ it.track }, { it.title.lowercase() }))
            val artists = songs.map { it.artist }.filter { it.isNotBlank() }.distinct()
            Album(
                key = key,
                title = songs.first().album.ifBlank { "Unknown album" },
                artist = when (artists.size) {
                    0 -> "Unknown artist"
                    1 -> artists.first()
                    else -> "Various artists"
                },
                songs = sorted,
            )
        }
        .sortedBy { it.title.lowercase() }

fun List<MediaEntry>.toArtists(): List<Artist> =
    filter { !it.isVideo }
        .groupBy { it.artist.ifBlank { "Unknown artist" } }
        .map { (name, songs) ->
            Artist(
                name = name,
                songs = songs.sortedWith(compareBy({ it.album.lowercase() }, { it.track }, { it.title.lowercase() })),
                albumCount = songs.map { it.album }.distinct().size,
            )
        }
        .sortedBy { it.name.lowercase() }

fun List<MediaEntry>.toFolders(): List<Folder> =
    groupBy { it.folder }
        .map { (path, items) ->
            Folder(path = path, name = items.first().folderName, items = items.sortedBy { it.title.lowercase() })
        }
        .sortedBy { it.name.lowercase() }

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatTotalDuration(ms: Long): String {
    val minutes = ms / 60000
    return when {
        minutes < 1 -> "under a minute"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} hr ${minutes % 60} min"
    }
}

fun countLabel(items: List<MediaEntry>): String {
    val videos = items.count { it.isVideo }
    val songs = items.size - videos
    val parts = mutableListOf<String>()
    if (songs > 0) parts += if (songs == 1) "1 song" else "$songs songs"
    if (videos > 0) parts += if (videos == 1) "1 video" else "$videos videos"
    if (parts.isEmpty()) return "Empty"
    val total = items.sumOf { it.durationMs }
    return parts.joinToString(", ") + if (total > 0) " · " + formatTotalDuration(total) else ""
}
