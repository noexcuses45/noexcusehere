package com.noexcusehere.mashwave.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noexcusehere.mashwave.APP_NAME
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.MediaSource
import com.noexcusehere.mashwave.data.describeUri
import com.noexcusehere.mashwave.data.source
import com.noexcusehere.mashwave.ui.LocalUi
import com.noexcusehere.mashwave.ui.components.Artwork
import com.noexcusehere.mashwave.ui.components.Chip
import com.noexcusehere.mashwave.ui.components.EmptyState
import com.noexcusehere.mashwave.ui.components.HeaderIcon
import com.noexcusehere.mashwave.ui.components.ScreenHeader
import com.noexcusehere.mashwave.ui.photoPermissions
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextMid

/**
 * The Photo editor tab: every photo on the phone in one grid. Tapping one opens it in the photo
 * editor; what is saved there is a new picture in the gallery - the original is left as it is.
 */
@Composable
fun PhotosScreen() {
    val context = LocalContext.current
    val app = context.app
    val ui = LocalUi.current
    val photos by app.library.photos.collectAsStateWithLifecycle()
    var canSee by remember { mutableStateOf(app.library.hasPhotoPermission()) }
    var partial by remember { mutableStateOf(app.library.photoAccessIsPartial()) }
    var filter by rememberSaveable { mutableStateOf("ALL") }

    val askForPhotos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        canSee = app.library.hasPhotoPermission()
        partial = app.library.photoAccessIsPartial()
        app.library.refresh()
    }

    /** A photo that came from another app's picker rather than from the grid. */
    fun open(uri: Uri?) {
        if (uri == null) return
        val entry = describeUri(context, uri, "image/*")
        ui.editingPhoto = entry.copy(isImage = true, isVideo = false, title = entry.title.ifBlank { "Photo" })
    }
    val fromGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { open(it) }
    val fromFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { open(it) }

    // The phone's own photos should simply be there: ask once, straight away, rather than
    // waiting for a tap. (If video access was already given, Android often grants this silently.)
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!canSee && !asked) {
            asked = true
            runCatching { askForPhotos.launch(photoPermissions()) }
        }
    }

    val newestFirst = remember(photos) { photos.sortedByDescending { it.dateAdded } }
    val sources = remember(newestFirst) { MediaSource.entries.filter { s -> s != MediaSource.OTHER && newestFirst.any { it.source == s } } }
    val shown = remember(newestFirst, filter) { if (filter == "ALL") newestFirst else newestFirst.filter { it.source.name == filter } }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader("Photo editor") {
            HeaderIcon(Icons.Rounded.PhotoLibrary, "Choose a photo from Google Photos or the gallery", {
                runCatching { fromGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    .onFailure { app.say("No photo picker is available on this phone") }
            })
            HeaderIcon(Icons.Rounded.CloudDownload, "Choose a photo from Google Drive or files", {
                runCatching { fromFiles.launch(arrayOf("image/*")) }
                    .onFailure { app.say("No file picker is available on this phone") }
            })
        }
        Text(
            "Tap a photo to edit it. What you save goes into Pictures › $APP_NAME as a new picture - the original stays as it is.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMid,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )

        if (!canSee) {
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Pink.copy(alpha = 0.16f))
                    .clickable { askForPhotos.launch(photoPermissions()) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Show my photos here", style = MaterialTheme.typography.titleMedium, color = TextHi)
                    Text(
                        "Allow access once and every photo on the phone - camera roll, WhatsApp, screenshots - is listed below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMid,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text("Allow", style = MaterialTheme.typography.labelLarge, color = Pink)
            }
        } else if (partial) {
            Text(
                "Only the photos you chose are shown. Tap here to choose more or allow all.",
                style = MaterialTheme.typography.bodySmall,
                color = Pink,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { askForPhotos.launch(photoPermissions()) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        if (sources.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                item { Chip("All", selected = filter == "ALL", onClick = { filter = "ALL" }) }
                items(sources, key = { it.name }) { source ->
                    Chip(source.label, selected = filter == source.name, onClick = { filter = source.name })
                }
            }
        }

        if (shown.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.PhotoFilter,
                title = if (canSee) "No photos found" else "Your photos will show here",
                message = if (canSee) {
                    "No photos were found on this phone. Use the buttons at the top to pick one from Google Photos, Drive or files."
                } else {
                    "Allow access above, or use the buttons at the top to pick a single photo."
                },
                action = if (canSee) "Look again" else null,
                onAction = if (canSee) ({ app.library.refresh() }) else null,
            )
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(shown, key = { it.uri }) { entry ->
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface2)
                        .clickable { ui.editingPhoto = entry },
                ) {
                    Artwork(entry, Modifier.fillMaxSize(), RoundedCornerShape(8.dp))
                }
            }
        }
    }
}
