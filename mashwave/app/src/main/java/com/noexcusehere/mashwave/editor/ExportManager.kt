package com.noexcusehere.mashwave.editor

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.noexcusehere.mashwave.APP_NAME
import com.noexcusehere.mashwave.KeepAliveService
import com.noexcusehere.mashwave.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class ExportState(
    val running: Boolean = false,
    val projectId: String? = null,
    val name: String = "",
    /** 0..1 while the video is being made. */
    val progress: Float = 0f,
    /** Address of the finished video, once it is in the phone's library. */
    val savedUri: String? = null,
    /** Plain words for where it went, e.g. "Movies › Ultimate Beats". */
    val savedWhere: String? = null,
    val error: String? = null,
)

/** Saves a project as a finished MP4 in the phone's video library. One video at a time. */
class ExportManager(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(ExportState())
    val state: StateFlow<ExportState> = _state

    private val exporter = VideoExporter(context)
    private var workFile: File? = null

    /** [shortSide] is the quality: 480, 720 or 1080. */
    fun start(project: EditProject, shortSide: Int) {
        if (_state.value.running || project.clips.isEmpty()) return
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "video-${System.currentTimeMillis()}.mp4")
        workFile = file
        _state.value = ExportState(running = true, projectId = project.id, name = project.name)
        exporter.start(
            project = project,
            shortSide = shortSide,
            output = file,
            onProgress = { p -> _state.update { if (it.running) it.copy(progress = maxOf(it.progress, p * 0.97f)) else it } },
            onDone = { result, error ->
                if (error != null || result == null) {
                    Log.w("UltimateBeats", "Video export failed", error)
                    fail(describe(error))
                } else {
                    scope.launch {
                        val saved = runCatching { withContext(Dispatchers.IO) { publish(file, project.name) } }
                        file.delete()
                        saved.onSuccess { (uri, where) ->
                            _state.value = ExportState(
                                projectId = project.id,
                                name = project.name,
                                progress = 1f,
                                savedUri = uri,
                                savedWhere = where,
                            )
                            context.app.library.refresh()
                        }.onFailure { e ->
                            Log.w("UltimateBeats", "Saving the exported video failed", e)
                            fail("The video was made but could not be stored: ${e.message ?: "unknown problem"}")
                        }
                        KeepAliveService.refresh(context)
                    }
                }
            },
        )
        // Started last, once the heavy set-up above is out of the way.
        if (_state.value.running) KeepAliveService.refresh(context)
    }

    private fun fail(message: String) {
        workFile?.delete()
        _state.update { ExportState(projectId = it.projectId, name = it.name, error = message) }
        KeepAliveService.refresh(context)
    }

    private fun describe(error: Exception?): String {
        val text = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return when {
            text.contains("no output sample written", true) ->
                "The phone's video encoder stopped responding. Try a lower quality, or remove the last thing you added."
            text.contains("ENOSPC", true) || text.contains("No space", true) -> "The phone is out of storage space."
            text.contains("FileNotFound", true) || text.contains("ENOENT", true) ->
                "One of the clips can no longer be found. It may have been deleted or moved."
            text.contains("decod", true) -> "One of the clips is in a format this phone cannot edit."
            else -> "Something went wrong while making the video. Try a lower quality."
        }
    }

    fun cancel() {
        if (!_state.value.running) return
        exporter.cancel()
        workFile?.delete()
        _state.value = ExportState()
        KeepAliveService.refresh(context)
    }

    /** Clears the "saved" or "failed" message once the user has seen it. */
    fun dismiss() {
        if (!_state.value.running) _state.value = ExportState()
    }

    /** Copies the finished file into the phone's shared video library. Returns its address and a plain description. */
    private fun publish(file: File, name: String): Pair<String, String> {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().ifBlank { "Video" }
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$clean.mp4")
                put(MediaStore.Video.Media.TITLE, clean)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + APP_NAME)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val item = resolver.insert(collection, values) ?: throw IOException("could not create the file")
            try {
                resolver.openOutputStream(item)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("could not open the file")
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(item, values, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(item, null, null) }
                throw e
            }
            val id = ContentUris.parseId(item)
            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
            return uri.toString() to "Movies › $APP_NAME"
        }
        // Older Android: keep it in the app's own movies folder (no extra permission needed).
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, APP_NAME)
        dir.mkdirs()
        var target = File(dir, "$clean.mp4")
        var n = 2
        while (target.exists()) target = File(dir, "$clean ($n).mp4").also { n++ }
        file.copyTo(target, overwrite = true)
        return Uri.fromFile(target).toString() to "the app's Movies folder"
    }
}
