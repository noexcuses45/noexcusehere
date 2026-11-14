package com.noexcusehere.mashwave.ui.screens

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noexcusehere.mashwave.editor.EditProject
import com.noexcusehere.mashwave.editor.EditText
import com.noexcusehere.mashwave.editor.FrameCache
import com.noexcusehere.mashwave.ui.theme.Cyan
import com.noexcusehere.mashwave.ui.theme.Outfit
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.Surface3
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid
import com.noexcusehere.mashwave.ui.theme.Violet
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Formats finished-video time as m:ss.t */
fun formatEditTime(ms: Long): String {
    val tenths = (ms.coerceAtLeast(0) + 50) / 100
    return "%d:%02d.%d".format(tenths / 600, tenths / 10 % 60, tenths % 10)
}

/**
 * The film strip. The playhead stays in the middle and the strip slides under it: drag to move
 * through the video, pinch to zoom, tap a caption or the music bar to edit it.
 */
@Composable
fun EditorTimeline(
    project: EditProject,
    positionMs: Long,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    onTapText: (EditText) -> Unit,
    onTapMusic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    var pxPerSec by remember { mutableFloatStateOf(with(density) { 64.dp.toPx() }) }
    val minZoom = with(density) { 6.dp.toPx() }
    val maxZoom = with(density) { 260.dp.toPx() }

    val currentProject by rememberUpdatedState(project)
    val currentPosition by rememberUpdatedState(positionMs)
    val scrubStart by rememberUpdatedState(onScrubStart)
    val scrub by rememberUpdatedState(onScrub)
    val scrubEnd by rememberUpdatedState(onScrubEnd)
    val tapText by rememberUpdatedState(onTapText)
    val tapMusic by rememberUpdatedState(onTapMusic)

    val rulerH = with(density) { 18.dp.toPx() }
    val textRowH = with(density) { 21.dp.toPx() }
    val clipsH = with(density) { 58.dp.toPx() }
    val musicH = with(density) { 24.dp.toPx() }
    val gap = with(density) { 5.dp.toPx() }
    val textsTop = rulerH
    val clipsTop = textsTop + 2 * textRowH + gap
    val musicTop = clipsTop + clipsH + gap
    val totalHeight = musicTop + musicH + gap

    val rows = remember(project.texts) { textRows(project.texts) }
    val currentRows by rememberUpdatedState(rows)
    val fling = remember { arrayOfNulls<Job>(1) }
    val decay = remember(density) { splineBasedDecay<Float>(density) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(with(density) { totalHeight.toDp() })
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fling[0]?.cancel()
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var moved = false
                    var zoomed = false
                    var started = false
                    var travelled = 0f
                    var lastSpread = 0f
                    var position = currentPosition.toFloat()
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            val spread = (pressed[0].position - pressed[1].position).getDistance()
                            if (lastSpread > 0f && spread > 0f) {
                                pxPerSec = (pxPerSec * spread / lastSpread).coerceIn(minZoom, maxZoom)
                            }
                            lastSpread = spread
                            zoomed = true
                            pressed.forEach { it.consume() }
                        } else {
                            lastSpread = 0f
                            val change = pressed[0]
                            val dx = change.positionChange().x
                            travelled += abs(dx)
                            if (!moved && travelled > viewConfiguration.touchSlop) moved = true
                            if (moved && !zoomed) {
                                if (!started) {
                                    started = true
                                    scrubStart()
                                    position = currentPosition.toFloat()
                                }
                                tracker.addPosition(change.uptimeMillis, change.position)
                                position = (position - dx / pxPerSec * 1000f).coerceIn(0f, currentProject.durationMs.toFloat())
                                scrub(position.toLong())
                                change.consume()
                            }
                        }
                    }
                    when {
                        zoomed -> if (started) scrubEnd()
                        !moved -> {
                            // A tap: on a caption, the music bar, or a spot in the film strip.
                            val tapMs = currentPosition + ((down.position.x - size.width / 2f) / pxPerSec * 1000f).toLong()
                            val y = down.position.y
                            if (y >= textsTop && y < clipsTop - gap / 2) {
                                val row = ((y - textsTop) / textRowH).toInt().coerceIn(0, 1)
                                val slop = 12.dp.toPx() / pxPerSec * 1000f
                                val hit = currentProject.texts.filter { currentRows[it.id] == row }
                                    .firstOrNull { tapMs >= it.startMs - slop && tapMs <= it.endMs + slop }
                                    ?: currentProject.texts.firstOrNull { tapMs >= it.startMs && tapMs <= it.endMs }
                                if (hit != null) tapText(hit)
                            } else if (y >= musicTop) {
                                tapMusic()
                            } else if (y >= clipsTop - gap && currentProject.clips.isNotEmpty()) {
                                scrubStart()
                                scrub(tapMs.coerceIn(0, currentProject.durationMs))
                                scrubEnd()
                            }
                        }
                        else -> {
                            // Let go while moving: glide to a stop.
                            val velocity = -tracker.calculateVelocity().x
                            if (abs(velocity) < 400f) {
                                scrubEnd()
                            } else {
                                fling[0] = scope.launch {
                                    var last = 0f
                                    try {
                                        AnimationState(initialValue = 0f, initialVelocity = velocity).animateDecay(decay) {
                                            val step = value - last
                                            last = value
                                            val limit = currentProject.durationMs.toFloat()
                                            position = (position + step / pxPerSec * 1000f).coerceIn(0f, limit)
                                            scrub(position.toLong())
                                            if (position <= 0f || position >= limit) cancelAnimation()
                                        }
                                    } finally {
                                        scrubEnd()
                                    }
                                }
                            }
                        }
                    }
                }
            },
    ) {
        // Redraw when a new thumbnail arrives.
        FrameCache.generation.intValue
        val pxPerMs = pxPerSec / 1000f
        val center = size.width / 2f
        fun x(timeMs: Long) = center + (timeMs - positionMs) * pxPerMs

        drawRuler(measurer, pxPerSec, positionMs, center, rulerH)

        // Captions and emoji.
        for (text in project.texts) {
            val row = rows[text.id] ?: 0
            val left = x(text.startMs)
            val right = x(text.endMs)
            if (right < 0 || left > size.width) continue
            val top = textsTop + row * textRowH + 1.dp.toPx()
            val h = textRowH - 3.dp.toPx()
            val w = (right - left).coerceAtLeast(6.dp.toPx())
            drawRoundRect(Violet.copy(alpha = 0.55f), Offset(left, top), Size(w, h), CornerRadius(5.dp.toPx()))
            drawRoundRect(Violet, Offset(left, top), Size(w, h), CornerRadius(5.dp.toPx()), style = Stroke(1.dp.toPx()))
            label(measurer, text.text.replace('\n', ' '), left + 5.dp.toPx(), top + h / 2, w - 8.dp.toPx(), Color.White, 11)
        }
        if (project.texts.isEmpty()) {
            label(measurer, "Captions and emoji appear here", 12.dp.toPx(), textsTop + textRowH, size.width, TextLow, 11)
        }

        // The clips, as a strip of little frames.
        val starts = project.clipStarts()
        val current = project.clipAt(positionMs)?.first ?: -1
        val tile = clipsH
        project.clips.forEachIndexed { index, clip ->
            val left = x(starts[index])
            val right = x(starts[index] + clip.durationMs)
            if (right < 0 || left > size.width) return@forEachIndexed
            val inset = 1.dp.toPx()
            val rect = Rect(left + inset, clipsTop, (right - inset).coerceAtLeast(left + inset + 2f), clipsTop + clipsH)
            val shape = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(7.dp.toPx()))) }
            clipPath(shape) {
                drawRect(Surface3, rect.topLeft, rect.size)
                val firstTile = floor(((0f - rect.left) / tile).coerceAtLeast(0f)).toInt()
                val lastTile = ceil(((size.width - rect.left) / tile).coerceAtMost(rect.width / tile)).toInt()
                for (k in firstTile..lastTile) {
                    val tileLeft = rect.left + k * tile
                    if (tileLeft > rect.right) break
                    val intoMs = (k * tile / pxPerMs).toLong()
                    val sourceMs = if (clip.isImage) 0 else clip.startMs + (intoMs * clip.speed).toLong()
                    val image = FrameCache.get(context, scope, clip, sourceMs) ?: continue
                    // Centre-crop the frame into a square tile.
                    val side = minOf(image.width, image.height)
                    drawImage(
                        image = image,
                        srcOffset = IntOffset((image.width - side) / 2, (image.height - side) / 2),
                        srcSize = IntSize(side, side),
                        dstOffset = IntOffset(tileLeft.roundToInt(), clipsTop.roundToInt()),
                        dstSize = IntSize(ceil(tile).toInt(), clipsH.roundToInt()),
                    )
                }
                // Small facts about the clip in its corner.
                val facts = buildString {
                    append("%.1fs".format(clip.durationMs / 1000f))
                    if (!clip.isImage && abs(clip.speed - 1f) > 0.001f) append("  ${trimNumber(clip.speed)}×")
                    if (!clip.isImage && clip.volume <= 0.001f) append("  muted")
                }
                val factsLeft = maxOf(rect.left, 0f) + 4.dp.toPx()
                val measured = measurer.measure(
                    facts,
                    TextStyle(fontFamily = Outfit, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                    maxLines = 1,
                )
                if (measured.size.width + 10.dp.toPx() < rect.right - factsLeft) {
                    drawRoundRect(
                        Color.Black.copy(alpha = 0.6f),
                        Offset(factsLeft, rect.bottom - measured.size.height - 6.dp.toPx()),
                        Size(measured.size.width + 8.dp.toPx(), measured.size.height + 2.dp.toPx()),
                        CornerRadius(4.dp.toPx()),
                    )
                    drawText(measured, topLeft = Offset(factsLeft + 4.dp.toPx(), rect.bottom - measured.size.height - 5.dp.toPx()))
                }
            }
            if (index == current) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(Violet, Pink), startX = rect.left, endX = rect.right),
                    topLeft = rect.topLeft,
                    size = rect.size,
                    cornerRadius = CornerRadius(7.dp.toPx()),
                    style = Stroke(2.5.dp.toPx()),
                )
            }
        }

        // Background music runs under the whole video.
        val music = project.music
        val videoLeft = x(0)
        val videoRight = x(project.durationMs)
        if (music != null && project.clips.isNotEmpty()) {
            val left = videoLeft.coerceAtLeast(-20f)
            val right = videoRight.coerceAtMost(size.width + 20f)
            if (right > left) {
                drawRoundRect(Cyan.copy(alpha = 0.28f), Offset(left, musicTop), Size(right - left, musicH), CornerRadius(6.dp.toPx()))
                drawRoundRect(Cyan.copy(alpha = 0.8f), Offset(left, musicTop), Size(right - left, musicH), CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx()))
                label(measurer, "♪  " + music.entry.title, maxOf(left, 0f) + 8.dp.toPx(), musicTop + musicH / 2, right - maxOf(left, 0f) - 12.dp.toPx(), Color.White, 11)
            }
        } else {
            drawRoundRect(Surface2, Offset(12.dp.toPx(), musicTop), Size(size.width - 24.dp.toPx(), musicH), CornerRadius(6.dp.toPx()))
            label(measurer, "♪  Tap to add music from your library", 22.dp.toPx(), musicTop + musicH / 2, size.width, TextMid, 11)
        }

        // The playhead.
        drawRoundRect(
            Color.White,
            Offset(center - 1.dp.toPx(), rulerH - 4.dp.toPx()),
            Size(2.dp.toPx(), totalHeight - rulerH + 2.dp.toPx()),
            CornerRadius(1.dp.toPx()),
        )
        drawCircle(Color.White, 4.dp.toPx(), Offset(center, rulerH - 4.dp.toPx()))
    }
}

