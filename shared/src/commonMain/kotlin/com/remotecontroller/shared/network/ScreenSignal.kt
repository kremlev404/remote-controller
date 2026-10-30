package com.remotecontroller.shared.network

/**
 * Text frames on the screen socket. Picture frames stay binary.
 * The desktop sends these when sharing is stopped or started again.
 */
object ScreenSignal {
    const val PAUSED = """{"type":"paused"}"""
    const val LIVE = """{"type":"live"}"""

    fun isPaused(text: String): Boolean = text.contains("\"paused\"")

    fun isLive(text: String): Boolean = text.contains("\"live\"")
}

sealed interface ScreenFeed {
    data class Picture(val picture: ScreenFrame.Picture) : ScreenFeed
    data object HostPaused : ScreenFeed
    data object HostLive : ScreenFeed
}
