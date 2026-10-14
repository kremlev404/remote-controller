package com.remotecontroller.desktop.executor

import java.awt.MouseInfo
import java.awt.Robot
import java.awt.event.InputEvent

class MouseSimulator {

    private val robot = Robot().apply { setAutoDelay(0) }

    fun moveRelative(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        val point = MouseInfo.getPointerInfo().location
        robot.mouseMove(point.x + dx, point.y + dy)
    }

    fun click(button: String) {
        val mask = buttonMask(button)
        robot.mousePress(mask)
        robot.mouseRelease(mask)
    }

    fun scroll(deltaY: Int) {
        if (deltaY == 0) return
        robot.mouseWheel(deltaY)
    }

    private fun buttonMask(button: String): Int =
        when (button.uppercase()) {
            "RIGHT" -> InputEvent.BUTTON3_DOWN_MASK
            "MIDDLE" -> InputEvent.BUTTON2_DOWN_MASK
            else -> InputEvent.BUTTON1_DOWN_MASK
        }
}
