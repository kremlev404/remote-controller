package com.remotecontroller.desktop.platform

import com.sun.jna.Library
import com.sun.jna.Native

/**
 * macOS throws away synthetic mouse and keyboard events from an app that is not allowed under
 * Privacy & Security → Accessibility. Robot reports no error, so the app has to ask.
 * A rebuilt app counts as a new one: the old entry can look enabled and still not work.
 */
object InputAccess {
    private const val SETTINGS_PAGE =
        "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"

    private val graphics: CoreGraphics? = if (DesktopOs.current == DesktopOs.Mac) {
        runCatching { Native.load("CoreGraphics", CoreGraphics::class.java) }.getOrNull()
    } else {
        null
    }

    val blocked: Boolean
        get() = graphics?.let { it.CGPreflightPostEventAccess() == 0.toByte() } ?: false

    /** Adds this app to the list and opens it. */
    fun request() {
        graphics?.CGRequestPostEventAccess()
        runCatching { ProcessBuilder("open", SETTINGS_PAGE).start() }
    }

    @Suppress("FunctionName")
    private interface CoreGraphics : Library {
        fun CGPreflightPostEventAccess(): Byte

        fun CGRequestPostEventAccess(): Byte
    }
}
