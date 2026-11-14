package com.noexcusehere.vpn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.noexcusehere.vpn.R

/**
 * The big round connect/disconnect button. The ring around it carries the status: grey when
 * off, a spinning amber arc while connecting, solid teal with a glow once connected.
 */
@Composable
fun PowerButton(
    status: ConnStatus,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val connected = status == ConnStatus.CONNECTED
    val busy = status == ConnStatus.CONNECTING ||
        status == ConnStatus.WAITING ||
        status == ConnStatus.DISCONNECTING

    val ringColor by animateColorAsState(
        targetValue = when (status) {
            ConnStatus.CONNECTED -> scheme.primary
            ConnStatus.CONNECTING, ConnStatus.WAITING -> LocalStatusColors.current.pending
            ConnStatus.DISCONNECTING, ConnStatus.DISCONNECTED -> scheme.outline
        },
        label = "ring",
    )
    val buttonColor by animateColorAsState(
        targetValue = if (connected) scheme.primary else scheme.surfaceContainerHigh,
        label = "button",
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            connected -> scheme.onPrimary
            enabled -> scheme.onSurface
            else -> scheme.onSurface.copy(alpha = 0.38f)
        },
        label = "icon",
    )
    val spin = if (busy) {
        val transition = rememberInfiniteTransition(label = "spin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Restart),
            label = "angle",
        )
        angle
    } else {
        0f
    }

    val label = stringResource(
        if (status == ConnStatus.DISCONNECTED) R.string.action_connect else R.string.action_disconnect,
    )

    Box(modifier = modifier.size(208.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = 12.dp.toPx()
            val arcTopLeft = Offset(inset, inset)
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)

            if (connected) {
                drawCircle(
                    brush = Brush.radialGradient(
                        0.60f to ringColor.copy(alpha = 0.30f),
                        1.00f to Color.Transparent,
                    ),
                )
            }
            // The full ring is faint while busy so the moving arc stands out.
            drawArc(
                color = ringColor.copy(alpha = if (busy) 0.22f else if (connected) 1f else 0.55f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(stroke),
            )
            if (busy) {
                drawArc(
                    color = ringColor,
                    startAngle = spin - 90f,
                    sweepAngle = 96f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = buttonColor,
            modifier = Modifier
                .size(148.dp)
                .semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_power),
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(60.dp),
                )
            }
        }
    }
}
