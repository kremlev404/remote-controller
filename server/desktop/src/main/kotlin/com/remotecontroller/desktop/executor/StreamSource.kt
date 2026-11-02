package com.remotecontroller.desktop.executor

import com.remotecontroller.desktop.server.ScreenMode
import com.remotecontroller.host.screencast.Screencast
import com.remotecontroller.host.screencast.ScreencastResult
import com.remotecontroller.shared.network.ScreenFrame

/** What came out of one pass of a [StreamSession]. */
internal sealed interface StreamFrame {
    data object Unchanged : StreamFrame

    /** The desktop is out of reach for now. */
    data object Unavailable : StreamFrame

    /** This route can never work on this PC. */
    data object Failed : StreamFrame

    /** The system keeps the screen from this app until the user allows it. */
    data object NeedsAccess : StreamFrame

    /** One frame that depends on the ones before it, unless it is a [keyframe]. */
    class Encoded(
        val keyframe: Boolean,
        val pack: (sequence: Int) -> ByteArray,
    ) : StreamFrame
}

/** One way of turning the screen into frames that depend on each other. */
internal interface StreamSource {
    val name: String

    /** Null when this route can be tried here. */
    val unavailableReason: String?

    /** A choice beyond the mode and the display. When it changes, the stream opens a new session. */
    val variant: String
        get() = ""

    fun open(display: Int, mode: ScreenMode): StreamSession?
}

/** Belongs to the thread that opened it, as the native session does. */
internal interface StreamSession : AutoCloseable {
    /** Blocks until the next frame is due, and for a short while if the screen does not change. */
    fun next(keyframe: Boolean, bitrate: Int): StreamFrame

    fun status(): String

    override fun close()
}

internal class H264Source(
    private val preferredGpu: () -> String,
) : StreamSource {
    override val name = "H.264"

    override val unavailableReason: String?
        get() = Screencast.unavailableReason

    override val variant: String
        get() = preferredGpu()

    override fun open(display: Int, mode: ScreenMode): StreamSession? =
        Screencast.open(display, mode.longEdge, mode.framesPerSecond, mode.bitrate, variant)?.let(::H264Session)

    private class H264Session(
        private val screencast: Screencast,
    ) : StreamSession {
        override fun next(keyframe: Boolean, bitrate: Int): StreamFrame =
            when (val result = screencast.next(keyframe, bitrate)) {
                ScreencastResult.Unchanged -> StreamFrame.Unchanged
                ScreencastResult.Unavailable -> StreamFrame.Unavailable
                ScreencastResult.Failed -> StreamFrame.Failed
                ScreencastResult.NeedsAccess -> StreamFrame.NeedsAccess
                is ScreencastResult.Pixels -> StreamFrame.Failed
                is ScreencastResult.Encoded -> {
                    val size = result.annexB.remaining()
                    val packet = ScreenFrame.videoPacket(result.width, result.height, 0, result.keyframe, size)
                    result.annexB.get(packet, ScreenFrame.HEADER_SIZE, size)
                    StreamFrame.Encoded(result.keyframe) { sequence ->
                        ScreenFrame.stamp(packet, sequence)
                        packet
                    }
                }
            }

        override fun status(): String = screencast.status()

        override fun close() = screencast.close()
    }
}
