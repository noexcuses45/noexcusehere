package com.noexcusehere.mashwave.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.data.MediaSource
import com.noexcusehere.mashwave.data.source
import com.noexcusehere.mashwave.data.describeUri
import com.noexcusehere.mashwave.data.formatDuration
import com.noexcusehere.mashwave.editor.ClipFilter
import com.noexcusehere.mashwave.editor.EditClip
import com.noexcusehere.mashwave.editor.EditMusic
import com.noexcusehere.mashwave.editor.EditProject
import com.noexcusehere.mashwave.editor.EditText
import com.noexcusehere.mashwave.editor.EditorOps
import com.noexcusehere.mashwave.editor.EditorStore
import com.noexcusehere.mashwave.editor.ExportState
import com.noexcusehere.mashwave.editor.FrameCache
import com.noexcusehere.mashwave.ui.MwSheet
import com.noexcusehere.mashwave.ui.photoPermissions
import com.noexcusehere.mashwave.ui.SongPickerSheet
import com.noexcusehere.mashwave.ui.components.Artwork
import com.noexcusehere.mashwave.ui.components.Chip
import com.noexcusehere.mashwave.ui.components.MwSlider
import com.noexcusehere.mashwave.ui.components.PillButton
import com.noexcusehere.mashwave.ui.theme.BrandGradient
import com.noexcusehere.mashwave.ui.theme.Cyan
import com.noexcusehere.mashwave.ui.theme.Outline
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface1
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.Surface3
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid
import com.noexcusehere.mashwave.ui.theme.Violet
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
private fun SheetTitle(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = TextHi)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMid)
        }
        trailing?.invoke()
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit,
    mark: Float = -1f,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.padding(horizontal = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = TextMid, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = TextHi)
        }
        MwSlider(value = value, onValueChange = onValueChange, onValueChangeFinished = onFinished, mark = mark, fillFromMark = mark >= 0f)
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

// ---- adding clips ----------------------------------------------------------------------------

/**
 * Everything that can go on the timeline, in one place: the photos and videos on the phone
 * (grouped by where they came from - camera, WhatsApp, Messenger ...), plus buttons that reach
 * into Google Photos and Google Drive.
 */
