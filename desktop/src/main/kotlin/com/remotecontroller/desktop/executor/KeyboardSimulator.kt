package com.remotecontroller.desktop.executor

import java.awt.Robot
import java.awt.event.KeyEvent

class KeyboardSimulator(
    private val mediaKeys: MediaKeys,
) {

    private val robot = Robot().apply { setAutoDelay(30) }

    fun pressKey(keyName: String) {
        if (keyName.uppercase() in MEDIA_KEYS && mediaKeys.send(keyName.uppercase())) return
        val keyCode = resolveKeyCode(keyName) ?: return
        robot.keyPress(keyCode)
        robot.keyRelease(keyCode)
    }

    fun pressCombo(keys: List<String>) {
        val keyCodes = keys.mapNotNull(::resolveKeyCode)
        if (keyCodes.isEmpty()) return

        keyCodes.forEach(robot::keyPress)
        keyCodes.reversed().forEach(robot::keyRelease)
    }

    private fun resolveKeyCode(keyName: String): Int? =
        when (keyName.uppercase()) {
            "ENTER" -> KeyEvent.VK_ENTER
            "ESC", "ESCAPE" -> KeyEvent.VK_ESCAPE
            "TAB" -> KeyEvent.VK_TAB
            "SPACE" -> KeyEvent.VK_SPACE
            "BACKSPACE" -> KeyEvent.VK_BACK_SPACE
            "DELETE" -> KeyEvent.VK_DELETE
            "HOME" -> KeyEvent.VK_HOME
            "END" -> KeyEvent.VK_END
            "PAGE_UP" -> KeyEvent.VK_PAGE_UP
            "PAGE_DOWN" -> KeyEvent.VK_PAGE_DOWN
            "UP" -> KeyEvent.VK_UP
            "DOWN" -> KeyEvent.VK_DOWN
            "LEFT" -> KeyEvent.VK_LEFT
            "RIGHT" -> KeyEvent.VK_RIGHT
            "CTRL", "CONTROL" -> KeyEvent.VK_CONTROL
            "ALT" -> KeyEvent.VK_ALT
            "SHIFT" -> KeyEvent.VK_SHIFT
            "WIN", "WINDOWS" -> KeyEvent.VK_WINDOWS
            in MEDIA_KEYS -> mediaKeys.keyCode(keyName.uppercase())
            else -> resolveExtendedKey(keyName)
        }

    private fun resolveExtendedKey(keyName: String): Int? {
        if (keyName.length == 1) {
            val keyCode = KeyEvent.getExtendedKeyCodeForChar(keyName.uppercase()[0].code)
            return keyCode.takeIf { it != KeyEvent.VK_UNDEFINED }
        }
        if (!keyName.startsWith("F") || keyName.length > 3) return null
        return when (keyName.uppercase()) {
            "F1" -> KeyEvent.VK_F1
            "F2" -> KeyEvent.VK_F2
            "F3" -> KeyEvent.VK_F3
            "F4" -> KeyEvent.VK_F4
            "F5" -> KeyEvent.VK_F5
            "F6" -> KeyEvent.VK_F6
            "F7" -> KeyEvent.VK_F7
            "F8" -> KeyEvent.VK_F8
            "F9" -> KeyEvent.VK_F9
            "F10" -> KeyEvent.VK_F10
            "F11" -> KeyEvent.VK_F11
            "F12" -> KeyEvent.VK_F12
            else -> null
        }
    }

    private companion object {
        val MEDIA_KEYS = setOf("PLAY_PAUSE", "NEXT", "PREVIOUS", "STOP", "VOLUME_UP", "VOLUME_DOWN", "VOLUME_MUTE")
    }
}
