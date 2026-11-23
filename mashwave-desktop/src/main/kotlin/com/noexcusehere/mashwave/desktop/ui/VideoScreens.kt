package com.noexcusehere.mashwave.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noexcusehere.mashwave.desktop.AppFolders
import com.noexcusehere.mashwave.desktop.Desk
import com.noexcusehere.mashwave.desktop.LocalFile
import com.noexcusehere.mashwave.desktop.Look
import com.noexcusehere.mashwave.desktop.MediaKinds
import com.noexcusehere.mashwave.desktop.PhotoFiles
import com.noexcusehere.mashwave.desktop.VideoEditing
import com.noexcusehere.mashwave.editor.ClipFilter
import com.noexcusehere.mashwave.editor.EditClip
import com.noexcusehere.mashwave.editor.EditProject
import com.noexcusehere.mashwave.editor.EditText
import com.noexcusehere.mashwave.editor.EditorOps
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The box for picking videos and photos to put in a video. */
fun chooseClips(owner: Frame?): List<File> = runCatching {
    val dialog = FileDialog(owner, "Choose videos and photos", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.setFilenameFilter { _, name -> MediaKinds.isVideo(name) == true || PhotoFiles.isPicture(name) }
    dialog.isVisible = true
    dialog.files?.toList().orEmpty()
}.getOrDefault(emptyList())

private fun chooseSong(owner: Frame?): File? = runCatching {
    val dialog = FileDialog(owner, "Choose a song", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> MediaKinds.isVideo(name) == false }
    dialog.isVisible = true
    dialog.files?.firstOrNull()
}.getOrNull()

/** "1:05" for the timeline; tenths are added for short stretches, where they matter. */
private fun stamp(ms: Long, tenths: Boolean = false): String {
    val total = ms.coerceAtLeast(0)
    val minutes = total / 60_000
    val seconds = total / 1000 % 60
    return if (tenths) "%d:%02d.%d".format(minutes, seconds, total / 100 % 10) else "%d:%02d".format(minutes, seconds)
}

/** "2x", "0.5x", "1.25x". */
private fun times(speed: Float): String = "%.2f".format(speed).trimEnd('0').trimEnd('.', ',') + "x"

/** The Video editor page: the videos being made on this computer, and a button to start another. */
@Composable
fun VideoHomeScreen(desk: Desk) {
    val projects = desk.videos.projects
    var removing by remember { mutableStateOf<EditProject?>(null) }
    val engine = desk.videoEngine

    fun startNew() {
        val files = chooseClips(desk.window)
        if (files.isEmpty()) return
        desk.openVideo(VideoEditing.fresh())
        desk.videoEditing?.addFiles(files)
    }

    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 20.dp, top = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Video editor", style = MaterialTheme.typography.headlineSmall, color = TextHi)
                Text(
                    "Join videos and photos, cut them, change their speed and look, add words and music, and save the result as one video.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextLow,
                )
            }
            Spacer(Modifier.width(12.dp))
            Pill("New video", { startNew() }, icon = Icons.Rounded.Add, enabled = engine != null)
        }
        Spacer(Modifier.height(14.dp))
        when {
            engine == null -> Note(
                Icons.Rounded.MovieFilter,
                "The video editor's engine is missing",
                "This copy of the app came without the part that cuts and joins video (FFmpeg), and none was found on this computer. The Windows and Mac downloads include it.",
            )
            projects.isEmpty() -> Note(
                Icons.Rounded.MovieFilter,
                "No videos yet",
                "Choose \"New video\" and pick the videos and photos to start with. You can add more later, or drop them onto the window.",
                action = "New video",
                onAction = { startNew() },
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(250.dp),
                contentPadding = PaddingValues(bottom = 20.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(projects, key = { it.id }) { project ->
                    Column(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(14.dp)).clickable { desk.openVideo(project) }.padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Surface3), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Movie, contentDescription = null, tint = TextMid, modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(project.name, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val count = project.clips.size
                                Text(
                                    (if (count == 1) "1 clip" else "$count clips") + " · " + stamp(project.durationMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMid,
                                )
                            }
                            RoundIcon(Icons.Rounded.Delete, "Delete this video project", { removing = project }, size = 32.dp, tint = TextLow)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Changed " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(project.updatedAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextLow,
                        )
                    }
                }
            }
        }
    }

    removing?.let { project ->
        Sheet("Delete \"${project.name}\"?", onClose = { removing = null }, width = 440.dp) {
            Text("The project is deleted. The videos and photos in it, and any finished video you saved, stay where they are.", style = MaterialTheme.typography.bodyMedium, color = TextMid)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Quiet("Keep it", { removing = null })
                Pill("Delete", {
                    desk.videos.remove(project.id)
                    removing = null
                })
            }
        }
    }
}

