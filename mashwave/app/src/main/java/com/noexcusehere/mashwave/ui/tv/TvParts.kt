package com.noexcusehere.mashwave.ui.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noexcusehere.mashwave.ui.theme.BrandGradientHorizontal
import com.noexcusehere.mashwave.ui.theme.Outline
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.Surface3
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid
import com.noexcusehere.mashwave.ui.theme.Violet

/**
 * Makes something workable with a TV remote: it lights up when the remote's arrows land on it,
 * and OK presses it. [onMenu] is for the remote's menu button (or holding OK down), and
 * [onLanded] is told when the remote arrives.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.remote(
    onClick: () -> Unit,
    shape: Shape = RoundedCornerShape(10.dp),
    chosen: Boolean = false,
    requester: FocusRequester? = null,
    onMenu: (() -> Unit)? = null,
    onLanded: (() -> Unit)? = null,
): Modifier = composed {
    var lit by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    // Whether the OK press now under way began on this very item.
    val began = remember { booleanArrayOf(false) }
    (if (requester != null) Modifier.focusRequester(requester) else Modifier)
        .onFocusChanged {
            val now = it.isFocused
            if (now && !lit) onLanded?.invoke()
            if (!now) began[0] = false
            lit = now
        }
        .onPreviewKeyEvent { event ->
            when {
                onMenu != null && event.key == Key.Menu -> {
                    if (event.type == KeyEventType.KeyUp) onMenu()
                    true
                }
                // OK only counts when it was pressed down here. A button still held while the
                // remote arrives (a list opened under it, the row it was on went away) would
                // otherwise press this item the moment it is let go.
                event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                    if (event.type == KeyEventType.KeyDown) {
                        if (event.nativeKeyEvent.repeatCount == 0) began[0] = true
                        !began[0]
                    } else {
                        val mine = began[0]
                        began[0] = false
                        !mine
                    }
                }
                else -> false
            }
        }
        .clip(shape)
        .background(
            when {
                lit -> Violet.copy(alpha = 0.6f)
                chosen -> Color.White.copy(alpha = 0.10f)
                else -> Color.Transparent
            },
        )
        .border(2.dp, if (lit) Color.White else Color.Transparent, shape)
        .combinedClickable(interactionSource = source, indication = null, onClick = onClick, onLongClick = onMenu)
}

/** Puts the remote on [requester]'s item once it is on screen. Quietly does nothing if it is not there. */
@Composable
fun LandOn(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) {
        // The item may need a moment to be laid out before it can take the remote.
        repeat(6) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(60)
        }
    }
}

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    requester: FocusRequester? = null,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .then(if (primary) Modifier.background(BrandGradientHorizontal) else Modifier.background(Surface3))
            .remote(onClick, RoundedCornerShape(50), requester = requester)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
    }
}

/** One line of a list down the side: a group of channels, a kind of film. */
@Composable
fun TvSideRow(
    title: String,
    chosen: Boolean,
    onClick: () -> Unit,
    onLanded: (() -> Unit)? = null,
    count: Int? = null,
    icon: ImageVector? = null,
    requester: FocusRequester? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .remote(onClick, chosen = chosen, requester = requester, onLanded = onLanded)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = TextHi, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = TextMid)
        }
    }
}

@Composable
fun TvNote(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, contentDescription = null, tint = TextLow, modifier = Modifier.size(46.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = TextHi, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = TextMid, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
        if (action != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            TvButton(action, onAction, primary = true)
        }
    }
}

@Composable
fun TvBusy(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Violet, strokeWidth = 3.dp, modifier = Modifier.size(38.dp))
        Spacer(Modifier.height(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = TextMid)
    }
}

/**
 * A box to type in with the TV's on-screen keyboard. Landing on it with the remote does not
 * throw the keyboard up; OK does. [onDone] is the keyboard's own "done" button.
 */
@Composable
fun TvTextBox(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    secret: Boolean = false,
    web: Boolean = false,
    requester: FocusRequester? = null,
    onDone: (() -> Unit)? = null,
) {
    var typing by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .border(1.dp, Outline, RoundedCornerShape(12.dp))
            .remote({ typing = true }, RoundedCornerShape(12.dp), requester = requester)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = TextMid, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.weight(1f)) {
            val shown = if (secret && !typing) "•".repeat(value.length.coerceAtMost(24)) else value
            if (typing) {
                BasicTextField(
                    value = value,
                    onValueChange = onValue,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = TextHi),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = if (web) KeyboardType.Uri else KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        typing = false
                        keyboard?.hide()
                        onDone?.invoke()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(field)
                        .onFocusChanged {
                            // Moving off the box with the remote ends the typing.
                            if (held && !it.isFocused) typing = false
                            held = it.isFocused
                        }
                        .onPreviewKeyEvent { event ->
                            // Up and down always leave the box, and OK brings the keyboard back.
                            // (Left to itself the box only does this for some makes of remote,
                            // and with the others the arrows would be stuck inside it.)
                            when (event.key) {
                                Key.DirectionUp, Key.DirectionDown -> {
                                    if (event.type == KeyEventType.KeyDown) {
                                        focus.moveFocus(if (event.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                                    }
                                    true
                                }
                                Key.DirectionCenter -> {
                                    if (event.type == KeyEventType.KeyUp) keyboard?.show()
                                    true
                                }
                                else -> false
                            }
                        },
                )
                LaunchedEffect(Unit) {
                    held = false
                    runCatching { field.requestFocus() }
                    keyboard?.show()
                }
            } else {
                Text(
                    shown.ifEmpty { hint },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (value.isEmpty()) TextLow else TextHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
