package com.noexcusehere.vpn.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One saved server: a display name plus the WireGuard configuration text. */
data class Profile(
    val id: String,
    val name: String,
    val configText: String,
    /** "host:port" of the server, cached at save time for display. */
    val endpoint: String? = null,
)

data class ProfileList(
    val profiles: List<Profile> = emptyList(),
    val selectedId: String? = null,
) {
    val selected: Profile? get() = profiles.firstOrNull { it.id == selectedId }

    operator fun get(id: String): Profile? = profiles.firstOrNull { it.id == id }
}

/**
 * Saved servers, kept in one JSON file in the app's private storage. The file contains
 * private keys, so it lives where only this app can read it and is excluded from backups.
 *
 * Writes go to disk before the in-memory state changes, so a failed write never leaves the
 * UI showing something that was not saved.
 */
class ProfileStore(private val file: File) {

    private val _state = MutableStateFlow(read())
    val state: StateFlow<ProfileList> = _state.asStateFlow()

    /** Adds the profile, or replaces the one with the same id. The first profile becomes selected. */
    @Synchronized
    @Throws(IOException::class)
    fun save(profile: Profile) = update { current ->
        val exists = current.profiles.any { it.id == profile.id }
        val profiles = if (exists) {
            current.profiles.map { if (it.id == profile.id) profile else it }
        } else {
            current.profiles + profile
        }
        current.copy(profiles = profiles, selectedId = current.selectedId ?: profile.id)
    }

    @Synchronized
    @Throws(IOException::class)
    fun delete(id: String) = update { current ->
        val profiles = current.profiles.filterNot { it.id == id }
        val selectedId = if (current.selectedId == id) profiles.firstOrNull()?.id else current.selectedId
        current.copy(profiles = profiles, selectedId = selectedId)
    }

    @Synchronized
    @Throws(IOException::class)
    fun select(id: String) = update { current ->
        if (current[id] == null) current else current.copy(selectedId = id)
    }

    private fun update(transform: (ProfileList) -> ProfileList) {
        val current = _state.value
        val next = transform(current)
        if (next == current) return
        write(next)
        _state.value = next
    }

    private fun read(): ProfileList {
        if (!file.exists()) return ProfileList()
        return try {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray(KEY_PROFILES) ?: JSONArray()
            val profiles = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Profile(
                    id = item.getString(KEY_ID),
                    name = item.getString(KEY_NAME),
                    configText = item.getString(KEY_CONFIG),
                    endpoint = item.optString(KEY_ENDPOINT).takeIf { it.isNotEmpty() },
                )
            }
            val selected = root.optString(KEY_SELECTED).takeIf { id -> profiles.any { it.id == id } }
            ProfileList(profiles, selected ?: profiles.firstOrNull()?.id)
        } catch (e: JSONException) {
            keepUnreadableFile()
            ProfileList()
        } catch (e: IOException) {
            ProfileList()
        }
    }

    /** A file we can't parse is set aside rather than overwritten, so nothing is lost silently. */
    private fun keepUnreadableFile() {
        file.renameTo(File(file.parentFile, file.name + ".unreadable"))
    }

    private fun write(list: ProfileList) {
        val root = JSONObject()
        root.put(KEY_VERSION, 1)
        list.selectedId?.let { root.put(KEY_SELECTED, it) }
        val array = JSONArray()
        for (profile in list.profiles) {
            val item = JSONObject()
            item.put(KEY_ID, profile.id)
            item.put(KEY_NAME, profile.name)
            item.put(KEY_CONFIG, profile.configText)
            profile.endpoint?.let { item.put(KEY_ENDPOINT, it) }
            array.put(item)
        }
        root.put(KEY_PROFILES, array)

        // Write a sibling file and rename it over the original so a crash mid-write can't
        // leave a half-written list behind.
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(root.toString())
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("Could not replace ${file.name}")
        }
    }

    private companion object {
        const val KEY_VERSION = "version"
        const val KEY_SELECTED = "selected"
        const val KEY_PROFILES = "profiles"
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_CONFIG = "config"
        const val KEY_ENDPOINT = "endpoint"
    }
}