private enum class Panel(val label: String) { CLIP("Clip"), TEXT("Words"), MUSIC("Music"), SHAPE("Shape") }

/** The video editor. It covers the whole window while a project is open. */
@Composable
fun VideoEditor(desk: Desk, editing: VideoEditing) {
    val project = editing.project
    var panel by remember { mutableStateOf(Panel.CLIP) }
    var naming by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- top bar ----
            Row(Modifier.fillMaxWidth().height(58.dp).padding(start = 10.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to your videos (Esc)", { desk.closeVideo() })
                Spacer(Modifier.width(8.dp))
                if (naming) {
                    var name by remember { mutableStateOf(project.name) }
                    Field(name, { name = it }, "Name of the video", Modifier.width(320.dp), onEnter = {
                        editing.rename(name)
                        editing.settle()
                        naming = false
                    })
                    Spacer(Modifier.width(8.dp))
                    Quiet("Done", {
                        editing.rename(name)
                        editing.settle()
                        naming = false
                    })
                    Spacer(Modifier.weight(1f))
                } else {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { naming = true }.padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(project.name, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Click to rename · saved as you go", style = MaterialTheme.typography.labelSmall, color = TextLow, maxLines = 1)
                    }
                }
                RoundIcon(Icons.AutoMirrored.Rounded.Undo, "Undo (Ctrl+Z)", { editing.undo() }, enabled = editing.canUndo)
                RoundIcon(Icons.AutoMirrored.Rounded.Redo, "Redo (Ctrl+Y)", { editing.redo() }, enabled = editing.canRedo)
                Spacer(Modifier.width(10.dp))
                Pill("Save video", { exporting = true }, enabled = project.clips.isNotEmpty())
            }

            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Preview(editing, Modifier.weight(1f).fillMaxWidth())
                    Transport(desk, editing)
                    Timeline(editing, onText = { panel = Panel.TEXT }, onMusic = { panel = Panel.MUSIC }, onClip = { panel = Panel.CLIP })
                }
                Column(Modifier.width(340.dp).fillMaxHeight().background(Surface1)) {
                    Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Panel.entries.forEach { Chip(it.label, selected = panel == it, onClick = { panel = it }) }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                        when (panel) {
                            Panel.CLIP -> ClipPanel(editing)
                            Panel.TEXT -> TextPanel(editing)
                            Panel.MUSIC -> MusicPanel(desk, editing)
                            Panel.SHAPE -> ShapePanel(editing)
                        }
                    }
                }
            }
        }

        if (exporting || editing.export != null) {
            ExportBox(desk, editing) {
                exporting = false
                if (editing.export?.done != null || editing.export?.problem?.isNotEmpty() == true) editing.export = null
            }
        }
    }
}

