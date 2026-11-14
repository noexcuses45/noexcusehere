package com.noexcusehere.mashwave.ui.screens

import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.editor.ClipProbe
import com.noexcusehere.mashwave.editor.EditClip
import com.noexcusehere.mashwave.editor.EditText
import com.noexcusehere.mashwave.editor.EditorOps
import com.noexcusehere.mashwave.editor.EditorPreview
import com.noexcusehere.mashwave.editor.EditorSession
import com.noexcusehere.mashwave.editor.EditorStore
import com.noexcusehere.mashwave.ui.LocalUi
import com.noexcusehere.mashwave.ui.components.PillButton
import com.noexcusehere.mashwave.ui.components.TextPromptDialog
import com.noexcusehere.mashwave.ui.theme.BrandGradient
import com.noexcusehere.mashwave.ui.theme.Ink
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface1
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which tool panel is open. */
private enum class Panel { NONE, ADD, MUSIC, TEXT, EMOJI, FILTER, ADJUST, TRIM, SPEED, VOLUME, CANVAS, EXPORT }

private data class Tool(val label: String, val icon: ImageVector, val needsClip: Boolean = true, val action: () -> Unit)

/** The full-screen video editor for one project. */
@Composable
fun EditorScreen(projectId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val initial = remember(projectId) { app.editorStore.get(projectId) }
    if (initial == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val session = remember(projectId) { EditorSession(app.editorStore, initial) }
    val preview = remember(projectId) { EditorPreview(context.applicationContext) }
    val export by app.exporter.state.collectAsStateWithLifecycle()
    val project = session.project

    var panel by remember { mutableStateOf(if (initial.clips.isEmpty()) Panel.ADD else Panel.NONE) }
    // The clip a tool panel was opened for; it stays the target even if edits move the playhead.
    var panelClipId by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf<EditText?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var lastSeekAt by remember { mutableLongStateOf(0L) }
    val exporting = export.running || export.savedUri != null || export.error != null

    DisposableEffect(projectId) { onDispose { preview.release() } }
    LifecycleResumeEffect(projectId) { onPauseOrDispose { preview.pause() } }
    BackHandler {
        preview.pause()
        onClose()
    }

    // Rebuild the preview shortly after each edit; while a video is being saved the preview
    // lets go of the phone's video decoders so the export can use them.
    LaunchedEffect(session.revision, exporting) {
        if (exporting) {
            preview.release()
        } else {
            delay(120)
            preview.show(session.project, session.positionMs)
        }
    }
    LaunchedEffect(preview.isPlaying) {
        while (preview.isPlaying) {
            session.positionMs = preview.positionMs
            delay(40)
        }
    }

    fun seek(ms: Long, force: Boolean = false) {
        session.positionMs = ms.coerceIn(0, project.durationMs.coerceAtLeast(0))
        val now = System.currentTimeMillis()
        if (force || now - lastSeekAt > 70) {
            lastSeekAt = now
            preview.seekTo(session.positionMs)
        }
    }

    fun addClips(clips: List<EditClip>) {
        if (clips.isEmpty()) {
            app.say("Those files couldn't be opened")
            return
        }
        val at = if (session.project.clips.isEmpty()) 0 else session.currentIndex + 1
        val next = EditorOps.addClips(session.project, clips, at)
        session.change(next)
        session.positionMs = next.clipStarts().getOrElse(at) { 0L }
    }

    /** An edit made from a clip's tool panel: afterwards the playhead is kept on that clip. */
    fun changeClip(next: com.noexcusehere.mashwave.editor.EditProject) {
        session.change(next)
        val index = next.clips.indexOfFirst { it.id == panelClipId }
        if (index >= 0) {
            val start = next.clipStarts()[index]
            if (session.positionMs < start || session.positionMs >= start + next.clips[index].durationMs) session.positionMs = start
        }
    }

    val clip = session.currentClip
    val tools = listOf(
        Tool("Add", Icons.Rounded.Add, needsClip = false) { panel = Panel.ADD },
        Tool("Music", Icons.Rounded.MusicNote, needsClip = false) { panel = Panel.MUSIC },
        Tool("Text", Icons.Rounded.TextFields) {
            editingText = null
            panel = Panel.TEXT
        },
        Tool("Emoji", Icons.Rounded.EmojiEmotions) { panel = Panel.EMOJI },
        Tool("Split", Icons.Rounded.VerticalSplit) {
            val next = EditorOps.splitAt(session.project, session.positionMs, EditorStore.newId())
            if (next == null) app.say("Move the white line further into the clip to split it") else session.change(next)
        },
        Tool("Trim", if (clip?.isImage == true) Icons.Rounded.Timer else Icons.Rounded.ContentCut) { panel = Panel.TRIM },
        Tool("Filter", Icons.Rounded.AutoAwesome) { panel = Panel.FILTER },
        Tool("Adjust", Icons.Rounded.Tune) { panel = Panel.ADJUST },
        Tool("Speed", Icons.Rounded.Speed) {
            if (clip?.isImage == true) app.say("Photos have no speed - use Trim to set how long they show") else panel = Panel.SPEED
        },
        Tool("Volume", Icons.AutoMirrored.Rounded.VolumeUp) {
            if (clip?.isImage == true) app.say("Photos have no sound") else panel = Panel.VOLUME
        },
        Tool("Rotate", Icons.AutoMirrored.Rounded.RotateRight) {
            clip?.let { c -> session.change(EditorOps.updateClip(session.project, c.id) { it.copy(rotation = it.rotation + 90) }) }
        },
        Tool("Flip", Icons.Rounded.Flip) {
            clip?.let { c -> session.change(EditorOps.updateClip(session.project, c.id) { it.copy(mirrored = !it.mirrored) }) }
        },
        Tool("Copy", Icons.Rounded.ContentCopy) {
            clip?.let { c -> session.change(EditorOps.duplicateClip(session.project, c.id, EditorStore.newId())) }
        },
        Tool("Earlier", Icons.Rounded.ChevronLeft) {
            clip?.let { c ->
                val next = EditorOps.moveClip(session.project, c.id, -1)
                session.change(next)
                session.positionMs = next.clipStarts().getOrElse(next.clips.indexOfFirst { it.id == c.id }) { 0L }
            }
        },
        Tool("Later", Icons.Rounded.ChevronRight) {
            clip?.let { c ->
                val next = EditorOps.moveClip(session.project, c.id, 1)
                session.change(next)
                session.positionMs = next.clipStarts().getOrElse(next.clips.indexOfFirst { it.id == c.id }) { 0L }
            }
        },
        Tool("Canvas", Icons.Rounded.AspectRatio) { panel = Panel.CANVAS },
        Tool("Delete", Icons.Rounded.DeleteOutline) {
            clip?.let { c -> session.change(EditorOps.removeClip(session.project, c.id)) }
        },
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ---- top bar ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                preview.pause()
                onClose()
            }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to projects", tint = TextHi)
            }
            Text(
                project.name,
                style = MaterialTheme.typography.titleMedium,
                color = TextHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { renaming = true }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
            IconButton(onClick = { session.undo() }, enabled = session.canUndo) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo", tint = if (session.canUndo) TextHi else TextLow)
            }
            IconButton(onClick = { session.redo() }, enabled = session.canRedo) {
                Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = "Redo", tint = if (session.canRedo) TextHi else TextLow)
            }
            Spacer(Modifier.width(4.dp))
            PillButton(
                "Save",
                onClick = {
                    preview.pause()
                    if (project.clips.isEmpty()) app.say("Add a video or photo first") else panel = Panel.EXPORT
                },
                filled = true,
            )
        }

        // ---- preview ----
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (preview.isPlaying) preview.pause() else preview.play()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (project.clips.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Rounded.MovieCreation, contentDescription = null, tint = TextLow, modifier = Modifier.size(54.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Add videos or photos to start", style = MaterialTheme.typography.titleMedium, color = TextMid)
                    Spacer(Modifier.height(16.dp))
                    PillButton("Add clips", onClick = { panel = Panel.ADD }, icon = Icons.Rounded.Add, filled = true)
                }
            } else if (!exporting) {
                // Fit the picture's shape inside the space available.
                val aspect = project.aspect.coerceIn(0.3f, 3.4f)
                val boxAspect = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
                val sized = if (aspect >= boxAspect) {
                    Modifier.size(maxWidth, maxWidth / aspect)
                } else {
                    Modifier.size(maxHeight * aspect, maxHeight)
                }
                // The picture lives in a frame of its own: on its own it would punch its
                // see-through window right across everything drawn before it.
                AndroidView(
                    factory = { ctx ->
                        val surface = SurfaceView(ctx)
                        preview.attach(surface)
                        FrameLayout(ctx).apply {
                            addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                        }
                    },
                    onRelease = { frame -> (frame.getChildAt(0) as? SurfaceView)?.let { preview.detach(it) } },
                    modifier = sized,
                )
                preview.problem?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextHi,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(24.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(14.dp),
                    )
                }
            }
        }

        // ---- transport ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatEditTime(session.positionMs) + "  /  " + formatEditTime(project.durationMs),
                style = MaterialTheme.typography.labelLarge,
                color = TextMid,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(BrandGradient)
                    .clickable(enabled = project.clips.isNotEmpty()) { if (preview.isPlaying) preview.pause() else preview.play() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (preview.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (preview.isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                if (clip == null) "" else "Clip ${session.currentIndex + 1} of ${project.clips.size}",
                style = MaterialTheme.typography.labelLarge,
                color = TextMid,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }

        // ---- timeline ----
        EditorTimeline(
            project = project,
            positionMs = session.positionMs,
            onScrubStart = {
                preview.pause()
                preview.setScrubbing(true)
            },
            onScrub = { seek(it) },
            onScrubEnd = {
                preview.setScrubbing(false)
                seek(session.positionMs, force = true)
            },
            onTapText = {
                preview.pause()
                editingText = it
                panel = Panel.TEXT
            },
            onTapMusic = { panel = Panel.MUSIC },
            modifier = Modifier.background(Surface1),
        )

        // ---- tools ----
        LazyRow(
            Modifier
                .fillMaxWidth()
                .background(Surface1)
                .padding(top = 2.dp, bottom = 6.dp),
            contentPadding = PaddingValues(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            items(tools, key = { it.label }) { tool ->
                val enabled = !tool.needsClip || clip != null
                Column(
                    Modifier
                        .width(62.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled) {
                            preview.pause()
                            panelClipId = clip?.id
                            tool.action()
                        }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        tool.icon,
                        contentDescription = null,
                        tint = if (!enabled) TextLow else if (tool.label == "Delete") Pink else TextHi,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(tool.label, style = MaterialTheme.typography.labelSmall, color = if (enabled) TextMid else TextLow, maxLines = 1)
                }
            }
        }
    }

    // ---- tool panels ----
    val close = { panel = Panel.NONE }
    when (panel) {
        Panel.NONE -> Unit
        Panel.ADD -> ClipPickerSheet(
            onDismiss = close,
            onPickedEntries = { entries -> scope.launch { addClips(ClipProbe.describeEntries(context, entries)) } },
            onPickedUris = { uris -> scope.launch { addClips(ClipProbe.describe(context, uris)) } },
        )
        Panel.MUSIC -> MusicSheet(project, onChange = { session.change(it) }, onDismiss = close)
        Panel.TEXT -> TextSheet(
            project = project,
            existing = editingText,
            playheadMs = session.positionMs,
            onChange = { session.change(it) },
            onDismiss = {
                editingText = null
                close()
            },
        )
        Panel.EMOJI -> EmojiSheet(project, session.positionMs, onChange = { session.change(it) }, onDismiss = close)
        Panel.EXPORT -> ExportSheet(
            project,
            onSave = { quality ->
                // Free the phone's video decoders before the export asks for them.
                preview.release()
                app.exporter.start(session.project, quality)
            },
            onDismiss = close,
        )
        Panel.CANVAS -> CanvasSheet(project, onChange = { session.change(it) }, onDismiss = close)
        else -> {
            val target = project.clips.firstOrNull { it.id == panelClipId }
            if (target == null) {
                LaunchedEffect(Unit) { close() }
            } else {
                when (panel) {
                    Panel.FILTER -> FilterSheet(project, target, onChange = { changeClip(it) }, onDismiss = close)
                    Panel.ADJUST -> AdjustSheet(project, target, onChange = { changeClip(it) }, onDismiss = close)
                    Panel.TRIM -> TrimSheet(project, target, onChange = { changeClip(it) }, onDismiss = close)
                    Panel.SPEED -> SpeedEditSheet(project, target, onChange = { changeClip(it) }, onDismiss = close)
                    Panel.VOLUME -> VolumeSheet(project, target, onChange = { changeClip(it) }, onDismiss = close)
                    else -> Unit
                }
            }
        }
    }

    if (renaming) {
        TextPromptDialog(
            title = "Name this video",
            label = "Name",
            confirm = "Save",
            initial = project.name,
            onDismiss = { renaming = false },
            onConfirm = {
                session.rename(it)
                renaming = false
            },
        )
    }

    if (exporting) {
        ExportOverlay(
            state = export,
            onCancel = { app.exporter.cancel() },
            onDone = { app.exporter.dismiss() },
            onWatch = { entry ->
                app.exporter.dismiss()
                onClose()
                ui.play(app.player, listOf(entry), 0)
            },
        )
    }
}