@Composable
fun ClipPickerSheet(onDismiss: () -> Unit, onPickedEntries: (List<MediaEntry>) -> Unit, onPickedUris: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val videos by app.library.videos.collectAsStateWithLifecycle()
    val photos by app.library.photos.collectAsStateWithLifecycle()
    val selected = remember { mutableStateListOf<MediaEntry>() }
    var filter by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }
    var canSeePhotos by remember { mutableStateOf(app.library.hasPhotoPermission()) }
    var partial by remember { mutableStateOf(app.library.photoAccessIsPartial()) }

    val askForPhotos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        canSeePhotos = app.library.hasPhotoPermission()
        partial = app.library.photoAccessIsPartial()
        app.library.refresh()
    }
    fun picked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        // Ask to keep access so the project still opens tomorrow.
        uris.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        onPickedUris(uris)
        onDismiss()
    }
    val fromPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(40)) { picked(it) }
    val fromFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked(it) }

    // The phone's own photos should simply be there: ask for access straight away, once, rather
    // than waiting for a tap. (If video access was already given, Android grants this silently.)
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!canSeePhotos && !asked) {
            asked = true
            runCatching { askForPhotos.launch(photoPermissions()) }
        }
    }

    val all = remember(videos, photos) { (videos + photos).sortedByDescending { it.dateAdded } }
    val sources = remember(all) { MediaSource.entries.filter { s -> s != MediaSource.OTHER && all.any { it.source == s } } }
    val shown = remember(all, filter, query) {
        val q = query.trim()
        all.filter { entry ->
            val inGroup = when (filter) {
                "ALL" -> true
                "PHOTOS" -> entry.isImage
                "VIDEOS" -> entry.isVideo
                else -> entry.source.name == filter
            }
            inGroup && (q.isEmpty() || entry.title.contains(q, true) || entry.folder.contains(q, true))
        }
    }

    MwSheet(onDismiss) {
        SheetTitle("Add clips", "Every photo and video on this phone is below") {
            PillButton(
                text = if (selected.isEmpty()) "Done" else "Add ${selected.size}",
                onClick = {
                    if (selected.isNotEmpty()) onPickedEntries(selected.toList())
                    onDismiss()
                },
                filled = selected.isNotEmpty(),
            )
        }
        Text(
            "Only online, not saved on this phone? Get it from:",
            style = MaterialTheme.typography.bodySmall,
            color = TextMid,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SourceButton(Icons.Rounded.PhotoLibrary, "Google Photos", Modifier.weight(1f)) {
                runCatching { fromPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                    .onFailure { app.say("No photo picker is available on this phone") }
            }
            SourceButton(Icons.Rounded.CloudDownload, "Google Drive / files", Modifier.weight(1f)) {
                runCatching { fromFiles.launch(arrayOf("image/*", "video/*")) }
                    .onFailure { app.say("No file picker is available on this phone") }
            }
        }
        if (!canSeePhotos) {
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
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
                        "Allow access once and every photo on the phone - camera roll, WhatsApp, saved Messenger pictures - is listed below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMid,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text("Allow", style = MaterialTheme.typography.labelLarge, color = Pink)
            }
        } else if (partial) {
            Text(
                "Only the photos and videos you chose are shown. Tap here to choose more or allow all.",
                style = MaterialTheme.typography.bodySmall,
                color = Pink,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { askForPhotos.launch(photoPermissions()) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        ChipRow {
            Chip("All", selected = filter == "ALL", onClick = { filter = "ALL" })
            Chip("Photos", selected = filter == "PHOTOS", onClick = { filter = "PHOTOS" })
            Chip("Videos", selected = filter == "VIDEOS", onClick = { filter = "VIDEOS" })
            sources.forEach { source ->
                Chip(source.label, selected = filter == source.name, onClick = { filter = source.name })
            }
        }
        TextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search by name or folder", color = TextLow) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = TextMid) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Surface2,
                unfocusedContainerColor = Surface2,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Pink,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (shown.isEmpty()) {
            Text(
                when {
                    all.isEmpty() && !canSeePhotos -> "Nothing to show yet. Allow access above, or use the Google Photos and Google Drive buttons."
                    all.isEmpty() -> "No photos or videos were found on this phone. Use the Google Photos and Google Drive buttons above."
                    else -> "Nothing here matches."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextMid,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.height(340.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(shown, key = { it.uri }) { entry ->
                val order = selected.indexOfFirst { it.uri == entry.uri }
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface2)
                        .then(if (order >= 0) Modifier.border(2.5.dp, Pink, RoundedCornerShape(8.dp)) else Modifier)
                        .clickable { if (order >= 0) selected.removeAt(order) else selected.add(entry) },
                ) {
                    Artwork(entry, Modifier.fillMaxSize(), RoundedCornerShape(8.dp))
                    if (entry.isVideo) {
                        Text(
                            if (entry.durationMs > 0) formatDuration(entry.durationMs) else "Video",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.Black.copy(alpha = 0.65f))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                    if (order >= 0) {
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(BrandGradient),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${order + 1}", style = MaterialTheme.typography.labelLarge, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Pink, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- music -----------------------------------------------------------------------------------

@Composable
fun MusicSheet(project: EditProject, onChange: (EditProject) -> Unit, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(project.music == null) }
    var chose by remember { mutableStateOf(false) }
    val music = project.music
    if (picking) {
        SongPickerSheet(
            title = "Music for this video",
            multiSelect = false,
            onDismiss = {
                picking = false
                // Closed without choosing and there is no music to adjust: nothing more to show.
                if (!chose && project.music == null) onDismiss()
            },
            onPicked = { picked ->
                picked.firstOrNull()?.let {
                    chose = true
                    onChange(EditorOps.setMusic(project, EditMusic(it, 0, project.music?.volume ?: 0.8f)))
                }
            },
        )
        return
    }
    if (music == null) {
        // The song was removed (or undone) while this panel was open.
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    MwSheet(onDismiss) {
        SheetTitle("Music", "Plays under the whole video and repeats if the video is longer")
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Surface2)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(music.entry, Modifier.size(46.dp), RoundedCornerShape(8.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(music.entry.title, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(music.entry.subtitle, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1)
            }
        }
        var volume by remember(music.entry.uri) { mutableFloatStateOf(music.volume) }
        LabeledSlider(
            label = "Music volume",
            valueText = "${(volume * 100).roundToInt()}%",
            value = volume / 1.5f,
            onValueChange = { volume = (it * 1.5f * 20).roundToInt() / 20f },
            onFinished = { onChange(EditorOps.setMusic(project, music.copy(volume = volume))) },
        )
        val length = music.entry.durationMs
        if (length > 5000) {
            var offset by remember(music.entry.uri) { mutableLongStateOf(music.offsetMs) }
            LabeledSlider(
                label = "Start the song at",
                valueText = formatDuration(offset),
                value = offset.toFloat() / (length - 2000),
                onValueChange = { offset = ((it * (length - 2000)) / 500).roundToLong() * 500 },
                onFinished = { onChange(EditorOps.setMusic(project, music.copy(offsetMs = offset))) },
            )
        }
        Text(
            "Tip: turn a clip's own sound down with the Volume tool so the music stands out.",
            style = MaterialTheme.typography.bodySmall,
            color = TextLow,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Change song", onClick = { picking = true }, icon = Icons.Rounded.MusicNote)
            PillButton(
                "Remove",
                onClick = {
                    onChange(EditorOps.setMusic(project, null))
                    onDismiss()
                },
                icon = Icons.Rounded.DeleteOutline,
                tint = Pink,
            )
        }
    }
}

// ---- captions and emoji ----------------------------------------------------------------------

private val textColors = listOf(0xFFFFFFFF, 0xFF111119, 0xFFFF4D9D, 0xFF8B5CF6, 0xFF22D3EE, 0xFFFFB648, 0xFFFF5252, 0xFF4ADE80)

@Composable
fun TextSheet(project: EditProject, existing: EditText?, playheadMs: Long, onChange: (EditProject) -> Unit, onDismiss: () -> Unit) {
    val total = project.durationMs
    val id = remember { existing?.id ?: EditorStore.newId() }
    val start = remember { existing?.startMs ?: playheadMs.coerceIn(0, (total - EditorOps.MIN_TEXT_MS).coerceAtLeast(0)) }
    var words by remember { mutableStateOf(existing?.text ?: "") }
    var color by remember { mutableLongStateOf(existing?.color ?: 0xFFFFFFFF) }
    var size by remember { mutableFloatStateOf(existing?.size ?: 0.075f) }
    var x by remember { mutableFloatStateOf(existing?.x ?: 0f) }
    var y by remember { mutableFloatStateOf(existing?.y ?: -0.72f) }
    var startMs by remember { mutableLongStateOf(start) }
    var lengthMs by remember { mutableLongStateOf(existing?.let { it.endMs - it.startMs } ?: minOf(3000L, (total - start).coerceAtLeast(EditorOps.MIN_TEXT_MS))) }
    var added by remember { mutableStateOf(existing != null) }

    // Every change shows up in the preview straight away.
    fun push() {
        if (words.isBlank()) return
        val item = EditText(id, words.trim(), startMs, startMs + lengthMs, x, y, size, color)
        onChange(if (added) EditorOps.updateText(project, id) { item } else EditorOps.addText(project, item))
        added = true
    }

    MwSheet(onDismiss = {
        push()
        onDismiss()
    }) {
        SheetTitle(if (existing == null) "Add text" else "Edit text") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (added) {
                    PillButton(
                        "Delete",
                        onClick = {
                            onChange(EditorOps.removeText(project, id))
                            onDismiss()
                        },
                        tint = Pink,
                    )
                }
                PillButton(
                    "Done",
                    onClick = {
                        push()
                        onDismiss()
                    },
                    filled = words.isNotBlank(),
                )
            }
        }
        TextField(
            value = words,
            onValueChange = { words = it.take(120) },
            placeholder = { Text("Type here", color = TextLow) },
            maxLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Surface2,
                unfocusedContainerColor = Surface2,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Pink,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            textColors.forEach { option ->
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(option))
                        .border(if (option == color) 3.dp else 1.dp, if (option == color) Pink else Outline, CircleShape)
                        .clickable {
                            color = option
                            push()
                        },
                )
            }
        }
        LabeledSlider(
            label = "Size",
            valueText = "${(size * 100).roundToInt()}",
            value = (size - 0.03f) / 0.32f,
            onValueChange = { size = 0.03f + it * 0.32f },
            onFinished = { push() },
        )
        // Nine places on the picture.
        Row(Modifier.padding(horizontal = 22.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Position", style = MaterialTheme.typography.labelLarge, color = TextMid, modifier = Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (row in listOf(0.72f, 0f, -0.72f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (column in listOf(-0.6f, 0f, 0.6f)) {
                            val here = abs(x - column) < 0.05f && abs(y - row) < 0.05f
                            Box(
                                Modifier
                                    .size(width = 38.dp, height = 24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .then(if (here) Modifier.background(BrandGradient) else Modifier.background(Surface3))
                                    .clickable {
                                        x = column
                                        y = row
                                        push()
                                    },
                            )
                        }
                    }
                }
            }
        }
        if (total > EditorOps.MIN_TEXT_MS) {
            LabeledSlider(
                label = "Appears at",
                valueText = formatEditTime(startMs),
                value = startMs.toFloat() / (total - EditorOps.MIN_TEXT_MS),
                onValueChange = {
                    startMs = ((it * (total - EditorOps.MIN_TEXT_MS)) / 100).roundToLong() * 100
                    lengthMs = lengthMs.coerceAtMost((total - startMs).coerceAtLeast(EditorOps.MIN_TEXT_MS))
                },
                onFinished = { push() },
            )
            val longest = (total - startMs).coerceAtLeast(EditorOps.MIN_TEXT_MS)
            LabeledSlider(
                label = "Stays for",
                valueText = "%.1f s".format(lengthMs / 1000f),
                value = (lengthMs - EditorOps.MIN_TEXT_MS).toFloat() / (longest - EditorOps.MIN_TEXT_MS).coerceAtLeast(1),
                onValueChange = { lengthMs = (EditorOps.MIN_TEXT_MS + it * (longest - EditorOps.MIN_TEXT_MS)).roundToLong().coerceIn(EditorOps.MIN_TEXT_MS, longest) },
                onFinished = { push() },
            )
        }
    }
}

private val emojiChoices = listOf(
    "😀", "😂", "🤣", "😍", "😎", "🥳", "😭", "😡", "🤯", "😱", "🤔", "😴",
    "❤️", "🔥", "✨", "💯", "🎉", "🎵", "🎶", "🎤", "🎧", "🎬", "⭐", "⚡",
    "👍", "👏", "🙌", "💪", "🙏", "👀", "💀", "👑", "🏆", "🚀", "🌈", "☀️",
    "🌙", "🌊", "🌴", "🍕", "🍔", "🍻", "⚽", "🏀", "🎮", "🚗", "✈️", "💸",
)

@Composable
fun EmojiSheet(project: EditProject, playheadMs: Long, onChange: (EditProject) -> Unit, onDismiss: () -> Unit) {
    MwSheet(onDismiss) {
        SheetTitle("Add an emoji", "Tap it on the timeline afterwards to move or resize it")
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            modifier = Modifier.height(330.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        ) {
            items(emojiChoices) { emoji ->
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            val total = project.durationMs
                            val start = playheadMs.coerceIn(0, (total - EditorOps.MIN_TEXT_MS).coerceAtLeast(0))
                            val item = EditText(
                                id = EditorStore.newId(),
                                text = emoji,
                                startMs = start,
                                endMs = minOf(total, start + 3000),
                                x = 0f,
                                y = 0f,
                                size = 0.2f,
                            )
                            onChange(EditorOps.addText(project, item))
                            onDismiss()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, fontSize = 30.sp)
                }
            }
        }
    }
}

// ---- looks -----------------------------------------------------------------------------------

@Composable
private fun ColumnScope.ApplyToAll(label: String, onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        PillButton(label, onClick = onClick)
    }
}

@Composable
fun FilterSheet(project: EditProject, clip: EditClip, onChange: (EditProject) -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    MwSheet(onDismiss) {
        SheetTitle("Filter", clip.title)
        ChipRow {
            ClipFilter.entries.forEach { filter ->
                Chip(filter.label, selected = clip.filter == filter.name, onClick = {
                    onChange(EditorOps.updateClip(project, clip.id) { it.copy(filter = filter.name) })
                })
            }
        }
        if (project.clips.size > 1) {
            ApplyToAll("Use this filter on every clip") {
                onChange(project.copy(clips = project.clips.map { it.copy(filter = clip.filter) }))
                app.say("Filter put on all ${project.clips.size} clips")
            }
        }
    }
}

@Composable
fun AdjustSheet(project: EditProject, clip: EditClip, onChange: (EditProject) -> Unit, onDismiss: () -> Unit) {
    var brightness by remember(clip.id) { mutableFloatStateOf(clip.brightness) }
    var contrast by remember(clip.id) { mutableFloatStateOf(clip.contrast) }
    var saturation by remember(clip.id) { mutableFloatStateOf(clip.saturation) }
    fun push() = onChange(
        EditorOps.updateClip(project, clip.id) { it.copy(brightness = brightness, contrast = contrast, saturation = saturation) },
    )
    fun shown(v: Float) = (if (v > 0) "+" else "") + (v * 100).roundToInt()
    MwSheet(onDismiss) {
        SheetTitle("Adjust", clip.title) {
            PillButton("Reset", onClick = {
                brightness = 0f
                contrast = 0f
                saturation = 0f
                push()
            })
        }
        LabeledSlider("Brightness", shown(brightness), (brightness + 1) / 2, { brightness = snapZero(it * 2 - 1) }, { push() }, mark = 0.5f)
        LabeledSlider("Contrast", shown(contrast), (contrast + 1) / 2, { contrast = snapZero(it * 2 - 1) }, { push() }, mark = 0.5f)
        LabeledSlider("Colour", shown(saturation), (saturation + 1) / 2, { saturation = snapZero(it * 2 - 1) }, { push() }, mark = 0.5f)
    }
}

private fun snapZero(value: Float) = if (abs(value) < 0.04f) 0f else value

private val speedChoices = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

@Composable
fun SpeedEditSheet(project: EditProject, clip: EditClip, onChange: (EditProject) -> Unit, onDismiss: () -> Unit) {
    MwSheet(onDismiss) {
        SheetTitle("Speed", "This clip now lasts ${"%.1f".format(clip.durationMs / 1000f)} s")
        ChipRow {
            speedChoices.forEach { speed ->
                val label = if (speed == speed.roundToInt().toFloat()) "${speed.roundToInt()}×" else "$speed×"
                Chip(label, selected = abs(clip.speed - speed) < 0.01f, onClick = {
                    onChange(EditorOps.updateClip(project, clip.id) { it.copy(speed = speed) })
                })
            }
        }
        Text(
            "Slower than 1× is slow motion, faster is a speed-up. Voices keep their normal pitch.",
            style = MaterialTheme.typography.bodySmall,
            color = TextLow,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun VolumeSheet(project: EditProject, clip: EditClip, onChange: (EditProject) -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    var volume by remember(clip.id) { mutableFloatStateOf(clip.volume) }
    MwSheet(onDismiss) {
        SheetTitle("Clip volume", clip.title) {
            PillButton(if (volume <= 0.001f) "Unmute" else "Mute", onClick = {
                volume = if (volume <= 0.001f) 1f else 0f
                onChange(EditorOps.updateClip(project, clip.id) { it.copy(volume = volume) })
            })
        }
        LabeledSlider(
            label = "Sound of this clip",
            valueText = "${(volume * 100).roundToInt()}%",
            value = volume / 2f,
            onValueChange = { volume = (it * 2f * 20).roundToInt() / 20f },
            onFinished = { onChange(EditorOps.updateClip(project, clip.id) { it.copy(volume = volume) }) },
            mark = 0.5f,
        )
        if (project.clips.size > 1) {
            ApplyToAll("Use this volume on every clip") {
                onChange(project.copy(clips = project.clips.map { if (it.isImage) it else it.copy(volume = volume) }))
                app.say("Volume set on all clips")
            }
        }
    }
}

private val ratioChoices = listOf("ORIGINAL" to "Original", "16:9" to "16:9 Wide", "9:16" to "9:16 Tall", "1:1" to "1:1 Square", "4:5" to "4:5 Portrait")

@Composable
fun CanvasSheet(project: EditProject, onChange: (EditProject) -> Unit, onDismiss: () -> Unit) {
    MwSheet(onDismiss) {
        SheetTitle("Canvas", "The shape of the finished video")
        ChipRow {
            ratioChoices.forEach { (key, label) ->
                Chip(label, selected = project.ratio == key, onClick = { onChange(project.copy(ratio = key)) })
            }
        }
        Text(
            "When a clip is a different shape, fill the gaps with",
            style = MaterialTheme.typography.labelLarge,
            color = TextMid,
            modifier = Modifier.padding(start = 22.dp, top = 10.dp),
        )
        ChipRow {
            Chip("Black bars", selected = project.background != "BLUR", onClick = { onChange(project.copy(background = "BLACK")) })
            Chip("Blurred picture", selected = project.background == "BLUR", onClick = { onChange(project.copy(background = "BLUR")) })
        }
    }
}

// ---- trimming --------------------------------------------------------------------------------

@Composable
fun TrimSheet(project: EditProject, clip: EditClip, onChange: (EditProject) -> Unit, onDismiss: () -> Unit) {
    MwSheet(onDismiss) {
        if (clip.isImage) {
            var length by remember(clip.id) { mutableLongStateOf(clip.endMs) }
            SheetTitle("Photo length", clip.title)
            LabeledSlider(
                label = "Show this photo for",
                valueText = "%.1f s".format(length / 1000f),
                value = (length - EditorOps.MIN_PHOTO_MS).toFloat() / (15_000 - EditorOps.MIN_PHOTO_MS),
                onValueChange = { length = ((EditorOps.MIN_PHOTO_MS + it * (15_000 - EditorOps.MIN_PHOTO_MS)) / 100).roundToLong() * 100 },
                onFinished = { onChange(EditorOps.trim(project, clip.id, 0, length)) },
            )
            if (project.clips.count { it.isImage } > 1) {
                ApplyToAll("Use this length for every photo") {
                    onChange(project.copy(clips = project.clips.map { if (it.isImage) it.copy(endMs = length) else it }))
                }
            }
        } else {
            var start by remember(clip.id) { mutableLongStateOf(clip.startMs) }
            var end by remember(clip.id) { mutableLongStateOf(clip.endMs) }
            SheetTitle("Trim", "Drag the two handles to keep the part you want")
            TrimBar(
                clip = clip,
                start = start,
                end = end,
                onChange = { s, e ->
                    start = s
                    end = e
                },
                onFinished = { onChange(EditorOps.trim(project, clip.id, start, end)) },
            )
            Row(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
                Text("From ${formatEditTime(start)}", style = MaterialTheme.typography.labelLarge, color = TextMid, modifier = Modifier.weight(1f))
                Text("Keeps ${"%.1f".format((end - start) / 1000f)} s", style = MaterialTheme.typography.labelLarge, color = TextHi)
                Text("To ${formatEditTime(end)}", style = MaterialTheme.typography.labelLarge, color = TextMid, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A strip of the whole original video with a handle at each end of the part that is kept. */
@Composable
private fun TrimBar(clip: EditClip, start: Long, end: Long, onChange: (Long, Long) -> Unit, onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val total = clip.sourceDurationMs.coerceAtLeast(end).coerceAtLeast(1)
    val currentStart by rememberUpdatedState(start)
    val currentEnd by rememberUpdatedState(end)
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    var grabbed by remember { mutableIntStateOf(0) }
    Canvas(
        Modifier
            .padding(horizontal = 22.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(64.dp)
            .pointerInput(clip.id) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val startX = currentStart.toFloat() / total * size.width
                        val endX = currentEnd.toFloat() / total * size.width
                        grabbed = if (abs(offset.x - startX) <= abs(offset.x - endX)) 1 else 2
                    },
                    onDragEnd = {
                        grabbed = 0
                        finished()
                    },
                    onDragCancel = {
                        grabbed = 0
                        finished()
                    },
                    onHorizontalDrag = { input, delta ->
                        input.consume()
                        val moved = (delta / size.width * total).roundToLong()
                        if (grabbed == 1) {
                            change((currentStart + moved).coerceIn(0, currentEnd - EditorOps.MIN_PIECE_MS), currentEnd)
                        } else if (grabbed == 2) {
                            change(currentStart, (currentEnd + moved).coerceIn(currentStart + EditorOps.MIN_PIECE_MS, total))
                        }
                    },
                )
            },
    ) {
        FrameCache.generation.intValue
        val tile = size.height
        val count = (size.width / tile).toInt() + 1
        for (k in 0 until count) {
            val sourceMs = ((k + 0.5f) * tile / size.width * total).toLong().coerceIn(0, total)
            val image = FrameCache.get(context, scope, clip, sourceMs) ?: continue
            val side = minOf(image.width, image.height)
            drawImage(
                image = image,
                srcOffset = IntOffset((image.width - side) / 2, (image.height - side) / 2),
                srcSize = IntSize(side, side),
                dstOffset = IntOffset((k * tile).roundToInt(), 0),
                dstSize = IntSize(tile.roundToInt() + 1, size.height.roundToInt()),
            )
        }
        val left = start.toFloat() / total * size.width
        val right = end.toFloat() / total * size.width
        // Dim what is cut away.
        drawRect(Color.Black.copy(alpha = 0.68f), Offset.Zero, Size(left, size.height))
        drawRect(Color.Black.copy(alpha = 0.68f), Offset(right, 0f), Size(size.width - right, size.height))
        drawRoundRect(Pink, Offset(left, 0f), Size(right - left, size.height), CornerRadius(6.dp.toPx()), style = Stroke(3.dp.toPx()))
        val handle = Size(12.dp.toPx(), size.height)
        drawRoundRect(Pink, Offset(left - 4.dp.toPx(), 0f), handle, CornerRadius(5.dp.toPx()))
        drawRoundRect(Pink, Offset(right - 8.dp.toPx(), 0f), handle, CornerRadius(5.dp.toPx()))
        drawRoundRect(Color.White, Offset(left + 1.dp.toPx(), size.height * 0.3f), Size(2.dp.toPx(), size.height * 0.4f), CornerRadius(1.dp.toPx()))
        drawRoundRect(Color.White, Offset(right - 3.dp.toPx(), size.height * 0.3f), Size(2.dp.toPx(), size.height * 0.4f), CornerRadius(1.dp.toPx()))
    }
}

// ---- saving ----------------------------------------------------------------------------------

@Composable
fun ExportSheet(project: EditProject, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var quality by remember { mutableIntStateOf(720) }
    MwSheet(onDismiss) {
        val clips = project.clips.size
        SheetTitle("Save video", "$clips ${if (clips == 1) "clip" else "clips"} · ${formatEditTime(project.durationMs)}")
        ChipRow {
            listOf(480 to "480p  Small", 720 to "720p  HD", 1080 to "1080p  Full HD").forEach { (value, label) ->
                Chip(label, selected = quality == value, onClick = { quality = value })
            }
        }
        Text(
            "The finished video goes into your phone's Movies folder and shows up in the Videos tab. " +
                "Higher quality takes longer and makes a bigger file.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMid,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
        )
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            PillButton(
                "Save to my phone",
                onClick = {
                    onSave(quality)
                    onDismiss()
                },
                filled = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Covers the editor while a video is being saved, then says where it went. */
@Composable
fun ExportOverlay(state: ExportState, onCancel: () -> Unit, onDone: () -> Unit, onWatch: (MediaEntry) -> Unit) {
    val context = LocalContext.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(28.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Surface1)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                state.running -> {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.size(92.dp),
                            color = Pink,
                            trackColor = Surface3,
                            strokeWidth = 7.dp,
                        )
                        Text("${(state.progress * 100).roundToInt()}%", style = MaterialTheme.typography.titleLarge, color = TextHi)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("Saving “${state.name}”", style = MaterialTheme.typography.titleMedium, color = TextHi, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "You can leave the app - it keeps going and the notification shows the progress.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMid,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    PillButton("Cancel", onClick = onCancel)
                }
                state.error != null -> {
                    Text("Couldn't save the video", style = MaterialTheme.typography.titleLarge, color = TextHi)
                    Spacer(Modifier.height(8.dp))
                    Text(state.error, style = MaterialTheme.typography.bodyMedium, color = TextMid, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(18.dp))
                    PillButton("OK", onClick = onDone, filled = true)
                }
                else -> {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Cyan, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Video saved", style = MaterialTheme.typography.titleLarge, color = TextHi)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "It is in ${state.savedWhere ?: "your videos"} and in the Videos tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMid,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val saved = state.savedUri
                        if (saved != null) {
                            PillButton(
                                "Watch",
                                onClick = { onWatch(describeUri(context, Uri.parse(saved), "video/mp4").copy(title = state.name, isVideo = true)) },
                                icon = Icons.Rounded.PlayArrow,
                                filled = true,
                            )
                            if (saved.startsWith("content://")) {
                                PillButton(
                                    "Share",
                                    onClick = {
                                        val send = Intent(Intent.ACTION_SEND)
                                            .setType("video/mp4")
                                            .putExtra(Intent.EXTRA_STREAM, Uri.parse(saved))
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        runCatching { context.startActivity(Intent.createChooser(send, "Share video")) }
                                    },
                                    icon = Icons.Rounded.Share,
                                )
                            }
                        }
                        PillButton("Done", onClick = onDone)
                    }
                }
            }
        }
    }
}
