package com.remotecontroller.desktop.executor

import com.remotecontroller.desktop.platform.LinuxCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

interface MediaKeys {
    fun keyCode(name: String): Int?

    /** Handles a media key without the keyboard. True when done; otherwise [keyCode] is pressed. */
    fun send(name: String): Boolean = false
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

/**
 * X11 has no AWT key codes for media keys. Players on Linux speak MPRIS, which `playerctl` drives;
 * volume keys go to the volume controller.
 */
internal class LinuxMediaKeys(
    private val volume: VolumeController,
) : MediaKeys {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun keyCode(name: String): Int? = null

    override fun send(name: String): Boolean {
        val playerctl = when (name) {
            "PLAY_PAUSE" -> "play-pause"
            "NEXT" -> "next"
            "PREVIOUS" -> "previous"
            "STOP" -> "stop"
            else -> null
        }
        if (playerctl != null) {
            return LinuxCommands.run("playerctl", playerctl) != null
        }
        when (name) {
            "VOLUME_UP" -> scope.launch { volume.volumeUp() }
            "VOLUME_DOWN" -> scope.launch { volume.volumeDown() }
            "VOLUME_MUTE" -> scope.launch { volume.toggleMute() }
            else -> return false
        }
        return true
    }
}
