package com.remotecontroller.desktop.executor

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.WORD
import com.sun.jna.platform.win32.WinUser
import java.util.concurrent.TimeUnit

/**
 * Types characters, not key codes. AWT Robot can only press keys of the active layout,
 * so Cyrillic or emoji from the phone would be lost or come out wrong.
 */
interface TextTyper {
    /** False when the text was not delivered and the caller should fall back to Robot. */
    fun type(text: String): Boolean
}

internal object WindowsTextTyper : TextTyper {
    private const val KEYEVENTF_KEYUP = 0x2L
    private const val KEYEVENTF_UNICODE = 0x4L

    override fun type(text: String): Boolean {
        val template = WinUser.INPUT()
        @Suppress("UNCHECKED_CAST")
        val inputs = template.toArray(text.length * 2) as Array<WinUser.INPUT>
        text.forEachIndexed { index, char ->
            fill(inputs[index * 2], char, KEYEVENTF_UNICODE)
            fill(inputs[index * 2 + 1], char, KEYEVENTF_UNICODE or KEYEVENTF_KEYUP)
        }
        val sent = User32.INSTANCE.SendInput(DWORD(inputs.size.toLong()), inputs, template.size())
        return sent.toInt() == inputs.size
    }

    private fun fill(input: WinUser.INPUT, char: Char, flags: Long) {
        input.type = DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
        input.input.setType("ki")
        input.input.ki.wVk = WORD(0)
        input.input.ki.wScan = WORD(char.code.toLong())
        input.input.ki.dwFlags = DWORD(flags)
        input.input.ki.time = DWORD(0)
        input.input.ki.dwExtraInfo = ULONG_PTR(0)
    }
}

internal object MacTextTyper : TextTyper {
    private const val HID_EVENT_TAP = 0

    private val graphics: CoreGraphics? = runCatching {
        Native.load("CoreGraphics", CoreGraphics::class.java)
    }.getOrNull()

    private val foundation: CoreFoundation? = runCatching {
        Native.load("CoreFoundation", CoreFoundation::class.java)
    }.getOrNull()

    override fun type(text: String): Boolean {
        val api = graphics ?: return false
        val memory = foundation ?: return false
        var start = 0
        while (start < text.length) {
            val end = text.offsetByCodePoints(start, 1)
            val units = ShortArray(end - start) { text[start + it].code.toShort() }
            if (!post(api, memory, units, down = true) || !post(api, memory, units, down = false)) return false
            start = end
        }
        return true
    }

    private fun post(api: CoreGraphics, memory: CoreFoundation, units: ShortArray, down: Boolean): Boolean {
        val event = api.CGEventCreateKeyboardEvent(null, 0, down) ?: return false
        api.CGEventKeyboardSetUnicodeString(event, units.size.toLong(), units)
        api.CGEventPost(HID_EVENT_TAP, event)
        memory.CFRelease(event)
        return true
    }

    @Suppress("FunctionName")
    private interface CoreGraphics : Library {
        fun CGEventCreateKeyboardEvent(source: Pointer?, virtualKey: Short, keyDown: Boolean): Pointer?

        fun CGEventKeyboardSetUnicodeString(event: Pointer, length: Long, units: ShortArray)

        fun CGEventPost(tap: Int, event: Pointer)
    }

    @Suppress("FunctionName")
    private interface CoreFoundation : Library {
        fun CFRelease(reference: Pointer)
    }
}

/** X11 only. Needs `xdotool` for anything beyond the keys Robot can press. */
internal object LinuxTextTyper : TextTyper {
    private const val TIMEOUT_SECONDS = 30L

    override fun type(text: String): Boolean = runCatching {
        // Through stdin: arguments are visible to every process, and this may be a password.
        val process = ProcessBuilder("xdotool", "type", "--clearmodifiers", "--file", "-")
            .redirectErrorStream(true)
            .start()
        process.outputStream.use { it.write(text.encodeToByteArray()) }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return false
        }
        process.exitValue() == 0
    }.getOrDefault(false)
}
