package com.noexcusehere.mashwave.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.CLOUD_FOLDER
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.data.describeUri
import com.noexcusehere.mashwave.data.providerLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImportKind { MUSIC, VIDEO, ANY }

/**
 * Adds songs and videos from Google Drive, Google Photos and any other app that plugs into
 * Android's own pickers. What is picked stays in the library until it is removed.
 */
class MediaImporter(
    /** Opens the file picker (Google Drive, OneDrive, Downloads, ...). */
    val fromFiles: (ImportKind) -> Unit,
    /** Opens the photo picker, which also shows videos kept in Google Photos. */
    val fromPhotos: () -> Unit,
)

/** The result of keeping a batch of picked files. */
class Picked(val kept: List<MediaEntry>, val playOnly: List<MediaEntry>, val skipped: Int, val full: Boolean)

/**
 * Asks Android to keep access to each picked file and describes it for the library.
 * Android only lets an app hold on to a limited number of picked files, so the list is capped.
 */
fun keepPicked(context: Context, uris: List<Uri>): Picked {
    val resolver = context.contentResolver
    val limit = if (Build.VERSION.SDK_INT >= 30) 450 else 110
    var held = runCatching { resolver.persistedUriPermissions.size }.getOrDefault(0)
    val kept = ArrayList<MediaEntry>()
    val playOnly = ArrayList<MediaEntry>()
    var skipped = 0
    var full = false
    val now = System.currentTimeMillis() / 1000
    for (uri in uris) {
        val entry = describeUri(context, uri)
        if (entry.isImage) {
            skipped++
            continue
        }
        val labelled = entry.copy(folder = CLOUD_FOLDER + providerLabel(uri), dateAdded = now)
        val canKeep = held < limit &&
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        if (canKeep) {
            held++
            kept += labelled
        } else {
            if (held >= limit) full = true
            playOnly += labelled
        }
    }
    return Picked(kept, playOnly, skipped, full)
}

@Composable
fun rememberMediaImporter(): MediaImporter {
    val context = LocalContext.current
    val app = context.app
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()

    fun handle(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val picked = withContext(Dispatchers.IO) { keepPicked(context, uris) }
            val added = app.store.addImported(picked.kept)
            val all = picked.kept + picked.playOnly
            if (all.isEmpty()) {
                app.say("Nothing playable was picked")
                return@launch
            }
            val from = all.first().folderName
            app.say(
                when {
                    picked.full -> "Android only lets an app keep a few hundred picked files. The extra ones play now but won't stay."
                    picked.playOnly.isNotEmpty() -> "$from won't let these stay in the library, but they can play now"
                    added == 0 -> "Already in your library"
                    added == 1 -> "Added to your library from $from"
                    else -> "Added $added to your library from $from"
                },
            )
            ui.play(app.player, all, 0)
            if (!all.first().isVideo) ui.nowPlayingOpen = !app.cast.isConnected
        }
    }

    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { handle(it) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(60)) { handle(it) }
    return MediaImporter(
        fromFiles = { kind ->
            val types = when (kind) {
                ImportKind.MUSIC -> arrayOf("audio/*", "application/ogg")
                ImportKind.VIDEO -> arrayOf("video/*", "application/x-matroska")
                ImportKind.ANY -> arrayOf("audio/*", "video/*", "application/ogg", "application/x-matroska")
            }
            runCatching { files.launch(types) }.onFailure { app.say("No file picker is available on this phone") }
        },
        fromPhotos = {
            runCatching { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
                .onFailure { app.say("No photo picker is available on this phone") }
        },
    )
}