/** Puts overlapping captions on separate rows (two rows; further overlaps share the second). */
private fun textRows(texts: List<EditText>): Map<String, Int> {
    val out = HashMap<String, Int>()
    var firstRowEnd = Long.MIN_VALUE
    for (text in texts.sortedBy { it.startMs }) {
        if (text.startMs >= firstRowEnd) {
            out[text.id] = 0
            firstRowEnd = text.endMs
        } else {
            out[text.id] = 1
        }
    }
    return out
}

private fun trimNumber(value: Float): String =
    if (abs(value - value.roundToInt()) < 0.005f) value.roundToInt().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.')

private fun DrawScope.label(measurer: TextMeasurer, text: String, left: Float, centerY: Float, maxWidth: Float, color: Color, sizeSp: Int) {
    if (maxWidth < 12.dp.toPx() || text.isEmpty()) return
    val measured = measurer.measure(
        text,
        TextStyle(fontFamily = Outfit, fontSize = sizeSp.sp, fontWeight = FontWeight.Medium, color = color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = maxWidth.toInt().coerceAtLeast(1)),
    )
    drawText(measured, topLeft = Offset(left, centerY - measured.size.height / 2f))
}

private fun DrawScope.drawRuler(measurer: TextMeasurer, pxPerSec: Float, positionMs: Long, center: Float, height: Float) {
    // Pick a step so labels never crowd each other.
    val steps = intArrayOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600)
    val wanted = 64.dp.toPx()
    val step = steps.firstOrNull { it * pxPerSec >= wanted } ?: steps.last()
    val leftSec = (positionMs / 1000f - center / pxPerSec)
    val rightSec = (positionMs / 1000f + (size.width - center) / pxPerSec)
    var second = (floor(leftSec / step).toInt() * step).coerceAtLeast(0)
    clipRect(0f, 0f, size.width, height) {
        while (second <= rightSec) {
            val x = center + (second * 1000L - positionMs) * pxPerSec / 1000f
            val measured = measurer.measure(
                "%d:%02d".format(second / 60, second % 60),
                TextStyle(fontFamily = Outfit, fontSize = 10.sp, color = TextLow),
                maxLines = 1,
            )
            drawText(measured, topLeft = Offset(x + 3.dp.toPx(), 0f))
            drawRect(TextLow.copy(alpha = 0.6f), Offset(x, height - 6.dp.toPx()), Size(1.dp.toPx(), 5.dp.toPx()))
            second += step
        }
    }
}
