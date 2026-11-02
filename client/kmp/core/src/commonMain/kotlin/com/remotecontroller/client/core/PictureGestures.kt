package com.remotecontroller.client.core

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope

private const val SCROLL_STEP_PX = 32f

/** What a finger does on the picture. Positions are fractions of the PC's screen. */
class PictureTouch(
    val onDown: (x: Float, y: Float, button: String) -> Unit,
    val onMove: (x: Float, y: Float, button: String) -> Unit,
    val onUp: (x: Float, y: Float, button: String) -> Unit,
    val onScroll: (x: Float, y: Float, deltaY: Int) -> Unit,
    /** Where the finger is, for the page to mark, or null once it left. */
    val onMark: (Offset?) -> Unit = {},
)

/** One finger presses and drags on the PC's screen, two scroll. A press outside the picture does nothing. */
suspend fun PointerInputScope.touchPicture(
    imageWidth: Int,
    imageHeight: Int,
    button: () -> String,
    touch: PictureTouch,
    accepts: (PointerInputChange) -> Boolean = { true },
) {
    fun placed(): PlacedImage = letterbox(size.width.toFloat(), size.height.toFloat(), imageWidth, imageHeight)

    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        if (!accepts(first) || !placed().contains(first.position)) {
            awaitAllLifted()
            return@awaitEachGesture
        }
        val buttonName = button()
        var last = placed().normalize(first.position)
        touch.onDown(last.x, last.y, buttonName)
        touch.onMark(last)
        first.consume()
        var scrolling = false
        var lastY = first.position.y
        var accumulated = 0f
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                if (!scrolling) touch.onUp(last.x, last.y, buttonName)
                touch.onMark(null)
                break
            }
            if (pressed.size >= 2) {
                val y = pressed.map { it.position.y }.average().toFloat()
                if (!scrolling) {
                    touch.onUp(last.x, last.y, buttonName)
                    touch.onMark(null)
                    scrolling = true
                    lastY = y
                    accumulated = 0f
                } else {
                    accumulated += lastY - y
                    lastY = y
                    val steps = (accumulated / SCROLL_STEP_PX).toInt()
                    if (steps != 0) {
                        touch.onScroll(last.x, last.y, steps)
                        accumulated -= steps * SCROLL_STEP_PX
                    }
                }
            } else if (!scrolling) {
                last = placed().normalize(pressed[0].position)
                touch.onMove(last.x, last.y, buttonName)
                touch.onMark(last)
            }
            event.changes.forEach { it.consume() }
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitAllLifted() {
    while (awaitPointerEvent().changes.any { it.pressed }) Unit
}