/** The video as it looks under the playhead, or the preview while it plays. */
@Composable
private fun Preview(editing: VideoEditing, modifier: Modifier) {
    val project = editing.project
    Box(modifier.background(Color.Black).padding(14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(project.aspect.coerceIn(0.3f, 3.4f)).background(Color(0xFF050505)), contentAlignment = Alignment.Center) {
            val player = editing.player
            val still = editing.frame
            if (editing.playing && player != null) {
                VideoPicture(player, Look.FIT, Modifier.fillMaxSize())
            } else if (still != null) {
                Image(still, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else if (project.clips.isEmpty()) {
                Text("Add videos or photos to begin", style = MaterialTheme.typography.bodyMedium, color = TextLow)
            }
        }
        val preparing = editing.preparing
        if (preparing != null || editing.adding) {
            Column(
                Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 22.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Pink, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    if (preparing != null) "Getting the preview ready... ${(preparing * 100).roundToInt()}%" else "Reading the files...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextHi,
                )
                if (preparing != null) {
                    Spacer(Modifier.height(8.dp))
                    Bar(preparing, Modifier.width(220.dp))
                }
            }
        }
    }
}

/** Play, the time, and the buttons that work on the chosen clip. */
@Composable
private fun Transport(desk: Desk, editing: VideoEditing) {
    val project = editing.project
    val clip = editing.clip
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(BrandGradient).dimmedUnless(project.clips.isNotEmpty())
                .clickable(enabled = project.clips.isNotEmpty()) { if (editing.playing || editing.preparing != null) editing.pause() else editing.play() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (editing.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (editing.playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text("${stamp(editing.playhead, tenths = true)} / ${stamp(project.durationMs)}", style = MaterialTheme.typography.titleSmall, color = TextHi)
        Spacer(Modifier.weight(1f))
        Quiet("Add clips", { chooseClips(desk.window).takeIf { it.isNotEmpty() }?.let { editing.addFiles(it) } }, icon = Icons.Rounded.Add)
        Spacer(Modifier.width(8.dp))
        RoundIcon(Icons.Rounded.ContentCut, "Cut the clip in two at the line", { editing.split() }, backing = Surface3, enabled = project.clips.isNotEmpty())
        Spacer(Modifier.width(6.dp))
        RoundIcon(Icons.Rounded.ChevronLeft, "Move the clip earlier", { clip?.let { editing.move(it.id, -1) } }, backing = Surface3, enabled = clip != null && project.clips.firstOrNull()?.id != clip.id)
        Spacer(Modifier.width(6.dp))
        RoundIcon(Icons.Rounded.ChevronRight, "Move the clip later", { clip?.let { editing.move(it.id, 1) } }, backing = Surface3, enabled = clip != null && project.clips.lastOrNull()?.id != clip.id)
        Spacer(Modifier.width(6.dp))
        RoundIcon(Icons.Rounded.ContentCopy, "Make a copy of the clip", { clip?.let { editing.duplicate(it.id) } }, backing = Surface3, enabled = clip != null)
        Spacer(Modifier.width(6.dp))
        RoundIcon(Icons.Rounded.Delete, "Take the clip out of the video", { clip?.let { editing.remove(it.id) } }, backing = Surface3, enabled = clip != null)
    }
}

/** The clips side by side, each as wide as it is long, with the words and the music under them and a line where the playhead is. */
@Composable
private fun Timeline(editing: VideoEditing, onText: () -> Unit, onMusic: () -> Unit, onClip: () -> Unit) {
    val project = editing.project
    val total = project.durationMs.coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxWidth().background(Surface1).padding(horizontal = 14.dp, vertical = 10.dp)) {
        val width = maxWidth
        fun at(ms: Long) = width * (ms.toFloat() / total)
        Column {
            // The strip to click or drag along to move the line.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .pointerInput(total) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fun to(x: Float) = editing.seek((x / size.width * total).roundToLong())
                            to(down.position.x)
                            drag(down.id) { change ->
                                to(change.position.x)
                                change.consume()
                            }
                        }
                    },
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (i in 0..4) Text(stamp(total * i / 4), style = MaterialTheme.typography.labelSmall, color = TextLow)
                }
            }
            Spacer(Modifier.height(4.dp))
            // The clips.
            Row(Modifier.fillMaxWidth().height(58.dp)) {
                if (project.clips.isEmpty()) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Surface2), contentAlignment = Alignment.Center) {
                        Text("Your clips will line up here", style = MaterialTheme.typography.labelMedium, color = TextLow)
                    }
                }
                val starts = project.clipStarts()
                project.clips.forEachIndexed { index, clip ->
                    val chosen = clip.id == editing.selected
                    Box(
                        Modifier
                            .weight(clip.durationMs.toFloat().coerceAtLeast(1f))
                            .fillMaxHeight()
                            .padding(horizontal = 1.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (chosen) Violet.copy(alpha = 0.55f) else Surface3)
                            .border(if (chosen) 2.dp else 1.dp, if (chosen) Pink else Outline, RoundedCornerShape(8.dp))
                            .clickable {
                                editing.selected = clip.id
                                onClip()
                                // The line jumps into the clip unless it is in it already.
                                val from = starts[index]
                                if (editing.playhead < from || editing.playhead >= from + clip.durationMs) editing.seek(from)
                            }
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (clip.isImage) Icons.Rounded.Image else Icons.Rounded.Movie, contentDescription = null, tint = TextMid, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(clip.title.ifBlank { if (clip.isImage) "Photo" else "Video" }, style = MaterialTheme.typography.labelSmall, color = TextHi, maxLines = 1, overflow = TextOverflow.Clip)
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                stamp(clip.durationMs, tenths = true) + if (!clip.isImage && clip.speed != 1f) "  " + times(clip.speed) else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMid,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                            )
                        }
                    }
                }
            }
            // The words, each a bar over the stretch it shows for.
            if (project.texts.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(20.dp)) {
                    project.texts.forEach { item ->
                        val chosen = item.id == editing.selectedText
                        Box(
                            Modifier
                                .offset(x = at(item.startMs))
                                .width(at(item.endMs - item.startMs).coerceAtLeast(10.dp))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (chosen) Pink.copy(alpha = 0.7f) else Cyan.copy(alpha = 0.32f))
                                .clickable {
                                    editing.selectedText = item.id
                                    onText()
                                }
                                .padding(horizontal = 5.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(item.text.replace('\n', ' '), style = MaterialTheme.typography.labelSmall, color = TextHi, maxLines = 1, overflow = TextOverflow.Clip)
                        }
                    }
                }
            }
            // The music, under everything.
            project.music?.let { music ->
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(5.dp)).background(Amber.copy(alpha = 0.26f)).clickable { onMusic() }.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = TextHi, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(music.entry.title, style = MaterialTheme.typography.labelSmall, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // The line.
        Canvas(Modifier.matchParentSize()) {
            val x = size.width * (editing.playhead.toFloat() / total).coerceIn(0f, 1f)
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            drawCircle(Color.White, 5.dp.toPx(), Offset(x, 5.dp.toPx()))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = TextMid, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 2.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = TextLow, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
}

/** Everything that can be done to the chosen clip. */
@Composable
private fun ClipPanel(editing: VideoEditing) {
    val clip = editing.clip
    if (clip == null) {
        Hint("Click a clip in the row below to work on it.")
        return
    }
    val id = clip.id
    Text(clip.title.ifBlank { "Clip" }, style = MaterialTheme.typography.titleSmall, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))

    if (clip.isImage) {
        val longest = 15_000f
        SliderRow("Shows for", (clip.endMs / longest).coerceIn(0f, 1f), "%.1f s".format(clip.endMs / 1000f), {
            editing.trim(id, 0, (it * longest).roundToLong().coerceAtLeast(EditorOps.MIN_PHOTO_MS), "length")
        }, onDone = { editing.settle() })
    } else {
        val whole = clip.sourceDurationMs.coerceAtLeast(clip.endMs).coerceAtLeast(1).toFloat()
        Label("Keep from ... to ...")
        SliderRow("Start", clip.startMs / whole, stamp(clip.startMs, tenths = true), {
            editing.trim(id, (it * whole).roundToLong().coerceAtMost(clip.endMs - EditorOps.MIN_PIECE_MS), clip.endMs, "start")
        }, onDone = { editing.settle() })
        SliderRow("End", clip.endMs / whole, stamp(clip.endMs, tenths = true), {
            editing.trim(id, clip.startMs, (it * whole).roundToLong().coerceAtLeast(clip.startMs + EditorOps.MIN_PIECE_MS), "end")
        }, onDone = { editing.settle() })

        Label("Speed")
        ActionRow {
            listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f).forEach { speed ->
                Chip(if (speed == 1f) "Normal" else times(speed), selected = clip.speed == speed, onClick = { editing.update(id) { it.copy(speed = speed) } })
            }
        }
        SliderRow("Sound", (clip.volume / 2f).coerceIn(0f, 1f), "${(clip.volume * 100).roundToInt()}%", { value ->
            editing.update(id, "volume") { it.copy(volume = (value * 2f * 20).roundToInt() / 20f) }
        }, onDone = { editing.settle() }, mark = 0.5f)
    }

    Label("Turn")
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Quiet("Turn", { editing.update(id) { it.copy(rotation = (it.rotation + 90) % 360) } }, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.RotateRight)
        Quiet("Mirror", { editing.update(id) { it.copy(mirrored = !it.mirrored) } }, Modifier.weight(1f), icon = Icons.Rounded.Flip)
    }

    Label("Look")
    ActionRow {
        ClipFilter.entries.forEach { look ->
            Chip(look.label, selected = clip.filter == look.name, onClick = { editing.update(id) { it.copy(filter = look.name) } })
        }
    }
    listOf(
        Triple("Brightness", clip.brightness) { c: EditClip, v: Float -> c.copy(brightness = v) },
        Triple("Contrast", clip.contrast) { c: EditClip, v: Float -> c.copy(contrast = v) },
        Triple("Colour", clip.saturation) { c: EditClip, v: Float -> c.copy(saturation = v) },
    ).forEach { (label, value, set) ->
        SliderRow(label, (value + 1f) / 2f, (if (value > 0) "+" else "") + (value * 100).roundToInt(), {
            val next = it * 2f - 1f
            editing.update(id, label) { c -> set(c, if (kotlin.math.abs(next) < 0.04f) 0f else next) }
        }, onDone = { editing.settle() }, mark = 0.5f)
    }
}

