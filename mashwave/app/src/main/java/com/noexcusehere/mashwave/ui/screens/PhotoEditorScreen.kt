package com.noexcusehere.mashwave.ui.screens

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Details
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.editor.EditClip
import com.noexcusehere.mashwave.photo.BrushTool
import com.noexcusehere.mashwave.photo.CropTool
import com.noexcusehere.mashwave.photo.DrawTool
import com.noexcusehere.mashwave.photo.FocusTool
import com.noexcusehere.mashwave.photo.HealTool
import com.noexcusehere.mashwave.photo.PhotoFiles
import com.noexcusehere.mashwave.photo.PhotoOps
import com.noexcusehere.mashwave.photo.PhotoSession
import com.noexcusehere.mashwave.photo.PhotoTool
import com.noexcusehere.mashwave.photo.ReshapeTool
import com.noexcusehere.mashwave.photo.SavedPhoto
import com.noexcusehere.mashwave.photo.ToneTool
import com.noexcusehere.mashwave.photo.bitmapOf
import com.noexcusehere.mashwave.photo.fitWithin
import com.noexcusehere.mashwave.photo.pixels
import com.noexcusehere.mashwave.ui.components.Chip
import com.noexcusehere.mashwave.ui.components.ConfirmDialog
import com.noexcusehere.mashwave.ui.components.MwSlider
import com.noexcusehere.mashwave.ui.components.PillButton
import com.noexcusehere.mashwave.ui.theme.BrandGradient
import com.noexcusehere.mashwave.ui.theme.Ink
import com.noexcusehere.mashwave.ui.theme.Outline
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface1
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The photo editor's tools, in the order they appear along the bottom. */
private enum class PhotoKind(val label: String, val icon: ImageVector, val hint: String, val brushDp: Float = 0f) {
    CROP("Crop", Icons.Rounded.Crop, "Drag a corner or an edge. Drag inside to move the frame."),
    AUTO("Auto", Icons.Rounded.AutoFixHigh, ""),
    ADJUST("Adjust", Icons.Rounded.Tune, "Pick a setting, then slide."),
    LOOKS("Looks", Icons.Rounded.Palette, "Pick a look, then set how strong it is."),
    SMOOTH("Smooth", Icons.Rounded.Face, "Paint over skin to even it out. Eyes, lips and hair stay crisp.", 30f),
    HEAL("Heal", Icons.Rounded.Healing, "Dab or paint over a spot and it is gone.", 14f),
    WHITEN("Whiten", Icons.Rounded.Mood, "Paint over teeth or the whites of eyes.", 14f),
    DETAILS("Details", Icons.Rounded.Details, "Paint over eyes, hair or anything that should be crisper.", 22f),
    LIGHTEN("Lighten", Icons.Rounded.LightMode, "Paint where the photo should be brighter.", 34f),
    RESHAPE("Reshape", Icons.Rounded.Gesture, "Push gently with one finger to nudge a shape.", 48f),
    BLUR("Blur", Icons.Rounded.BlurOn, "Drag the circle onto what should stay sharp."),
    DRAW("Draw", Icons.Rounded.Brush, "Draw with one finger.", 5f),
    ORIGINAL("Start over", Icons.Rounded.RestartAlt, ""),
}

/** How far the picture is zoomed in and where it has been moved to. */
private class PhotoView {
    var zoom by mutableFloatStateOf(1f)
    var shiftX by mutableFloatStateOf(0f)
    var shiftY by mutableFloatStateOf(0f)

    fun reset() {
        zoom = 1f
        shiftX = 0f
        shiftY = 0f
    }

    /** Screen pixels per picture pixel when a picture [w] by [h] is shown in [area] with [margin] around it. */
    fun scale(area: Size, w: Int, h: Int, margin: Float): Float = PhotoOps.fitScale(area.width, area.height, w, h, margin) * zoom

    fun left(area: Size, w: Int, h: Int, margin: Float): Float = area.width / 2f + shiftX - w * scale(area, w, h, margin) / 2f

    fun top(area: Size, w: Int, h: Int, margin: Float): Float = area.height / 2f + shiftY - h * scale(area, w, h, margin) / 2f

    /** Two fingers (or one, with [zoomBy] 1): whatever was under [from] follows it by [pan], zoomed by [zoomBy]. */
    fun gesture(area: Size, w: Int, h: Int, margin: Float, from: Offset, pan: Offset, zoomBy: Float) {
        val next = PhotoOps.viewAfter(floatArrayOf(zoom, shiftX, shiftY), area.width, area.height, w, h, margin, from.x, from.y, pan.x, pan.y, zoomBy)
        zoom = next[0]
        shiftX = next[1]
        shiftY = next[2]
    }
}

