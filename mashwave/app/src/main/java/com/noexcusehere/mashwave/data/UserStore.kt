package com.noexcusehere.mashwave.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
}

/**
 * Everything the user creates - playlists, likes, history, saved mashups, sound settings -
 * kept in one small JSON file inside the app's private storage.
 */
class UserStore(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "mashwave-user.json")
    private val queueFile = File(context.filesDir, "mashwave-queue.json")
    private val writes = Channel<UserData>(Channel.CONFLATED)
    private val queueWrites = Channel<SavedQueue>(Channel.CONFLATED)

    private val _data = MutableStateFlow(load())
    val data: StateFlow<UserData> = _data

    init {
        scope.launch(Dispatchers.IO) {
            for (snapshot in writes) {
                runCatching { writeAtomic(file, AppJson.encodeToString(UserData.serializer(), snapshot)) }
            }
        }
        scope.launch(Dispatchers.IO) {
            for (snapshot in queueWrites) {
                runCatching { writeAtomic(queueFile, AppJson.encodeToString(SavedQueue.serializer(), snapshot)) }
            }
        }
    }

    private fun load(): UserData = runCatching {
        if (file.exists()) AppJson.decodeFromString(UserData.serializer(), file.readText()) else UserData()
    }.getOrElse { UserData() }

    private fun edit(block: (UserData) -> UserData) {
        _data.update(block)
        writes.trySend(_data.value)
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    // ---- likes -------------------------------------------------------------------------

    fun isFavorite(uri: String): Boolean = _data.value.favorites.any { it.uri == uri }

    fun toggleFavorite(entry: MediaEntry) = edit { d ->
        if (d.favorites.any { it.uri == entry.uri }) {
            d.copy(favorites = d.favorites.filterNot { it.uri == entry.uri })
        } else {
            d.copy(favorites = listOf(entry) + d.favorites)
        }
    }

    // ---- history -----------------------------------------------------------------------

    fun recordPlay(entry: MediaEntry) = edit { d ->
        d.copy(
            history = (listOf(entry) + d.history.filterNot { it.uri == entry.uri }).take(60),
            playCounts = d.playCounts + (entry.uri to ((d.playCounts[entry.uri] ?: 0) + 1)),
        )
    }

    fun clearHistory() = edit { it.copy(history = emptyList(), playCounts = emptyMap()) }

    // ---- playlists ---------------------------------------------------------------------

    /** [video] makes it a watch list instead of a music playlist. */
    fun createPlaylist(name: String, items: List<MediaEntry> = emptyList(), video: Boolean = false): Playlist {
        val now = System.currentTimeMillis()
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { if (video) "My watch list" else "My playlist" },
            items = items.distinctBy { it.uri },
            createdAt = now,
            updatedAt = now,
            video = video,
        )
        edit { it.copy(playlists = listOf(playlist) + it.playlists) }
        return playlist
    }

    fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name.trim().ifBlank { it.name }) }

    fun deletePlaylist(id: String) = edit { d -> d.copy(playlists = d.playlists.filterNot { it.id == id }) }

    /** Adds entries that are not already in the playlist. Returns how many were added. */
    fun addToPlaylist(id: String, entries: List<MediaEntry>): Int {
        var added = 0
        editPlaylist(id) { p ->
            val existing = p.items.mapTo(HashSet()) { it.uri }
            val fresh = entries.filter { existing.add(it.uri) }
            added = fresh.size
            p.copy(items = p.items + fresh)
        }
        return added
    }

    fun removeFromPlaylist(id: String, uri: String) = editPlaylist(id) { p ->
        p.copy(items = p.items.filterNot { it.uri == uri })
    }

    fun moveInPlaylist(id: String, from: Int, to: Int) = editPlaylist(id) { p ->
        if (from !in p.items.indices || to !in p.items.indices || from == to) {
            p
        } else {
            val list = p.items.toMutableList()
            list.add(to, list.removeAt(from))
            p.copy(items = list)
        }
    }

    private fun editPlaylist(id: String, block: (Playlist) -> Playlist) = edit { d ->
        d.copy(
            playlists = d.playlists.map {
                if (it.id == id) block(it).copy(updatedAt = System.currentTimeMillis()) else it
            },
        )
    }

    // ---- things added from Google Drive, Google Photos and other apps ---------------------

    /** Remembers picked files so they stay in the library. Returns how many were new. */
    fun addImported(entries: List<MediaEntry>): Int {
        var added = 0
        edit { d ->
            val existing = d.imported.mapTo(HashSet()) { it.uri }
            val fresh = entries.filter { existing.add(it.uri) }
            added = fresh.size
            d.copy(imported = fresh + d.imported)
        }
        return added
    }

    fun removeImported(uri: String) = edit { d -> d.copy(imported = d.imported.filterNot { it.uri == uri }) }

    fun isImported(uri: String): Boolean = _data.value.imported.any { it.uri == uri }

    // ---- mashups -----------------------------------------------------------------------

    fun saveMashup(mashup: Mashup) = edit { d ->
        d.copy(mashups = listOf(mashup) + d.mashups.filterNot { it.id == mashup.id })
    }

    fun deleteMashup(id: String) = edit { d -> d.copy(mashups = d.mashups.filterNot { it.id == id }) }

    fun addAutoMashup(mashup: AutoMashup) = edit { d ->
        d.copy(autoMashups = listOf(mashup) + d.autoMashups.filterNot { it.id == mashup.id })
    }

    fun deleteAutoMashup(id: String) = edit { d -> d.copy(autoMashups = d.autoMashups.filterNot { it.id == id }) }

    // ---- settings ----------------------------------------------------------------------

    fun setFx(fx: FxSettings) = edit { it.copy(fx = fx) }

    fun setSongSort(sort: String) = edit { it.copy(songSort = sort) }

    fun setOnboarded() = edit { it.copy(onboarded = true) }

    // ---- last queue (so the app reopens where you left off) --------------------------

    fun saveQueue(queue: SavedQueue) {
        queueWrites.trySend(queue)
    }

    fun loadQueue(): SavedQueue? = runCatching {
        if (queueFile.exists()) AppJson.decodeFromString(SavedQueue.serializer(), queueFile.readText()) else null
    }.getOrNull()
}