private val TEXT_COLOURS = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFF3B30, 0xFFFF4D9D, 0xFF8B5CF6, 0xFF22D3EE, 0xFF34C759, 0xFFFFD60A, 0xFFFF9500)

/** Words laid over the video. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextPanel(editing: VideoEditing) {
    val project = editing.project
    Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Quiet("Add words", { editing.addText() }, icon = Icons.Rounded.TextFields, enabled = project.clips.isNotEmpty())
    }
    if (project.texts.isEmpty()) {
        Hint("Words (or a smiley) appear over the picture for the stretch of time you choose. They start where the line is.")
        return
    }
    project.texts.forEach { item ->
        val chosen = item.id == editing.selectedText
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(if (chosen) Violet.copy(alpha = 0.3f) else Surface2)
                .clickable { editing.selectedText = item.id }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(item.text.replace('\n', ' '), style = MaterialTheme.typography.bodyMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${stamp(item.startMs)} - ${stamp(item.endMs)}", style = MaterialTheme.typography.labelSmall, color = TextMid)
        }
    }
    val item = editing.text ?: return
    val id = item.id
    val total = project.durationMs.coerceAtLeast(1).toFloat()
    Spacer(Modifier.height(8.dp))
    Field(item.text, { typed -> editing.updateText(id, "words") { it.copy(text = typed.take(200)) } }, "What it says", Modifier.fillMaxWidth().padding(horizontal = 14.dp), lines = 3)
    SliderRow("From", item.startMs / total, stamp(item.startMs, tenths = true), { v ->
        editing.updateText(id, "from") { it.copy(startMs = (v * total).roundToLong().coerceAtMost(it.endMs - EditorOps.MIN_TEXT_MS).coerceAtLeast(0)) }
    }, onDone = { editing.settle() })
    SliderRow("To", item.endMs / total, stamp(item.endMs, tenths = true), { v ->
        editing.updateText(id, "to") { it.copy(endMs = (v * total).roundToLong().coerceAtLeast(it.startMs + EditorOps.MIN_TEXT_MS)) }
    }, onDone = { editing.settle() })
    SliderRow("Size", ((item.size - 0.03f) / 0.27f).coerceIn(0f, 1f), "", { v -> editing.updateText(id, "size") { it.copy(size = 0.03f + v * 0.27f) } }, onDone = { editing.settle() })
    SliderRow("Left - right", (item.x + 1f) / 2f, "", { v -> editing.updateText(id, "x") { it.copy(x = v * 2f - 1f) } }, onDone = { editing.settle() }, mark = 0.5f)
    SliderRow("Down - up", (item.y + 1f) / 2f, "", { v -> editing.updateText(id, "y") { it.copy(y = v * 2f - 1f) } }, onDone = { editing.settle() }, mark = 0.5f)
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TEXT_COLOURS.forEach { colour ->
            val chosen = item.color == colour
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(colour.toInt())).border(if (chosen) 3.dp else 1.dp, if (chosen) Pink else Outline, CircleShape)
                    .clickable { editing.updateText(id) { it.copy(color = colour) } },
            )
        }
    }
    ActionRow { Quiet("Remove these words", { editing.removeText(id) }, icon = Icons.Rounded.Delete) }
}

/** A song under the whole video. */
@Composable
private fun MusicPanel(desk: Desk, editing: VideoEditing) {
    val music = editing.project.music
    Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
        Quiet(if (music == null) "Choose a song" else "Choose another song", { chooseSong(desk.window)?.let { editing.chooseMusic(it) } }, icon = Icons.Rounded.MusicNote)
    }
    if (music == null) {
        Hint("A song plays under the whole video. If it is shorter than the video it starts again; the clips keep their own sound unless you turn it down.")
        return
    }
    Text(music.entry.title, style = MaterialTheme.typography.titleSmall, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
    val length = music.entry.durationMs.coerceAtLeast(1000).toFloat()
    SliderRow("Start the song at", (music.offsetMs / length).coerceIn(0f, 1f), stamp(music.offsetMs), { v ->
        editing.music("offset") { it.copy(offsetMs = (v * length).roundToLong().coerceIn(0, (length - 500).toLong().coerceAtLeast(0))) }
    }, onDone = { editing.settle() })
    SliderRow("Loudness", (music.volume / 2f).coerceIn(0f, 1f), "${(music.volume * 100).roundToInt()}%", { v ->
        editing.music("volume") { it.copy(volume = (v * 2f * 20).roundToInt() / 20f) }
    }, onDone = { editing.settle() }, mark = 0.5f)
    ActionRow { Quiet("Take the song out", { editing.noMusic() }, icon = Icons.Rounded.Delete) }
}

