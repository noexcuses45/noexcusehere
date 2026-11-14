package com.noexcusehere.mashwave.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.editor.ClipProbe
import com.noexcusehere.mashwave.editor.EditProject
import com.noexcusehere.mashwave.editor.EditorOps
import com.noexcusehere.mashwave.editor.FrameCache
import com.noexcusehere.mashwave.ui.LocalUi
import com.noexcusehere.mashwave.ui.MwSheet
import com.noexcusehere.mashwave.ui.components.ConfirmDialog
import com.noexcusehere.mashwave.ui.components.ListBottomPadding
import com.noexcusehere.mashwave.ui.components.ScreenHeader
import com.noexcusehere.mashwave.ui.components.SheetAction
import com.noexcusehere.mashwave.ui.components.TextPromptDialog
import com.noexcusehere.mashwave.ui.theme.BrandGradient
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.Surface3
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The "Edit" tab: start a new video or carry on with one you were making. */
@Composable
fun EditorHomeScreen() {
    val context = LocalContext.current
    val app = context.app
    val ui = LocalUi.current
    val scope = rememberCoroutineScope()
    val projects by app.editorStore.projects.collectAsStateWithLifecycle()
    val export by app.exporter.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<EditProject?>(null) }
    var renaming by remember { mutableStateOf<EditProject?>(null) }
    var deleting by remember { mutableStateOf<EditProject?>(null) }

    fun startWith(clips: List<com.noexcusehere.mashwave.editor.EditClip>) {
        if (clips.isEmpty()) {
            app.say("Those files couldn't be opened")
            return
        }
        val created = app.editorStore.create("My video ${projects.size + 1}")
        app.editorStore.save(EditorOps.addClips(created, clips))
        ui.editingProjectId = created.id
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = ListBottomPadding,
    ) {
        item { ScreenHeader("Video editor") }
        item {
            // The big "start here" card.
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(BrandGradient)
                    .clickable { picking = true }
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("New video", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text(
                        "Join clips and photos, trim, add your music, text and filters",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
            }
        }
        if (export.running) {
            item {
                Column(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Surface2)
                        .clickable { export.projectId?.let { id -> if (app.editorStore.get(id) != null) ui.editingProjectId = id } }
                        .padding(16.dp),
                ) {
                    Text("Saving “${export.name}”  ·  ${(export.progress * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium, color = TextHi)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { export.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Pink,
                        trackColor = Surface3,
                    )
                }
            }
        }
        if (projects.isEmpty()) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Rounded.MovieCreation, contentDescription = null, tint = TextLow, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Your videos in progress will show up here", style = MaterialTheme.typography.bodyMedium, color = TextMid)
                }
            }
        } else {
            item {
                Text(
                    "Your projects",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextHi,
                    modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
                )
            }
            items(projects, key = { it.id }) { project ->
                ProjectRow(project, onOpen = { ui.editingProjectId = project.id }, onMore = { menuFor = project })
            }
        }
    }

    if (picking) {
        ClipPickerSheet(
            onDismiss = { picking = false },
            onPickedEntries = { entries -> scope.launch { startWith(ClipProbe.describeEntries(context, entries)) } },
            onPickedUris = { uris -> scope.launch { startWith(ClipProbe.describe(context, uris)) } },
        )
    }
    menuFor?.let { project ->
        MwSheet(onDismiss = { menuFor = null }) {
            Text(
                project.name,
                style = MaterialTheme.typography.titleLarge,
                color = TextHi,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            )
            SheetAction(Icons.Rounded.Edit, "Rename", onClick = {
                renaming = project
                menuFor = null
            })
            SheetAction(Icons.Rounded.ContentCopy, "Make a copy", onClick = {
                app.editorStore.duplicate(project.id)
                menuFor = null
            })
            SheetAction(Icons.Rounded.DeleteOutline, "Delete project", tint = Pink, onClick = {
                deleting = project
                menuFor = null
            })
        }
    }
    renaming?.let { project ->
        TextPromptDialog(
            title = "Rename project",
            label = "Name",
            confirm = "Save",
            initial = project.name,
            onDismiss = { renaming = null },
            onConfirm = {
                app.editorStore.rename(project.id, it)
                renaming = null
            },
        )
    }
    deleting?.let { project ->
        ConfirmDialog(
            title = "Delete “${project.name}”?",
            message = "The project is removed. Your original videos, photos and any video you already saved stay untouched.",
            confirm = "Delete",
            onDismiss = { deleting = null },
            onConfirm = {
                app.editorStore.delete(project.id)
                deleting = null
            },
        )
    }
}

@Composable
private fun ProjectRow(project: EditProject, onOpen: () -> Unit, onMore: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val first = project.clips.firstOrNull()
        Canvas(
            Modifier
                .size(width = 96.dp, height = 60.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Surface2),
        ) {
            FrameCache.generation.intValue
            val image = first?.let { FrameCache.get(context, scope, it, it.startMs) } ?: return@Canvas
            // Centre-crop the first frame into the thumbnail.
            val boxAspect = size.width / size.height
            val imageAspect = image.width.toFloat() / image.height
            val srcW = if (imageAspect > boxAspect) (image.height * boxAspect).roundToInt() else image.width
            val srcH = if (imageAspect > boxAspect) image.height else (image.width / boxAspect).roundToInt()
            drawImage(
                image = image,
                srcOffset = IntOffset((image.width - srcW) / 2, (image.height - srcH) / 2),
                srcSize = IntSize(srcW, srcH),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(project.name, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val clips = project.clips.size
            Text(
                "$clips ${if (clips == 1) "clip" else "clips"}  ·  ${formatEditTime(project.durationMs)}  ·  ${ago(project.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMid,
                maxLines = 1,
            )
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "More options", tint = TextMid)
        }
    }
}

private fun ago(time: Long): String {
    val minutes = (System.currentTimeMillis() - time) / 60_000
    return when {
        time <= 0 -> ""
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} d ago"
    }
}
