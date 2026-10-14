package com.remotecontroller.client.presentation.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.remotecontroller.shared.model.RemoteKeys
import kotlin.math.roundToInt

private const val MOVE_SENSITIVITY = 2f
private const val SCROLL_SENSITIVITY = 0.15f
private val MouseButtonHeight = 52.dp
private val MouseButtonShape = RoundedCornerShape(12.dp)

@Composable
fun MousePadSection(
    modifier: Modifier = Modifier,
    onMove: (dx: Int, dy: Int) -> Unit,
    onClick: (button: String) -> Unit,
    onPress: (button: String) -> Unit,
    onScroll: (deltaY: Int) -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Mouse Pad",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var dragged = false

                            drag(down.id) { change ->
                                val delta = change.positionChange()
                                if (delta != Offset.Zero) dragged = true
                                val dx = (delta.x * MOVE_SENSITIVITY).roundToInt()
                                val dy = (delta.y * MOVE_SENSITIVITY).roundToInt()
                                onMove(dx, dy)
                                change.consume()
                            }

                            if (!dragged) {
                                onClick("LEFT")
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Drag to move · Tap to click",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val deltaY = (-dragAmount.y * SCROLL_SENSITIVITY).roundToInt()
                            if (deltaY != 0) onScroll(deltaY)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Scroll — swipe up/down",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            MouseButtonRow(
                buttons = RemoteKeys.MOUSE_BUTTONS,
                onButtonClick = onClick,
            )

            MouseButtonRow(
                buttons = RemoteKeys.MOUSE_BUTTONS_ADD,
                onButtonClick = onPress,
            )
        }
    }
}

@Composable
private fun MouseButtonRow(
    buttons: List<String>,
    onButtonClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        buttons.forEach { button ->
            FilledTonalButton(
                onClick = { onButtonClick(button) },
                modifier = Modifier
                    .weight(1f)
                    .height(MouseButtonHeight),
                shape = MouseButtonShape,
            ) {
                Text(
                    text = button.lowercase().replaceFirstChar { it.titlecase() },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
