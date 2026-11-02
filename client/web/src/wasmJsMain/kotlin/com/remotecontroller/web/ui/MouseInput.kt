package com.remotecontroller.web.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import com.remotecontroller.client.core.letterbox

private const val LEFT = "LEFT"
private const val RIGHT = "RIGHT"
private const val MIDDLE = "MIDDLE"

private fun PointerButtons.name(): String = when {
    isSecondaryPressed -> RIGHT
    isTertiaryPressed -> MIDDLE
    else -> LEFT
}

/**
 * A real mouse over the picture: the PC's pointer follows it the whole time, so an app gets its
 * hover before a click, and each button is itself. The press goes on until every button is up,
 * wherever the mouse went by then. The first press to arrive is the one the PC sees: it holds
 * one button at a time.
 */
internal fun Modifier.desktopMouse(
    imageWidth: Int,
    imageHeight: Int,
    viewModel: PageViewModel,
): Modifier = pointerInput(imageWidth, imageHeight, viewModel) {
    var held: String? = null
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.type == PointerType.Mouse } ?: continue
            val placed = letterbox(size.width.toFloat(), size.height.toFloat(), imageWidth, imageHeight)
            val point = placed.normalize(change.position)
            val onPicture = placed.contains(change.position)
            when (event.type) {
                PointerEventType.Press -> if (held == null && onPicture) {
                    viewModel.captureKeyboard()
                    val button = event.buttons.name()
                    held = button
                    viewModel.pointerDown(point.x, point.y, button)
                    change.consume()
                }
                PointerEventType.Release -> {
                    val button = held
                    if (button != null && !event.buttons.areAnyPressed) {
                        held = null
                        viewModel.pointerUp(point.x, point.y, button)
                        change.consume()
                    }
                }
                PointerEventType.Move -> if (held != null || onPicture) {
                    viewModel.pointerMove(point.x, point.y, held ?: LEFT)
                }
                PointerEventType.Scroll -> if (onPicture) {
                    viewModel.scroll(point.x, point.y, change.scrollDelta.y.toInt())
                    change.consume()
                }
            }
        }
    }
}
