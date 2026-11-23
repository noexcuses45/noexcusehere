package com.noexcusehere.mashwave.desktop.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Details
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noexcusehere.mashwave.desktop.AppFolders
import com.noexcusehere.mashwave.desktop.Desk
import com.noexcusehere.mashwave.desktop.LocalFile
import com.noexcusehere.mashwave.desktop.PhotoEditing
import com.noexcusehere.mashwave.desktop.PhotoFiles
import com.noexcusehere.mashwave.desktop.PhotoKind
import com.noexcusehere.mashwave.desktop.PhotoThumbs
import com.noexcusehere.mashwave.desktop.PhotoView
import com.noexcusehere.mashwave.desktop.toScreen
import com.noexcusehere.mashwave.photo.BrushTool
import com.noexcusehere.mashwave.photo.CropTool
import com.noexcusehere.mashwave.photo.DrawTool
import com.noexcusehere.mashwave.photo.FocusTool
import com.noexcusehere.mashwave.photo.HealTool
import com.noexcusehere.mashwave.photo.PhotoOps
import com.noexcusehere.mashwave.photo.PhotoTool
import com.noexcusehere.mashwave.photo.ReshapeTool
import com.noexcusehere.mashwave.photo.ToneTool
import com.noexcusehere.mashwave.photo.bitmapOf
import com.noexcusehere.mashwave.photo.fitWithin
import com.noexcusehere.mashwave.photo.pixels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** The box for picking one picture file. */
fun choosePicture(owner: Frame?): File? = runCatching {
    val dialog = FileDialog(owner, "Open a picture", FileDialog.LOAD)
    // Windows goes by the pattern, a Mac by the filter.
    dialog.file = PhotoFiles.kinds.joinToString(";") { "*.$it" }
    dialog.setFilenameFilter { _, name -> PhotoFiles.isPicture(name) }
    dialog.isVisible = true
    dialog.files?.firstOrNull()
}.getOrNull()

/**
 * The Photo editor page: the photos on this computer in one grid. Clicking one opens it in the
 * photo editor; what is saved there is a new picture - the original is left as it is.
 */