private val SHAPES = listOf("ORIGINAL" to "As the first clip", "16:9" to "Wide 16:9", "9:16" to "Tall 9:16", "1:1" to "Square", "4:5" to "Portrait 4:5")

/** The shape of the finished video, and what fills the gaps beside clips of another shape. */
@Composable
private fun ShapePanel(editing: VideoEditing) {
    val project = editing.project
    Label("Shape of the video")
    ActionRow {
        SHAPES.forEach { (key, label) -> Chip(label, selected = project.ratio == key, onClick = { editing.change(project.copy(ratio = key)) }) }
    }
    Label("Beside clips of another shape")
    ActionRow {
        Chip("Black", selected = project.background != "BLUR", onClick = { editing.change(project.copy(background = "BLACK")) })
        Chip("A soft copy of the clip", selected = project.background == "BLUR", onClick = { editing.change(project.copy(background = "BLUR")) })
    }
    Hint("Wide suits a TV or YouTube, tall suits a phone, square and portrait suit Instagram.")
}

/** Choosing how sharp the finished video is, watching it being made, and what to do with it afterwards. */
@Composable
private fun ExportBox(desk: Desk, editing: VideoEditing, onClose: () -> Unit) {
    val state = editing.export
    var side by remember { mutableIntStateOf(1080) }
    Sheet("Save the video", onClose = { if (state == null || state.done != null || state.problem.isNotEmpty()) onClose() }, width = 480.dp) {
        when {
            state == null -> {
                Text("How sharp should it be? Sharper takes longer to make and gives a bigger file.", style = MaterialTheme.typography.bodyMedium, color = TextMid)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1080 to "Full HD (1080)", 720 to "HD (720)", 480 to "Small (480)").forEach { (value, label) ->
                        Chip(label, selected = side == value, onClick = { side = value })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("It is saved in ${if (VideoEditing.savedFolder().parentFile?.name == "Movies") "Movies" else "Videos"} › ${AppFolders.NAME} as \"${editing.project.name}.mp4\".", style = MaterialTheme.typography.bodySmall, color = TextLow)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Quiet("Not now", onClose)
                    Pill("Make the video", { editing.make(side) }, icon = Icons.Rounded.Check)
                }
            }
            state.done != null -> {
                val file = state.done
                Text("Done. Saved as \"${file.name}\".", style = MaterialTheme.typography.bodyLarge, color = TextHi)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Play it", {
                        onClose()
                        desk.closeVideo()
                        desk.playVideos(listOf(LocalFile(path = file.absolutePath, video = true, title = file.nameWithoutExtension)), 0, fromStart = true)
                    }, icon = Icons.Rounded.PlayArrow)
                    Quiet("Show in folder", { runCatching { Desktop.getDesktop().open(file.parentFile) }.onFailure { desk.say("The folder could not be opened.") } }, icon = Icons.Rounded.FolderOpen)
                    Quiet("Close", onClose)
                }
            }
            state.problem.isNotEmpty() -> {
                Text(state.problem, style = MaterialTheme.typography.bodyMedium, color = Amber)
                Spacer(Modifier.height(16.dp))
                Quiet("Close", onClose)
            }
            else -> {
                Text("Making the video... ${(state.progress * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge, color = TextHi)
                Spacer(Modifier.height(12.dp))
                Bar(state.progress, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("You can leave this open; the app can be used again when it is done.", style = MaterialTheme.typography.bodySmall, color = TextLow)
                Spacer(Modifier.height(16.dp))
                Quiet("Stop", { editing.cancelMaking() })
            }
        }
    }
}
