package com.noexcusehere.mashwave.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** "1:02:03" or "4:05". */
fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "1 h 45 min" for the length of a film. */
fun length(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    return when {
        h > 0 && m > 0 -> "$h h $m min"
        h > 0 -> "$h h"
        else -> "$m min"
    }
}

/** A box to type in. Pasting, selecting and the right-click menu work as anywhere else on the computer. */
@Composable
fun Field(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    secret: Boolean = false,
    lines: Int = 1,
    focus: FocusRequester? = null,
    onEnter: (() -> Unit)? = null,
) {
    val single = lines == 1
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = single,
        minLines = lines,
        maxLines = if (single) 1 else lines + 3,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextHi),
        cursorBrush = SolidColor(Violet),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = if (onEnter != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onEnter?.invoke() }),
        modifier = modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onPreviewKeyEvent {
                if (single && onEnter != null && it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) {
                    onEnter()
                    true
                } else {
                    false
                }
            },
        decorationBox = { inner ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Surface2)
                    .border(1.dp, Outline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = if (single) Alignment.CenterVertically else Alignment.Top,
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = TextLow, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = TextLow, maxLines = if (single) 1 else lines, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                if (single && value.isNotEmpty() && !secret) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Clear",
                        tint = TextLow,
                        modifier = Modifier.size(16.dp).clip(CircleShape).clickable { onValue("") },
                    )
                }
            }
        },
    )
}

/**
 * Fades a button that cannot be used right now. Always the same kind of step whatever [enabled]
 * says: a fade that is only sometimes part of the chain left the button's colour undrawn after
 * it was switched back on.
 */
fun Modifier.dimmedUnless(enabled: Boolean, faded: Float = 0.45f): Modifier = graphicsLayer { alpha = if (enabled) 1f else faded }

/** The main button of a screen. */
@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Row(
        modifier
            .dimmedUnless(enabled)
            .clip(RoundedCornerShape(50))
            .background(BrandGradient)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
    }
}

/** A button that does not shout. */
@Composable
fun Quiet(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, tint: Color = TextHi) {
    Row(
        modifier
            .dimmedUnless(enabled)
            .clip(RoundedCornerShape(50))
            .background(Surface3)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Color.White else TextMid,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Violet else Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** Shows [text] in a little box when the mouse rests on [content]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tip(text: String, content: @Composable () -> Unit) {
    if (text.isEmpty()) {
        content()
        return
    }
    TooltipArea(
        tooltip = {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = TextHi,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Surface3)
                    .border(1.dp, Outline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        },
        delayMillis = 500,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
        content = content,
    )
}

/** A round button with only a picture on it; [tip] says what it does. */
@Composable
fun RoundIcon(
    icon: ImageVector,
    tip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    tint: Color = TextHi,
    backing: Color = Color.Transparent,
    enabled: Boolean = true,
) {
    Tip(tip) {
        Box(
            modifier
                .size(size)
                .dimmedUnless(enabled, 0.35f)
                .clip(CircleShape)
                .background(backing)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = tip, tint = tint, modifier = Modifier.size(size * 0.56f))
        }
    }
}

/** One line of a list down the side: a group of channels, a kind of film. */
@Composable
fun SideRow(title: String, selected: Boolean, onClick: () -> Unit, count: Int? = null, icon: ImageVector? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Violet.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (selected) TextHi else TextMid, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) TextHi else TextMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = TextLow)
        }
    }
}

/** A friendly message in the middle of an otherwise empty space. */
@Composable
fun Note(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = TextLow, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = TextHi, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = TextMid, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 460.dp))
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Pill(action, onAction)
        }
    }
}

@Composable
fun Busy(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Violet, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TextMid)
    }
}

/** A thin line showing how far through something is. */
@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.16f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Pink))
    }
}

@Composable
fun BoxScope.ListBar(state: LazyListState) {
    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp))
}

@Composable
fun BoxScope.GridBar(state: LazyGridState) {
    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp))
}

/** A box in the middle of the window, over everything else, for a question or a form. */
@Composable
fun Sheet(title: String, onClose: () -> Unit, width: Dp = 520.dp, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = width)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Surface1)
                .border(1.dp, Outline, RoundedCornerShape(18.dp))
                // Clicks inside the box must not count as clicks on the dimmed background.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = TextHi, modifier = Modifier.weight(1f))
                RoundIcon(Icons.Rounded.Close, "Close", onClose, size = 32.dp, tint = TextMid)
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}
