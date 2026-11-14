package com.noexcusehere.mashwave.editor

import android.content.Context
import com.noexcusehere.mashwave.data.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/** The video projects the user is working on, kept in a small file so nothing is lost when the app closes. */
class EditorStore(context: Context, scope: CoroutineScope) {
    private val file = File(context.filesDir, "editor-projects.json")
    private val serializer = ListSerializer(EditProject.serializer())
    private val writes = Channel<List<EditProject>>(Channel.CONFLATED)

    private val _projects = MutableStateFlow(load())

    /** Newest first. */
    val projects: StateFlow<List<EditProject>> = _projects

    init {
        scope.launch(Dispatchers.IO) {
            for (snapshot in writes) {
                runCatching {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(AppJson.encodeToString(serializer, snapshot))
                    if (!tmp.renameTo(file)) {
                        file.writeText(tmp.readText())
                        tmp.delete()
                    }
                }
            }
        }
    }

    private fun load(): List<EditProject> = runCatching {
        if (file.exists()) AppJson.decodeFromString(serializer, file.readText()) else emptyList()
    }.getOrElse { emptyList() }

    fun get(id: String): EditProject? = _projects.value.firstOrNull { it.id == id }

    fun create(name: String): EditProject {
        val project = EditProject(id = newId(), name = name, updatedAt = System.currentTimeMillis())
        save(project)
        return project
    }

    /** Adds the project, or replaces the saved copy with the same id, and moves it to the top. */
    fun save(project: EditProject) {
        val stamped = project.copy(updatedAt = System.currentTimeMillis())
        _projects.update { list -> listOf(stamped) + list.filterNot { it.id == project.id } }
        writes.trySend(_projects.value)
    }

    fun rename(id: String, name: String) {
        _projects.update { list -> list.map { if (it.id == id) it.copy(name = name) else it } }
        writes.trySend(_projects.value)
    }

    fun duplicate(id: String): EditProject? {
        val original = get(id) ?: return null
        val copy = original.copy(id = newId(), name = original.name + " copy")
        save(copy)
        return copy
    }

    fun delete(id: String) {
        _projects.update { list -> list.filterNot { it.id == id } }
        writes.trySend(_projects.value)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().take(12)
    }
}
