package com.remotecontroller.desktop.executor

/**
 * Windows virtual-key codes for [java.awt.Robot].
 * Defined locally because extended media keys are not exposed on [java.awt.event.KeyEvent]
 * in all JDK / Compose Desktop compile configurations.
 */
internal object WindowsKeyCodes {
    const val VOLUME_MUTE = 0xAD
    const val VOLUME_DOWN = 0xAE
    const val VOLUME_UP = 0xAF
    const val MEDIA_PREVIOUS = 0xB0
    const val MEDIA_NEXT = 0xB1
    const val MEDIA_STOP = 0xB2
    const val MEDIA_PLAY_PAUSE = 0xB3
}
