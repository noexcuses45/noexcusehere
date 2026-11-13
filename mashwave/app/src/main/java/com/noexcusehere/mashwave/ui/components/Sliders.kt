package com.noexcusehere.mashwave.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noexcusehere.mashwave.data.formatDuration
import com.noexcusehere.mashwave.ui.theme.BrandGradientHorizontal
import com.noexcusehere.mashwave.ui.theme.TextMid

/**
 * A slim slider drawn by hand so it looks the same everywhere (seek bars, crossfader, EQ).
 * [value] and the values it reports are fractions from 0 to 1.
 */
@Composable
fun MwSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    activeBrush: Brush = BrandGradientHorizontal,
    inactiveColor: Color = Color.White.copy(alpha = 0.16f),
    thumbColor: Color = Color.White,
    trackHeight: Dp = 4.dp,
    thumbRadius: Dp = 7.dp,
    /** Fraction where a small tick marks the neutral position (e.g. normal tempo), or -1 for none. */
    mark: Float = -1f,
    /** Fill outwards from the mark instead of from the left edge. */
    fillFromMark: Boolean = false,
) {
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val current by rememberUpdatedState(value)
    val pad = thumbRadius + 2.dp

    Canvas(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .progressSemantics(value.coerceIn(0f, 1f))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val padPx = pad.toPx()
                    change(((offset.x - padPx) / (size.width - 2 * padPx)).coerceIn(0f, 1f))
                    finished?.invoke()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var dragValue = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val padPx = pad.toPx()
                        // Grab the thumb where it is unless the finger starts far away from it.
                        val touched = ((offset.x - padPx) / (size.width - 2 * padPx)).coerceIn(0f, 1f)
                        dragValue = if (kotlin.math.abs(touched - current) < 0.12f) current else touched
                        change(dragValue)
                    },
                    onDragEnd = { finished?.invoke() },
                    onDragCancel = { finished?.invoke() },
                    onHorizontalDrag = { input, delta ->
                        input.consume()
                        val padPx = pad.toPx()
                        dragValue = (dragValue + delta / (size.width - 2 * padPx)).coerceIn(0f, 1f)
                        change(dragValue)
                    },
                )
            },
    ) {
        val padPx = pad.toPx()
        val trackPx = trackHeight.toPx()
        val width = size.width - 2 * padPx
        val y = size.height / 2
        val v = value.coerceIn(0f, 1f)
        val radius = CornerRadius(trackPx / 2, trackPx / 2)
        drawRoundRect(inactiveColor, Offset(padPx, y - trackPx / 2), Size(width, trackPx), radius)
        if (enabled) {
            val from = if (fillFromMark && mark >= 0f) minOf(mark, v) else 0f
            val to = if (fillFromMark && mark >= 0f) maxOf(mark, v) else v
            if (to > from) {
                drawRoundRect(
                    brush = activeBrush,
                    topLeft = Offset(padPx + width * from, y - trackPx / 2),
                    size = Size(width * (to - from), trackPx),
                    cornerRadius = radius,
                )
            }
        }
        if (mark >= 0f) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.55f),
                topLeft = Offset(padPx + width * mark - 1.dp.toPx(), y - trackPx * 1.6f),
                size = Size(2.dp.toPx(), trackPx * 3.2f),
                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
            )
        }
        if (enabled) {
            drawCircle(Color.Black.copy(alpha = 0.25f), thumbRadius.toPx() + 1.5.dp.toPx(), Offset(padPx + width * v, y))
            drawCircle(thumbColor, thumbRadius.toPx(), Offset(padPx + width * v, y))
        }
    }
}

/** Seek bar with elapsed / total time underneath. Only seeks when the finger lifts. */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    activeBrush: Brush = BrandGradientHorizontal,
    showTimes: Boolean = true,
    timeColor: Color = TextMid,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shown = if (dragging) dragValue else fraction
    Column(modifier) {
        MwSlider(
            value = shown,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                if (dragging) onSeek((dragValue * durationMs).toLong())
                dragging = false
            },
            enabled = durationMs > 0,
            activeBrush = activeBrush,
        )
        if (showTimes) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    formatDuration((shown * durationMs).toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    color = timeColor,
                )
                Spacer(Modifier.weight(1f))
                Text(formatDuration(durationMs), style = MaterialTheme.typography.labelMedium, color = timeColor)
            }
        }
    }
}

/** Upright slider used for the equalizer bands. */
@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    activeBrush: Brush = BrandGradientHorizontal,
) {
    val change by rememberUpdatedState(onValueChange)
    val current by rememberUpdatedState(value)
    Canvas(
        modifier
            .progressSemantics(value.coerceIn(0f, 1f))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val padPx = 10.dp.toPx()
                    change((1f - (offset.y - padPx) / (size.height - 2 * padPx)).coerceIn(0f, 1f))
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var dragValue = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragValue = current },
                    onVerticalDrag = { input, delta ->
                        input.consume()
                        val padPx = 10.dp.toPx()
                        dragValue = (dragValue - delta / (size.height - 2 * padPx)).coerceIn(0f, 1f)
                        change(dragValue)
                    },
                )
            },
    ) {
        val padPx = 10.dp.toPx()
        val trackPx = 5.dp.toPx()
        val height = size.height - 2 * padPx
        val x = size.width / 2
        val v = value.coerceIn(0f, 1f)
        val radius = CornerRadius(trackPx / 2, trackPx / 2)
        drawRoundRect(Color.White.copy(alpha = 0.14f), Offset(x - trackPx / 2, padPx), Size(trackPx, height), radius)
        // The middle is "no change"; fill from there towards the thumb.
        val mid = padPx + height / 2
        val thumbY = padPx + height * (1f - v)
        if (enabled) {
            drawRoundRect(
                brush = activeBrush,
                topLeft = Offset(x - trackPx / 2, minOf(mid, thumbY)),
                size = Size(trackPx, kotlin.math.abs(mid - thumbY)),
                cornerRadius = radius,
            )
        }
        drawRoundRect(
            Color.White.copy(alpha = 0.4f),
            Offset(x - 7.dp.toPx(), mid - 1.dp.toPx()),
            Size(14.dp.toPx(), 2.dp.toPx()),
        )
        drawCircle(if (enabled) Color.White else Color.White.copy(alpha = 0.35f), 8.dp.toPx(), Offset(x, thumbY))
    }
}
