package com.remotecontroller.desktop.executor

interface MediaKeys {
    fun keyCode(name: String): Int?
}

internal object WindowsMediaKeys : MediaKeys {
    override fun keyCode(name: String): Int? = when (name) {
        "PLAY_PAUSE" -> WindowsKeyCodes.MEDIA_PLAY_PAUSE
        "NEXT" -> WindowsKeyCodes.MEDIA_NEXT
        "PREVIOUS" -> WindowsKeyCodes.MEDIA_PREVIOUS
        "STOP" -> WindowsKeyCodes.MEDIA_STOP
        "VOLUME_UP" -> WindowsKeyCodes.VOLUME_UP
        "VOLUME_DOWN" -> WindowsKeyCodes.VOLUME_DOWN
        "VOLUME_MUTE" -> WindowsKeyCodes.VOLUME_MUTE
        else -> null
    }
}

/**
 * macOS media keys are not Windows virtual-key codes. AWT Robot ignores 0xAD–0xB3 there.
 * The Mac desktop ticket should send these through CoreGraphics / NSEvent.
 */
internal object MacMediaKeys : MediaKeys {
    override fun keyCode(name: String): Int? = null
}
