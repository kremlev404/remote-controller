package com.remotecontroller.client.feature.remote.impl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.remotecontroller.client.core.TypingRow
import com.remotecontroller.shared.model.RemoteKeys
import kotlin.math.roundToInt

private const val SCROLL_SENSITIVITY = 0.15f

@Composable
fun MousePadSection(
    modifier: Modifier = Modifier,
    sensitivity: Float,
    onMove: (dx: Int, dy: Int) -> Unit,
    onClick: (button: String) -> Unit,
    onPress: (button: String) -> Unit,
    onScroll: (deltaY: Int) -> Unit,
    onType: (text: String, backspaces: Int) -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TouchPad(
            sensitivity = sensitivity,
            onMove = onMove,
            onClick = { onClick("LEFT") },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        ScrollStrip(
            onScroll = onScroll,
            modifier = Modifier.fillMaxWidth(),
        )

        TypingRow(
            onType = onType,
            onEnter = { onPress("ENTER") },
        )

        ActionRow(buttons = RemoteKeys.MOUSE_BUTTONS, onPress = onClick)
        ActionRow(buttons = RemoteKeys.MOUSE_BUTTONS_ADD, onPress = onPress)
    }
}

@Composable
private fun TouchPad(
    sensitivity: Float,
    onMove: (dx: Int, dy: Int) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dotColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(sensitivity) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragged = false

                    drag(down.id) { change ->
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) dragged = true
                        val dx = (delta.x * sensitivity).roundToInt()
                        val dy = (delta.y * sensitivity).roundToInt()
                        onMove(dx, dy)
                        change.consume()
                    }

                    if (!dragged) onClick()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 22.dp.toPx()
            val radius = 1.7.dp.toPx()
            var y = step
            while (y < size.height - step * 0.6f) {
                var x = step
                while (x < size.width - step * 0.6f) {
                    drawCircle(color = dotColor, radius = radius, center = Offset(x, y))
                    x += step
                }
                y += step
            }
        }
        Text(
            text = "Mouse pad",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ScrollStrip(
    onScroll: (deltaY: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val deltaY = (-dragAmount.y * SCROLL_SENSITIVITY).roundToInt()
                    if (deltaY != 0) onScroll(deltaY)
                }
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.UnfoldMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = "Scroll",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ActionRow(
    buttons: List<String>,
    onPress: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        buttons.forEach { button ->
            RemoteKey(
                label = button.remoteLabel(),
                onClick = { onPress(button) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun RemoteKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = contentColor,
        )
    }
}

internal fun String.remoteLabel(): String = when (this) {
    "PLAY_PAUSE" -> "Play"
    "PAGE_UP" -> "PgUp"
    "PAGE_DOWN" -> "PgDn"
    "BACKSPACE" -> "Back"
    "DELETE" -> "Del"
    "LEFT" -> "Left"
    "RIGHT" -> "Right"
    else -> lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
}
