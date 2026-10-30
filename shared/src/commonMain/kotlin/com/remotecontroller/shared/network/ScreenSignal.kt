package com.remotecontroller.shared.network

import kotlinx.serialization.Serializable

/**
 * Text frames on the screen socket. Picture frames stay binary.
 * The desktop sends [PAUSED] and [LIVE] when sharing is stopped or started again.
 * A viewer says which video it can play right after the link is up, and asks for a keyframe
 * when its decoder lost the picture. All of them travel sealed, like every other frame.
 */
object ScreenSignal {
    const val PAUSED = """{"type":"paused"}"""
    const val LIVE = """{"type":"live"}"""
    const val KEYFRAME = """{"type":"keyframe"}"""
    const val MAX_VIEWER_TEXT = 1024

    fun isPaused(text: String): Boolean = text.contains("\"paused\"")

    fun isLive(text: String): Boolean = text.contains("\"live\"")

    fun isKeyframeRequest(text: String): Boolean = text == KEYFRAME

    fun viewer(codecs: List<String>): String = RemoteJson.encode(ScreenViewer(codecs = codecs))

    /** The codecs of a viewer's first message. Empty for anything else. */
    fun viewerCodecs(text: String): List<String> =
        runCatching { RemoteJson.decode<ScreenViewer>(text) }.getOrNull()?.codecs.orEmpty()
}

object ScreenCodec {
    const val H264 = "h264"
}

@Serializable
private data class ScreenViewer(
    val type: String = "viewer",
    val codecs: List<String> = emptyList(),
)

sealed interface ScreenFeed {
    data class Picture(val picture: ScreenFrame.Picture) : ScreenFeed
    data class Video(val video: ScreenFrame.Video) : ScreenFeed
    data object HostPaused : ScreenFeed
    data object HostLive : ScreenFeed
}
