package com.remotecontroller.desktop.platform

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window
import javax.swing.JFrame

internal fun Window.applySystemTitleBar(dark: Boolean) {
    background = if (dark) java.awt.Color(0x12, 0x12, 0x12) else java.awt.Color(0xF5, 0xF5, 0xF5)
    if (this is JFrame) {
        rootPane.putClientProperty(
            "apple.awt.application.appearance",
            if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua",
        )
    }
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        WindowsImmersiveTitleBar.apply(this, dark)
    }
}

private object WindowsImmersiveTitleBar {
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20

    private val dwmapi: Dwmapi? = try {
        Native.load("dwmapi", Dwmapi::class.java)
    } catch (_: UnsatisfiedLinkError) {
        null
    }

    fun apply(window: Window, dark: Boolean) {
        val api = dwmapi ?: return
        val pointer = Native.getComponentPointer(window)
        if (pointer == null || pointer == Pointer.NULL) return
        val value = Memory(4).apply { setInt(0, if (dark) 1 else 0) }
        api.DwmSetWindowAttribute(HWND(pointer), DWMWA_USE_IMMERSIVE_DARK_MODE, value, 4)
    }

    private interface Dwmapi : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: HWND, attribute: Int, pvAttribute: Pointer, cbAttribute: Int): Int
    }
}
