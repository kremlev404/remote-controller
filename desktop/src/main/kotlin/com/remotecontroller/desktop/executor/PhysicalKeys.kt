package com.remotecontroller.desktop.executor

import com.remotecontroller.desktop.platform.DesktopOs
import java.awt.event.KeyEvent

/**
 * Browser `KeyboardEvent.code` to AWT key codes. These name the key's position, not its letter,
 * so the PC's own layout decides what comes out, as in a virtual machine.
 */
internal object PhysicalKeys {
    private val SYSTEM_KEY = if (DesktopOs.current == DesktopOs.Mac) KeyEvent.VK_META else KeyEvent.VK_WINDOWS

    private val named = mapOf(
        "Escape" to KeyEvent.VK_ESCAPE,
        "Tab" to KeyEvent.VK_TAB,
        "CapsLock" to KeyEvent.VK_CAPS_LOCK,
        "Enter" to KeyEvent.VK_ENTER,
        "NumpadEnter" to KeyEvent.VK_ENTER,
        "Space" to KeyEvent.VK_SPACE,
        "Backspace" to KeyEvent.VK_BACK_SPACE,
        "Delete" to KeyEvent.VK_DELETE,
        "Insert" to KeyEvent.VK_INSERT,
        "Home" to KeyEvent.VK_HOME,
        "End" to KeyEvent.VK_END,
        "PageUp" to KeyEvent.VK_PAGE_UP,
        "PageDown" to KeyEvent.VK_PAGE_DOWN,
        "ArrowUp" to KeyEvent.VK_UP,
        "ArrowDown" to KeyEvent.VK_DOWN,
        "ArrowLeft" to KeyEvent.VK_LEFT,
        "ArrowRight" to KeyEvent.VK_RIGHT,
        "ShiftLeft" to KeyEvent.VK_SHIFT,
        "ShiftRight" to KeyEvent.VK_SHIFT,
        "ControlLeft" to KeyEvent.VK_CONTROL,
        "ControlRight" to KeyEvent.VK_CONTROL,
        "AltLeft" to KeyEvent.VK_ALT,
        "AltRight" to KeyEvent.VK_ALT,
        "MetaLeft" to SYSTEM_KEY,
        "MetaRight" to SYSTEM_KEY,
        "ContextMenu" to KeyEvent.VK_CONTEXT_MENU,
        "PrintScreen" to KeyEvent.VK_PRINTSCREEN,
        "ScrollLock" to KeyEvent.VK_SCROLL_LOCK,
        "Pause" to KeyEvent.VK_PAUSE,
        "NumLock" to KeyEvent.VK_NUM_LOCK,
        "Minus" to KeyEvent.VK_MINUS,
        "Equal" to KeyEvent.VK_EQUALS,
        "BracketLeft" to KeyEvent.VK_OPEN_BRACKET,
        "BracketRight" to KeyEvent.VK_CLOSE_BRACKET,
        "Backslash" to KeyEvent.VK_BACK_SLASH,
        "Semicolon" to KeyEvent.VK_SEMICOLON,
        "Quote" to KeyEvent.VK_QUOTE,
        "Backquote" to KeyEvent.VK_BACK_QUOTE,
        "Comma" to KeyEvent.VK_COMMA,
        "Period" to KeyEvent.VK_PERIOD,
        "Slash" to KeyEvent.VK_SLASH,
        "NumpadAdd" to KeyEvent.VK_ADD,
        "NumpadSubtract" to KeyEvent.VK_SUBTRACT,
        "NumpadMultiply" to KeyEvent.VK_MULTIPLY,
        "NumpadDivide" to KeyEvent.VK_DIVIDE,
        "NumpadDecimal" to KeyEvent.VK_DECIMAL,
    )

    fun keyCode(code: String): Int? {
        named[code]?.let { return it }
        return when {
            code.length == 4 && code.startsWith("Key") && code[3] in 'A'..'Z' ->
                KeyEvent.VK_A + (code[3] - 'A')
            code.length == 6 && code.startsWith("Digit") && code[5].isDigit() ->
                KeyEvent.VK_0 + (code[5] - '0')
            code.length == 7 && code.startsWith("Numpad") && code[6].isDigit() ->
                KeyEvent.VK_NUMPAD0 + (code[6] - '0')
            code.startsWith("F") -> functionKey(code.drop(1).toIntOrNull())
            else -> null
        }
    }

    private fun functionKey(number: Int?): Int? = when (number) {
        in 1..12 -> KeyEvent.VK_F1 + (number!! - 1)
        in 13..24 -> KeyEvent.VK_F13 + (number!! - 13)
        else -> null
    }
}