/**
 * The photo editor: opens over the video editor for one photo on the timeline. "Done" writes the
 * edited picture to the app's own storage and hands it back through [onSaved]; the file the photo
 * came from is never changed.
 */
@Composable
fun PhotoEditorScreen(clip: EditClip, onClose: () -> Unit, onSaved: (SavedPhoto) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val session = remember(clip.id) { PhotoSession(context.applicationContext) }
    val view = remember(clip.id) { PhotoView() }
    var tool by remember { mutableStateOf<PhotoTool?>(null) }
    var kind by remember { mutableStateOf<PhotoKind?>(null) }
    var busy by remember { mutableStateOf(true) }
    var comparing by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var sizing by remember { mutableStateOf(false) }
    val brushes = remember { mutableStateMapOf<PhotoKind, Float>() }
    // The row of tools stays where it was scrolled to while a tool is open.
    val toolRow = rememberLazyListState()

    LaunchedEffect(clip.id) {
        if (!session.open(clip.uri, clip.rotation, clip.mirrored)) {
            app.say("That photo couldn't be opened")
            onClose()
        }
        busy = false
    }
    DisposableEffect(clip.id) { onDispose { tool?.close() } }

    fun closeTool() {
        tool?.close()
        tool = null
        kind = null
        sizing = false
    }

    fun brushOf(which: PhotoKind): Float = brushes[which] ?: which.brushDp

    fun open(which: PhotoKind) {
        val picture = session.picture ?: return
        if (busy) return
        when (which) {
            PhotoKind.AUTO -> {
                busy = true
                scope.launch {
                    val out = withContext(Dispatchers.Default) {
                        runCatching { bitmapOf(PhotoOps.auto(picture.pixels(), picture.width, picture.height), picture.width, picture.height) }.getOrNull()
                    }
                    if (out != null) session.commit(out) else app.say(NO_MEMORY)
                    busy = false
                }
                return
            }
            PhotoKind.ORIGINAL -> {
                busy = true
                scope.launch {
                    val first = withContext(Dispatchers.Default) {
                        runCatching { PhotoFiles.read(context.applicationContext, clip.original.ifEmpty { clip.uri }, 0, false) }.getOrNull()
                    }
                    if (first != null) {
                        session.commit(first)
                        view.reset()
                    } else {
                        app.say("The first version of this photo can't be found any more")
                    }
                    busy = false
                }
                return
            }
            else -> Unit
        }
        val made = runCatching {
            when (which) {
                PhotoKind.CROP -> CropTool(picture, density)
                PhotoKind.ADJUST, PhotoKind.LOOKS -> ToneTool(picture, scope)
                PhotoKind.SMOOTH -> BrushTool(picture, scope) { pixels, w, h -> PhotoOps.smooth(pixels, w, h) }
                PhotoKind.WHITEN -> BrushTool(picture, scope) { pixels, _, _ -> PhotoOps.whiten(pixels) }
                PhotoKind.DETAILS -> BrushTool(picture, scope) { pixels, w, h -> PhotoOps.sharpen(pixels, w, h, 1.3f, max(1, min(w, h) / 500)) }
                PhotoKind.LIGHTEN -> BrushTool(picture, scope) { pixels, w, h -> PhotoOps.adjust(pixels, w, h, PhotoOps.Adjust(brightness = 0.28f, shadows = 0.3f)) }
                PhotoKind.HEAL -> HealTool(picture, scope)
                PhotoKind.RESHAPE -> ReshapeTool(picture)
                PhotoKind.BLUR -> FocusTool(picture, scope)
                PhotoKind.DRAW -> DrawTool(picture)
                else -> null
            }
        }.getOrNull()
        if (made == null) {
            app.say(NO_MEMORY)
            return
        }
        made.size = brushOf(which) * density
        if (which == PhotoKind.CROP) view.reset()
        tool = made
        kind = which
    }

    fun apply() {
        val open = tool ?: return
        if (busy || open.working) return
        if (!open.dirty) {
            closeTool()
            return
        }
        busy = true
        scope.launch {
            val out = withContext(Dispatchers.Default) { runCatching { open.result() }.getOrNull() }
            if (out != null) session.commit(out) else app.say(NO_MEMORY)
            if (kind == PhotoKind.CROP) view.reset()
            closeTool()
            busy = false
        }
    }

    fun done() {
        if (busy) return
        if (!session.changed) {
            onClose()
            return
        }
        busy = true
        scope.launch {
            val saved = session.save(clip.id)
            busy = false
            if (saved == null) app.say("The photo couldn't be saved. Is the phone's storage full?") else onSaved(saved)
        }
    }

    BackHandler {
        when {
            busy -> Unit
            tool != null -> closeTool()
            session.changed -> leaving = true
            else -> onClose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            // Nothing underneath should feel a touch meant for this screen.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // ---- top bar ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tool == null) {
                    IconButton(onClick = { if (!busy) if (session.changed) leaving = true else onClose() }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close without saving", tint = TextHi)
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                // The title sits on a filled, clipped patch of its own, exactly like the buttons
                // beside it: drawn straight onto the bar it did not show on a test phone.
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Ink)
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                ) {
                    Text(
                        kind?.label ?: "Edit photo",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextHi,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (tool == null) {
                    IconButton(onClick = { if (!busy) scope.launch { session.undo().also { view.reset() } } }, enabled = session.canUndo) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo", tint = if (session.canUndo) TextHi else TextLow)
                    }
                    IconButton(onClick = { if (!busy) scope.launch { session.redo().also { view.reset() } } }, enabled = session.canRedo) {
                        Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = "Redo", tint = if (session.canRedo) TextHi else TextLow)
                    }
                }
                // Press and hold to see the photo as it was.
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (comparing) Surface2 else Color.Transparent)
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                comparing = true
                                tryAwaitRelease()
                                comparing = false
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Compare, contentDescription = "Hold to see the photo as it was", tint = TextHi)
                }
                if (tool == null) {
                    Spacer(Modifier.width(6.dp))
                    PillButton("Done", onClick = { done() }, filled = true)
                }
            }

            // ---- the picture ----
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val picture = session.picture
                if (picture != null) {
                    val open = tool
                    PhotoCanvas(
                        picture = picture,
                        tool = open,
                        // With a tool open, "before" is the photo before this tool; otherwise as it was opened.
                        compare = if (!comparing) null else if (open != null) open.base else session.before,
                        view = view,
                        showRing = sizing,
                        // No painting while a change is being worked into the picture.
                        locked = busy,
                    )
                    if (comparing) {
                        Text(
                            "Before",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextHi,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
                if (busy || tool?.working == true) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Pink, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                    }
                }
            }

            // ---- tools, or the open tool's controls ----
            val open = tool
            val which = kind
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Surface1),
            ) {
                if (open == null || which == null) {
                    val kinds = remember(clip.original) { PhotoKind.entries.filter { it != PhotoKind.ORIGINAL || clip.original.isNotEmpty() } }
                    LazyRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 8.dp),
                        state = toolRow,
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        items(kinds, key = { it.name }) { item ->
                            Column(
                                Modifier
                                    .width(70.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = session.picture != null) { open(item) }
                                    .padding(vertical = 9.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(item.icon, contentDescription = null, tint = TextHi, modifier = Modifier.size(25.dp))
                                Spacer(Modifier.height(5.dp))
                                Text(item.label, style = MaterialTheme.typography.labelSmall, color = TextMid, maxLines = 1)
                            }
                        }
                    }
                } else {
                    if (which.hint.isNotEmpty()) {
                        Text(
                            which.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextLow,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 10.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    val brush: @Composable () -> Unit = {
                        val range = if (which == PhotoKind.DRAW) 2f..30f else 6f..90f
                        val now = brushOf(which)
                        SliderRow(
                            label = "Brush size",
                            value = ((now - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f),
                            shown = "",
                            onChange = {
                                val next = range.start + it * (range.endInclusive - range.start)
                                brushes[which] = next
                                open.size = next * density
                                sizing = true
                            },
                            onDone = { sizing = false },
                        )
                    }
                    when (open) {
                        is CropTool -> CropPanel(open)
                        is ToneTool -> if (which == PhotoKind.LOOKS) LooksPanel(open) else AdjustPanel(open)
                        is BrushTool -> BrushPanel(open, brush)
                        is HealTool -> {
                            brush()
                            ActionRow { PillButton("Undo last", onClick = { open.undo() }, enabled = open.canUndo) }
                        }
                        is ReshapeTool -> {
                            brush()
                            ActionRow {
                                PillButton("Undo last", onClick = { open.undo() }, enabled = open.canUndo)
                                PillButton("Reset", onClick = { open.reset() })
                            }
                        }
                        is FocusTool -> {
                            SliderRow("Blur", open.amount, "${(open.amount * 100).roundToInt()}%", { open.changeAmount(it) })
                            SliderRow("Sharp area", open.spread, "${(open.spread * 100).roundToInt()}%", { open.changeSpread(it) })
                        }
                        is DrawTool -> {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 18.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PEN_COLOURS.forEach { colour ->
                                    val chosen = open.colour == colour
                                    Box(
                                        Modifier
                                            .size(if (chosen) 36.dp else 30.dp)
                                            .clip(CircleShape)
                                            .background(Color(colour))
                                            .border(if (chosen) 3.dp else 1.dp, if (chosen) Pink else Outline, CircleShape)
                                            .clickable { open.colour = colour },
                                    )
                                }
                            }
                            brush()
                            ActionRow { PillButton("Undo last", onClick = { open.undo() }, enabled = open.canUndo) }
                        }
                        else -> Unit
                    }
                    // ---- keep or drop ----
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Surface2)
                                .clickable { if (!busy) closeTool() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Drop this change", tint = TextHi)
                        }
                        Text(
                            which.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextMid,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(BrandGradient)
                                .clickable { apply() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Check, contentDescription = "Keep this change", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    if (leaving) {
        ConfirmDialog(
            title = "Leave without saving?",
            message = "The changes to this photo will be lost.",
            confirm = "Leave",
            onDismiss = { leaving = false },
            onConfirm = {
                leaving = false
                onClose()
            },
        )
    }
}

private const val NO_MEMORY = "That didn't work - the phone ran short of memory. Close other apps and try again."

private val PEN_COLOURS = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF3B30.toInt(), 0xFFFF4D9D.toInt(), 0xFF8B5CF6.toInt(),
    0xFF22D3EE.toInt(), 0xFF34C759.toInt(), 0xFFFFD60A.toInt(), 0xFFFF9500.toInt(),
)

private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

/** The picture itself: two fingers zoom and move it, one finger works the open tool. */
@Composable
private fun PhotoCanvas(picture: Bitmap, tool: PhotoTool?, compare: Bitmap?, view: PhotoView, showRing: Boolean, locked: Boolean) {
    val margin = with(LocalDensity.current) { 14.dp.toPx() }
    val current by rememberUpdatedState(picture)
    val isLocked by rememberUpdatedState(locked)
    var finger by remember { mutableStateOf<Offset?>(null) }

    Canvas(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(tool) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    first.consume()
                    val area = Size(size.width.toFloat(), size.height.toFloat())
                    var w = tool?.width ?: current.width
                    var h = tool?.height ?: current.height
                    var scale = view.scale(area, w, h, margin)
                    fun pictureX(x: Float) = (x - view.left(area, w, h, margin)) / scale
                    fun pictureY(y: Float) = (y - view.top(area, w, h, margin)) / scale
                    // Tools that take no touch of their own let one finger move the picture.
                    val works = tool != null && tool !is ToneTool
                    var stroke = false
                    var two = false
                    if (works && tool != null && !isLocked) {
                        tool.down(pictureX(first.position.x), pictureY(first.position.y), scale)
                        stroke = true
                        finger = first.position
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        w = tool?.width ?: current.width
                        h = tool?.height ?: current.height
                        if (pressed.size >= 2) {
                            if (stroke) {
                                tool?.cancel()
                                stroke = false
                            }
                            two = true
                            finger = null
                            view.gesture(area, w, h, margin, event.calculateCentroid(useCurrent = false), event.calculatePan(), event.calculateZoom())
                        } else if (!two) {
                            val change = pressed[0]
                            if (stroke && tool != null) {
                                scale = view.scale(area, w, h, margin)
                                tool.move(pictureX(change.position.x), pictureY(change.position.y), scale)
                                finger = change.position
                            } else if (!works) {
                                view.gesture(area, w, h, margin, change.previousPosition, change.position - change.previousPosition, 1f)
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                    if (stroke) tool?.up()
                    finger = null
                }
            },
    ) {
        tool?.tick
        val w = tool?.width ?: picture.width
        val h = tool?.height ?: picture.height
        val scale = view.scale(size, w, h, margin)
        val left = view.left(size, w, h, margin)
        val top = view.top(size, w, h, margin)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
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
        // A ring the size of the brush: under the finger, or in the middle while its size is being set.
        if (tool != null && tool.hasBrush && compare == null) {
            val at = finger ?: if (showRing) Offset(size.width / 2f, size.height / 2f) else null
            if (at != null) {
                drawCircle(Color.Black.copy(alpha = 0.45f), radius = tool.size + 1.dp.toPx(), center = at, style = Stroke(1.dp.toPx()))
                drawCircle(Color.White, radius = tool.size, center = at, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, shown: String, onChange: (Float) -> Unit, onDone: (() -> Unit)? = null, mark: Float = -1f) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.padding(horizontal = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = TextMid, modifier = Modifier.weight(1f))
            Text(shown, style = MaterialTheme.typography.labelLarge, color = TextHi)
        }
        MwSlider(value = value, onValueChange = onChange, onValueChangeFinished = onDone, mark = mark, fillFromMark = mark >= 0f)
    }
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

private val CROP_SHAPES: List<Pair<String, Float?>> = listOf(
    CropTool.FREE to null,
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
    ActionRow {
        Chip(CropTool.FREE, selected = tool.shape == CropTool.FREE, onClick = { tool.choose(CropTool.FREE, null) })
        Chip(CropTool.ORIGINAL, selected = tool.shape == CropTool.ORIGINAL, onClick = { tool.choose(CropTool.ORIGINAL, tool.ownAspect) })
        CROP_SHAPES.drop(1).forEach { (name, ratio) ->
            Chip(name, selected = tool.shape == name, onClick = { tool.choose(name, ratio) })
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        CropAction("Turn", Icons.AutoMirrored.Rounded.RotateRight) { tool.turn() }
        CropAction("Mirror", Icons.Rounded.Flip) { tool.flip(upsideDown = false) }
        CropAction("Flip", Icons.Rounded.Flip, quarterTurn = true) { tool.flip(upsideDown = true) }
    }
}

@Composable
private fun CropAction(label: String, icon: ImageVector, quarterTurn: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = TextHi, modifier = Modifier.size(24.dp).rotate(if (quarterTurn) 90f else 0f))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMid, maxLines = 1)
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

@Composable
private fun AdjustPanel(tool: ToneTool) {
    var chosen by remember { mutableStateOf(SETTINGS.first()) }
    ActionRow {
        SETTINGS.forEach { setting ->
            val changed = abs(setting.get(tool.values)) > 0.001f
            Chip(if (changed) setting.label + " •" else setting.label, selected = setting === chosen, onClick = { chosen = setting })
        }
    }
    val value = chosen.get(tool.values)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (chosen.bothWays) {
                SliderRow(
                    label = chosen.label,
                    value = (value + 1f) / 2f,
                    shown = (if (value > 0) "+" else "") + (value * 100).roundToInt(),
                    onChange = {
                        val next = it * 2f - 1f
                        tool.set(chosen.set(tool.values, if (abs(next) < 0.04f) 0f else next))
                    },
                    mark = 0.5f,
                )
            } else {
                SliderRow(
                    label = chosen.label,
                    value = value,
                    shown = (value * 100).roundToInt().toString(),
                    onChange = { tool.set(chosen.set(tool.values, if (it < 0.03f) 0f else it)) },
                )
            }
        }
        PillButton("Reset", onClick = { tool.set(PhotoOps.Adjust()) }, modifier = Modifier.padding(end = 14.dp))
    }
}

@Composable
private fun LooksPanel(tool: ToneTool) {
    var look by remember { mutableStateOf(PhotoOps.Look.NONE) }
    var amount by remember { mutableFloatStateOf(1f) }
    // Small samples of every look on this very photo.
    val samples by produceState<List<ImageBitmap>?>(null, tool) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val small = tool.base.fitWithin(168)
                val pixels = small.pixels()
                PhotoOps.Look.entries.map { bitmapOf(PhotoOps.adjust(pixels, small.width, small.height, it.recipe), small.width, small.height).asImageBitmap() }
            }.getOrNull()
        }
    }
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(PhotoOps.Look.entries, key = { it.name }) { item ->
            val chosen = item == look
            Column(
                Modifier
                    .width(70.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        look = item
                        tool.set(item.recipe.scaled(amount))
                    }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(62.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Surface2)
                        .border(if (chosen) 2.dp else 0.dp, if (chosen) Pink else Color.Transparent, RoundedCornerShape(10.dp)),
                ) {
                    samples?.getOrNull(item.ordinal)?.let {
                        Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(item.label, style = MaterialTheme.typography.labelSmall, color = if (chosen) TextHi else TextMid, maxLines = 1)
            }
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
        Text(NO_MEMORY, style = MaterialTheme.typography.bodySmall, color = Pink, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
        return
    }
    ActionRow {
        Chip("Paint", selected = !tool.erasing, onClick = { tool.erasing = false })
        Chip("Rub out", selected = tool.erasing, onClick = { tool.erasing = true })
    }
    brush()
    SliderRow("Strength", tool.strength, "${(tool.strength * 100).roundToInt()}%", { tool.strength = it })
}