@Composable
fun PhotosScreen(desk: Desk) {
    val library = desk.library
    val photos = library.photos
    var folder by rememberSaveable { mutableStateOf("") }

    val folders = remember(photos) {
        photos.groupBy { File(it.path).parent.orEmpty() }.map { (path, list) -> Triple(path, File(path).name.ifEmpty { path }, list.size) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })
    }
    val shown = remember(photos, folder) { if (folder.isEmpty()) photos else photos.filter { File(it.path).parent.orEmpty() == folder } }

    Row(Modifier.fillMaxSize()) {
        if (folders.size > 1) {
            Column(Modifier.width(230.dp).fillMaxHeight().background(Surface1).padding(top = 18.dp)) {
                Text("Folders", style = MaterialTheme.typography.titleMedium, color = TextHi, modifier = Modifier.padding(start = 18.dp, bottom = 8.dp))
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    item { SideRow("All photos", folder.isEmpty(), { folder = "" }, count = photos.size, icon = Icons.Rounded.PhotoLibrary) }
                    items(folders, key = { it.first }) { (path, name, count) ->
                        SideRow(name, folder == path, { folder = path }, count = count, icon = Icons.Rounded.Folder)
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp, end = 20.dp, top = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Photo editor", style = MaterialTheme.typography.headlineSmall, color = TextHi)
                    Text(
                        when {
                            library.looking -> "Looking through your folders... ${library.foundSoFar} files so far"
                            photos.size == 1 -> "1 photo on this computer"
                            else -> "${photos.size} photos on this computer"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextLow,
                    )
                }
                Pill("Open a picture", { choosePicture(desk.window)?.let { desk.openPhoto(it) } }, icon = Icons.Rounded.FolderOpen)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Click a photo to edit it. What you save is a new picture in Pictures › ${AppFolders.NAME} - the original stays as it is.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMid,
            )

            desk.savedPhoto?.let { saved ->
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Violet.copy(alpha = 0.18f)).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = Pink, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Saved as \"${saved.name}\" in ${saved.parentFile?.name ?: "your pictures"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextHi,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Quiet("Show in folder", { runCatching { Desktop.getDesktop().open(saved.parentFile) }.onFailure { desk.say("The folder could not be opened.") } }, icon = Icons.Rounded.FolderOpen)
                    Spacer(Modifier.width(4.dp))
                    RoundIcon(Icons.Rounded.Close, "Hide this", { desk.savedPhoto = null }, size = 30.dp)
                }
            }
            Spacer(Modifier.height(12.dp))

            when {
                photos.isEmpty() && library.looking -> Busy("Looking for photos...")
                photos.isEmpty() -> Note(
                    Icons.Rounded.PhotoFilter,
                    "No photos found yet",
                    (if (desk.folders.isEmpty()) "Choose the folder your photos are kept in." else "Looked in: " + desk.folders.joinToString(", ") + ". If your photos are somewhere else, choose that folder.") +
                        " You can also open one picture with the button at the top, or drop it onto this window.",
                    action = "Choose a folder",
                    onAction = { Choosers.folder(desk.window)?.let { desk.addFolder(it) } },
                )
                else -> {
                    val state = rememberLazyGridState()
                    Box(Modifier.fillMaxSize()) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(150.dp),
                            state = state,
                            contentPadding = PaddingValues(bottom = 20.dp, end = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(shown, key = { it.path }) { photo -> PhotoCell(photo) { desk.openPhoto(File(photo.path)) } }
                        }
                        GridBar(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoCell(photo: LocalFile, onClick: () -> Unit) {
    val small by produceState(PhotoThumbs.ready(photo), photo.path, photo.changed) {
        value = PhotoThumbs.ready(photo) ?: PhotoThumbs.load(photo)
    }
    Tip(photo.title) {
        Box(
            Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Surface2).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val shown = small
            if (shown != null) {
                Image(shown, contentDescription = photo.title, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Rounded.Photo, contentDescription = null, tint = TextLow, modifier = Modifier.size(30.dp))
            }
        }
    }
}

// ---- the editor ---------------------------------------------------------------------------------

private val PhotoKind.icon: ImageVector
    get() = when (this) {
        PhotoKind.CROP -> Icons.Rounded.Crop
        PhotoKind.AUTO -> Icons.Rounded.AutoFixHigh
        PhotoKind.ADJUST -> Icons.Rounded.Tune
        PhotoKind.LOOKS -> Icons.Rounded.Palette
        PhotoKind.SMOOTH -> Icons.Rounded.Face
        PhotoKind.HEAL -> Icons.Rounded.Healing
        PhotoKind.WHITEN -> Icons.Rounded.Mood
        PhotoKind.DETAILS -> Icons.Rounded.Details
        PhotoKind.LIGHTEN -> Icons.Rounded.LightMode
        PhotoKind.RESHAPE -> Icons.Rounded.Gesture
        PhotoKind.BLUR -> Icons.Rounded.BlurOn
        PhotoKind.DRAW -> Icons.Rounded.Brush
    }

private val PEN_COLOURS = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF3B30.toInt(), 0xFFFF4D9D.toInt(), 0xFF8B5CF6.toInt(),
    0xFF22D3EE.toInt(), 0xFF34C759.toInt(), 0xFFFFD60A.toInt(), 0xFFFF9500.toInt(),
)

private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

/**
 * The photo editor. It covers the whole window. "Save a copy" writes the finished picture as a
 * new file; the file the photo came from is never changed.
 */
@Composable
fun PhotoEditor(editing: PhotoEditing) {
    val density = LocalDensity.current.density
    val margin = with(LocalDensity.current) { 18.dp.toPx() }
    SideEffect {
        editing.density = density
        editing.margin = margin
    }
    val session = editing.session
    val view = editing.view
    val tool = editing.tool
    val kind = editing.kind
    val busy = editing.busy
    var comparing by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            // Nothing underneath should feel a click meant for this screen.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- top bar ----
            Row(Modifier.fillMaxWidth().height(58.dp).padding(start = 10.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Rounded.Close, "Close without saving (Esc)", { if (tool == null) editing.leave() }, enabled = tool == null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(kind?.label ?: "Edit photo", style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1)
                    Text(editing.file.name, style = MaterialTheme.typography.labelSmall, color = TextLow, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Zoom.
                RoundIcon(Icons.Rounded.Remove, "Zoom out (-)", { editing.zoomBy(0.8f) }, size = 32.dp)
                Text("${(view.zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = TextMid, textAlign = TextAlign.Center, modifier = Modifier.width(48.dp))
                RoundIcon(Icons.Rounded.Add, "Zoom in (+)", { editing.zoomBy(1.25f) }, size = 32.dp)
                RoundIcon(Icons.Rounded.FitScreen, "Show the whole photo (0)", { view.reset() }, size = 32.dp)
                Spacer(Modifier.width(14.dp))
                if (tool == null) {
                    RoundIcon(Icons.AutoMirrored.Rounded.Undo, "Undo (Ctrl+Z)", { editing.stepBack() }, enabled = session.canUndo && !busy)
                    RoundIcon(Icons.AutoMirrored.Rounded.Redo, "Redo (Ctrl+Y)", { editing.stepForward() }, enabled = session.canRedo && !busy)
                    Spacer(Modifier.width(6.dp))
                }
                // Press and hold to see the photo as it was.
                Tip("Hold to see the photo as it was") {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (comparing) Violet else Surface3)
                            .pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    comparing = true
                                    tryAwaitRelease()
                                    comparing = false
                                })
                            }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Compare, contentDescription = null, tint = TextHi, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Before", style = MaterialTheme.typography.labelLarge, color = TextHi)
                    }
                }
                if (tool == null) {
                    Spacer(Modifier.width(8.dp))
                    Pill("Save a copy", { editing.done() }, enabled = session.changed && !busy)
                }
            }

            Row(Modifier.weight(1f).fillMaxWidth()) {
                // ---- the picture ----
                Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black), contentAlignment = Alignment.Center) {
                    val picture = session.picture
                    if (picture != null) {
                        PhotoCanvas(
                            picture = picture,
                            tool = tool,
                            // With a tool open, "before" is the photo before this tool; otherwise as it was opened.
                            compare = if (!comparing) null else if (tool != null) tool.base else session.before,
                            view = view,
                            margin = margin,
                            showRing = editing.sizing,
                            // No painting while a change is being worked into the picture.
                            locked = busy,
                        )
                        if (comparing) {
                            Text(
                                "Before",
                                style = MaterialTheme.typography.labelLarge,
                                color = TextHi,
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }
                    if (busy || tool?.working == true) {
                        Box(Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Pink, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                        }
                    }
                }

                // ---- tools, or the open tool's controls ----
                Column(Modifier.width(332.dp).fillMaxHeight().background(Surface1)) {
                    if (tool == null || kind == null) {
                        ToolList(enabled = session.picture != null && !busy) { editing.open(it) }
                    } else {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 8.dp)) {
                            if (kind.hint.isNotEmpty()) {
                                Text(kind.hint, style = MaterialTheme.typography.bodySmall, color = TextMid, modifier = Modifier.padding(horizontal = 18.dp))
                                Spacer(Modifier.height(10.dp))
                            }
                            val brush: @Composable () -> Unit = {
                                val range = PhotoEditing.brushRange(kind)
                                SliderRow(
                                    label = "Brush size",
                                    value = ((editing.brushOf(kind) - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f),
                                    shown = "",
                                    onChange = {
                                        editing.brushTo(kind, range.start + it * (range.endInclusive - range.start))
                                        editing.sizing = true
                                    },
                                    onDone = { editing.sizing = false },
                                )
                            }
                            when (tool) {
                                is CropTool -> CropPanel(tool)
                                is ToneTool -> if (kind == PhotoKind.LOOKS) LooksPanel(tool) else AdjustPanel(tool)
                                is BrushTool -> BrushPanel(tool, brush)
                                is HealTool -> {
                                    brush()
                                    ActionRow { Quiet("Undo last", { tool.undo() }, enabled = tool.canUndo) }
                                }
                                is ReshapeTool -> {
                                    brush()
                                    ActionRow {
                                        Quiet("Undo last", { tool.undo() }, enabled = tool.canUndo)
                                        Quiet("Reset", { tool.reset() })
                                    }
                                }
                                is FocusTool -> {
                                    SliderRow("Blur", tool.amount, "${(tool.amount * 100).roundToInt()}%", { tool.changeAmount(it) })
                                    SliderRow("Sharp area", tool.spread, "${(tool.spread * 100).roundToInt()}%", { tool.changeSpread(it) })
                                }
                                is DrawTool -> {
                                    PenColours(tool)
                                    brush()
                                    ActionRow { Quiet("Undo last", { tool.undo() }, enabled = tool.canUndo) }
                                }
                                else -> Unit
                            }
                        }
                        // ---- keep or drop ----
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Quiet("Cancel", { editing.closeTool() }, Modifier.weight(1f), icon = Icons.Rounded.Close)
                            Pill("Keep", { editing.apply() }, Modifier.weight(1f), icon = Icons.Rounded.Check, enabled = !busy && !tool.working)
                        }
                    }
                }
            }
        }

        if (editing.leaving) {
            Sheet("Leave without saving?", onClose = { editing.leaving = false }, width = 420.dp) {
                Text("The changes to this photo will be lost.", style = MaterialTheme.typography.bodyMedium, color = TextMid)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Quiet("Keep editing", { editing.leaving = false })
                    Pill("Leave", { editing.leaveNow() })
                }
            }
        }
    }
}

