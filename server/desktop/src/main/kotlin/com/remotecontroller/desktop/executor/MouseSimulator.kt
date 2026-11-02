package com.remotecontroller.desktop.executor

import com.remotecontroller.shared.model.RemoteKeys
import java.awt.MouseInfo
import java.awt.Robot
import java.awt.event.InputEvent

class MouseSimulator {

    private val robot = Robot().apply { autoDelay = 0 }
    private val lock = Any()
    private var pressedMask: Int? = null

    fun moveRelative(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        synchronized(lock) {
            val point = MouseInfo.getPointerInfo().location
            robot.mouseMove(point.x + dx, point.y + dy)
        }
    }

    fun click(button: String) {
        synchronized(lock) {
            val mask = buttonMask(button)
            robot.mousePress(mask)
            robot.mouseRelease(mask)
        }
    }

    fun scroll(deltaY: Int) {
        if (deltaY == 0) return
        synchronized(lock) {
            robot.mouseWheel(deltaY.coerceIn(-MAX_WHEEL, MAX_WHEEL))
        }
    }

    fun pointer(x: Int, y: Int, phase: String, button: String) {
        synchronized(lock) {
            robot.mouseMove(x, y)
            when (phase) {
                RemoteKeys.POINTER_DOWN -> {
                    releasePressedLocked()
                    val mask = buttonMask(button)
                    robot.mousePress(mask)
                    pressedMask = mask
                }
                RemoteKeys.POINTER_UP -> releasePressedLocked()
                else -> Unit
            }
        }
    }

    fun scrollAt(x: Int, y: Int, deltaY: Int) {
        if (deltaY == 0) return
        synchronized(lock) {
            robot.mouseMove(x, y)
            robot.mouseWheel(deltaY.coerceIn(-MAX_WHEEL, MAX_WHEEL))
        }
    }

    fun releaseHeld() {
        synchronized(lock) { releasePressedLocked() }
    }

    private fun releasePressedLocked() {
        val mask = pressedMask ?: return
        robot.mouseRelease(mask)
        pressedMask = null
    }

    private fun buttonMask(button: String): Int =
        when (button.uppercase()) {
            "RIGHT" -> InputEvent.BUTTON3_DOWN_MASK
            "MIDDLE" -> InputEvent.BUTTON2_DOWN_MASK
            else -> InputEvent.BUTTON1_DOWN_MASK
        }

    private companion object {
        const val MAX_WHEEL = 20
    }
}