@Composable
private fun ToolList(enabled: Boolean, onOpen: (PhotoKind) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Tools", style = MaterialTheme.typography.titleMedium, color = TextHi, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        PhotoKind.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(bottom = 8.dp)
                            .dimmedUnless(enabled)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Surface2)
                            .clickable(enabled = enabled) { onOpen(item) }
                            .padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(item.icon, contentDescription = null, tint = TextHi, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(item.label, style = MaterialTheme.typography.labelMedium, color = TextMid, maxLines = 1)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Turn the mouse wheel to zoom in and out. Hold the right mouse button and drag to move around the photo.",
            style = MaterialTheme.typography.bodySmall,
            color = TextLow,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** The picture itself: the wheel zooms, the right button moves it, the left button works the open tool. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PhotoCanvas(picture: Bitmap, tool: PhotoTool?, compare: Bitmap?, view: PhotoView, margin: Float, showRing: Boolean, locked: Boolean) {
    val current by rememberUpdatedState(picture)
    val isLocked by rememberUpdatedState(locked)
    var pointer by remember { mutableStateOf<Offset?>(null) }

    Canvas(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(tool) {
                awaitPointerEventScope {
                    // 0: nothing held, 1: the tool is being worked, 2: the picture is being moved
                    var doing = 0
                    var last = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val area = Size(size.width.toFloat(), size.height.toFloat())
                        val w = tool?.width ?: current.width
                        val h = tool?.height ?: current.height
                        val scale = view.scale(area, w, h, margin)
                        fun pictureX(x: Float) = (x - view.left(area, w, h, margin)) / scale
                        fun pictureY(y: Float) = (y - view.top(area, w, h, margin)) / scale
                        val at = change.position
                        when (event.type) {
                            PointerEventType.Press -> {
                                if (doing == 0) {
                                    // Tools that take no touch of their own let the left button move the picture too.
                                    val works = tool != null && tool !is ToneTool
                                    if (event.buttons.isPrimaryPressed && works && !isLocked) {
                                        tool.down(pictureX(at.x), pictureY(at.y), scale)
                                        doing = 1
                                    } else {
                                        doing = 2
                                    }
                                    last = at
                                }
                                pointer = at
                            }
                            PointerEventType.Move -> {
                                if (doing == 1 && tool != null) {
                                    tool.move(pictureX(at.x), pictureY(at.y), scale)
                                } else if (doing == 2) {
                                    view.gesture(area, w, h, margin, last, at - last, 1f)
                                }
                                last = at
                                pointer = at
                            }
                            PointerEventType.Release -> {
                                if (!event.buttons.areAnyPressed) {
                                    if (doing == 1) tool?.up()
                                    doing = 0
                                }
                                pointer = at
                            }
                            PointerEventType.Scroll -> {
                                val turned = change.scrollDelta.y
                                if (turned != 0f) view.gesture(area, w, h, margin, at, Offset.Zero, 1.12f.pow(-turned.coerceIn(-6f, 6f)))
                            }
                            PointerEventType.Exit -> if (doing == 0) pointer = null
                            else -> Unit
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        tool?.tick
        view.area = size
        val w = tool?.width ?: picture.width
        val h = tool?.height ?: picture.height
        val scale = view.scale(size, w, h, margin)
        val left = view.left(size, w, h, margin)
        val top = view.top(size, w, h, margin)
        drawIntoCanvas { canvas ->
            val native = android.graphics.Canvas(canvas.skiaCanvas)
            native.save()
            if (compare != null) {
                val sameShape = abs(compare.width.toFloat() / compare.height - w.toFloat() / h) < 0.01f
                if (sameShape) {
                    // Same place, same zoom - only the picture swaps, so the change is easy to judge.
                    native.translate(left, top)
                    native.scale(scale, scale)
                    native.drawBitmap(compare, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), bitmapPaint)
                } else {
                    val fit = min((size.width - 2 * margin) / compare.width, (size.height - 2 * margin) / compare.height)
                    native.translate((size.width - compare.width * fit) / 2f, (size.height - compare.height * fit) / 2f)
                    native.scale(fit, fit)
                    native.drawBitmap(compare, 0f, 0f, bitmapPaint)
                }
            } else {
                native.translate(left, top)
                native.scale(scale, scale)
                if (tool != null) {
                    tool.draw(native)
                    tool.guides(native, scale)
                } else {
                    native.drawBitmap(picture, 0f, 0f, bitmapPaint)
                }
            }
            native.restore()
        }
        // A ring the size of the brush: under the mouse, or in the middle while its size is being set.
        if (tool != null && tool.hasBrush && compare == null) {
            val at = if (showRing) Offset(size.width / 2f, size.height / 2f) else pointer
            if (at != null) {
                drawCircle(Color.Black.copy(alpha = 0.45f), radius = tool.size + 1.dp.toPx(), center = at, style = Stroke(1.dp.toPx()))
                drawCircle(Color.White, radius = tool.size, center = at, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** A named line with a knob. [mark] (0 to 1) is where "no change" sits, for settings that go both ways. */
@Composable
private fun SliderRow(label: String, value: Float, shown: String, onChange: (Float) -> Unit, onDone: (() -> Unit)? = null, mark: Float = -1f) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 3.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.labelLarge, color = TextMid, modifier = Modifier.weight(1f))
            Text(shown, style = MaterialTheme.typography.labelLarge, color = TextHi)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val pad = 8.dp.toPx()
                        fun at(x: Float) = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                        val down = awaitFirstDown()
                        onChange(at(down.position.x))
                        drag(down.id) { change ->
                            onChange(at(change.position.x))
                            change.consume()
                        }
                        onDone?.invoke()
                    }
                },
        ) {
            val pad = 8.dp.toPx()
            val middle = size.height / 2
            val thick = 4.dp.toPx()
            val run = size.width - 2 * pad
            val knob = pad + run * value.coerceIn(0f, 1f)
            drawRoundRect(Color.White.copy(alpha = 0.2f), Offset(pad, middle - thick / 2), Size(run, thick), CornerRadius(thick / 2))
            val from = if (mark >= 0f) pad + run * mark else pad
            drawRoundRect(Pink, Offset(min(from, knob), middle - thick / 2), Size(abs(knob - from), thick), CornerRadius(thick / 2))
            if (mark >= 0f) drawRect(Color.White.copy(alpha = 0.55f), Offset(from - 1.dp.toPx(), middle - 6.dp.toPx()), Size(2.dp.toPx(), 12.dp.toPx()))
            drawCircle(Color.White, 7.dp.toPx(), Offset(knob, middle))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

private val CROP_SHAPES: List<Pair<String, Float?>> = listOf(
    "Square" to 1f,
    "4:5" to 4f / 5f,
    "9:16" to 9f / 16f,
    "16:9" to 16f / 9f,
    "3:4" to 3f / 4f,
    "4:3" to 4f / 3f,
    "5:4" to 5f / 4f,
)

@Composable
private fun CropPanel(tool: CropTool) {
    Text("Shape", style = MaterialTheme.typography.labelLarge, color = TextMid, modifier = Modifier.padding(horizontal = 18.dp))
    ActionRow {
        Chip(CropTool.FREE, selected = tool.shape == CropTool.FREE, onClick = { tool.choose(CropTool.FREE, null) })
        Chip(CropTool.ORIGINAL, selected = tool.shape == CropTool.ORIGINAL, onClick = { tool.choose(CropTool.ORIGINAL, tool.ownAspect) })
        CROP_SHAPES.forEach { (name, ratio) ->
            Chip(name, selected = tool.shape == name, onClick = { tool.choose(name, ratio) })
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CropAction("Turn", Icons.AutoMirrored.Rounded.RotateRight, Modifier.weight(1f)) { tool.turn() }
        CropAction("Mirror", Icons.Rounded.Flip, Modifier.weight(1f)) { tool.flip(upsideDown = false) }
        CropAction("Flip", Icons.Rounded.Flip, Modifier.weight(1f), quarterTurn = true) { tool.flip(upsideDown = true) }
    }
}

@Composable
private fun CropAction(label: String, icon: ImageVector, modifier: Modifier = Modifier, quarterTurn: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Surface2).clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = TextHi, modifier = Modifier.size(24.dp).rotate(if (quarterTurn) 90f else 0f))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextMid, maxLines = 1)
    }
}

/** One of the adjustments: its name, how to read it, how to change it, and whether it goes both ways from the middle. */
private class Setting(val label: String, val bothWays: Boolean, val get: (PhotoOps.Adjust) -> Float, val set: (PhotoOps.Adjust, Float) -> PhotoOps.Adjust)

private val SETTINGS = listOf(
    Setting("Brightness", true, { it.brightness }, { a, v -> a.copy(brightness = v) }),
    Setting("Contrast", true, { it.contrast }, { a, v -> a.copy(contrast = v) }),
    Setting("Colour", true, { it.saturation }, { a, v -> a.copy(saturation = v) }),
    Setting("Warmth", true, { it.warmth }, { a, v -> a.copy(warmth = v) }),
    Setting("Highlights", true, { it.highlights }, { a, v -> a.copy(highlights = v) }),
    Setting("Shadows", true, { it.shadows }, { a, v -> a.copy(shadows = v) }),
    Setting("Sharpen", false, { it.sharpen }, { a, v -> a.copy(sharpen = v) }),
    Setting("Fade", false, { it.fade }, { a, v -> a.copy(fade = v) }),
    Setting("Vignette", false, { it.vignette }, { a, v -> a.copy(vignette = v) }),
)

/** Every adjustment with a slider of its own: a computer's window has the room for them all at once. */
@Composable
private fun AdjustPanel(tool: ToneTool) {
    SETTINGS.forEach { setting ->
        val value = setting.get(tool.values)
        if (setting.bothWays) {
            SliderRow(
                label = setting.label,
                value = (value + 1f) / 2f,
                shown = (if (value > 0) "+" else "") + (value * 100).roundToInt(),
                onChange = {
                    val next = it * 2f - 1f
                    tool.set(setting.set(tool.values, if (abs(next) < 0.04f) 0f else next))
                },
                mark = 0.5f,
            )
        } else {
            SliderRow(
                label = setting.label,
                value = value,
                shown = (value * 100).roundToInt().toString(),
                onChange = { tool.set(setting.set(tool.values, if (it < 0.03f) 0f else it)) },
            )
        }
    }
    ActionRow { Quiet("Reset", { tool.set(PhotoOps.Adjust()) }, enabled = tool.dirty) }
}

@Composable
private fun LooksPanel(tool: ToneTool) {
    var look by remember { mutableStateOf(PhotoOps.Look.NONE) }
    var amount by remember { mutableFloatStateOf(1f) }
    // Small samples of every look on this very photo.
    val samples by produceState<List<ImageBitmap>?>(null, tool) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val small = tool.base.fitWithin(200)
                val pixels = small.pixels()
                PhotoOps.Look.entries.map { bitmapOf(PhotoOps.adjust(pixels, small.width, small.height, it.recipe), small.width, small.height).toScreen() }
            }.getOrNull()
        }
    }
    PhotoOps.Look.entries.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { item ->
                val chosen = item == look
                Column(
                    Modifier
                        .weight(1f)
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            look = item
                            tool.set(item.recipe.scaled(amount))
                        }
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Surface2)
                            .border(if (chosen) 2.dp else 0.dp, if (chosen) Pink else Color.Transparent, RoundedCornerShape(10.dp)),
                    ) {
                        samples?.getOrNull(item.ordinal)?.let {
                            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(item.label, style = MaterialTheme.typography.labelMedium, color = if (chosen) TextHi else TextMid, maxLines = 1)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    Spacer(Modifier.height(4.dp))
    SliderRow(
        label = "Strength",
        value = amount,
        shown = "${(amount * 100).roundToInt()}%",
        onChange = {
            amount = it
            tool.set(look.recipe.scaled(it))
        },
    )
}

@Composable
private fun BrushPanel(tool: BrushTool, brush: @Composable () -> Unit) {
    if (tool.failed) {
        Text(PhotoEditing.NO_MEMORY, style = MaterialTheme.typography.bodySmall, color = Pink, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
        return
    }
    ActionRow {
        Chip("Paint", selected = !tool.erasing, onClick = { tool.erasing = false })
        Chip("Rub out", selected = tool.erasing, onClick = { tool.erasing = true })
    }
    brush()
    SliderRow("Strength", tool.strength, "${(tool.strength * 100).roundToInt()}%", { tool.strength = it })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PenColours(tool: DrawTool) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PEN_COLOURS.forEach { colour ->
            val chosen = tool.colour == colour
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(colour))
                    .border(if (chosen) 3.dp else 1.dp, if (chosen) Pink else Outline, CircleShape)
                    .clickable { tool.colour = colour },
            )
        }
    }
}
