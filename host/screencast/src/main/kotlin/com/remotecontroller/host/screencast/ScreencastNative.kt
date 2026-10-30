package com.remotecontroller.host.screencast

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What came out of one [Screencast.next] call. */
sealed interface ScreencastResult {
    /** The screen did not change. */
    data object Unchanged : ScreencastResult

    /** The desktop is out of reach for now: a mode change, the UAC prompt or the lock screen. */
    data object Unavailable : ScreencastResult

    /** One encoded picture. Its bytes stay valid until the next call. */
    data class Encoded(
        val width: Int,
        val height: Int,
        val keyframe: Boolean,
        val annexB: ByteBuffer,
    ) : ScreencastResult
}

/**
 * Windows capture and hardware H.264 from screencast.dll: one display, one encoder.
 *
 * The native session belongs to the thread that opened it. [next], [status] and [close] must all
 * run on that thread, so nothing in the library needs a lock and a late call cannot touch a closed
 * session.
 */
class Screencast private constructor(
    private var handle: Long,
) : AutoCloseable {
    private val output = ByteBuffer.allocateDirect(OUTPUT_BYTES).order(ByteOrder.nativeOrder())

    /** Blocks until the next frame is due, and for a short while if the screen does not change. */
    fun next(keyframe: Boolean, bitrate: Int): ScreencastResult {
        val size = ScreencastNative.nativeNext(handle, output, keyframe, bitrate)
        if (size == ScreencastNative.NEXT_UNCHANGED) return ScreencastResult.Unchanged
        if (size < 0) return ScreencastResult.Unavailable
        val width = output.getInt(0)
        val height = output.getInt(4)
        val flags = output.getInt(8)
        val bytes = output.duplicate().position(HEADER_BYTES).limit(HEADER_BYTES + size).slice()
        return ScreencastResult.Encoded(width, height, flags and FLAG_KEYFRAME != 0, bytes)
    }

    fun status(): String = ScreencastNative.nativeStatus(handle)

    override fun close() {
        val current = handle
        handle = 0
        if (current != 0L) ScreencastNative.nativeClose(current)
    }

    companion object {
        private const val OUTPUT_BYTES = 4 * 1024 * 1024
        private const val HEADER_BYTES = 16
        private const val FLAG_KEYFRAME = 1

        val isAvailable: Boolean
            get() = ScreencastNative.failure == null

        /** Why the library cannot be used here, or null when it can. */
        val unavailableReason: String?
            get() = ScreencastNative.failure

        /** Null where the library does not exist. A failing GPU shows up later, in [status]. */
        fun open(displayIndex: Int, maxEdge: Int, framesPerSecond: Int, bitrate: Int): Screencast? {
            if (!isAvailable) return null
            val handle = ScreencastNative.nativeOpen(displayIndex, maxEdge, framesPerSecond, bitrate)
            return if (handle == 0L) null else Screencast(handle)
        }
    }
}

/**
 * The library ships in the app's own resources folder and loads by its full path. It is never
 * unpacked to a temp folder and never looked up on the search path, where another program could
 * plant a file of the same name. Compose names that folder for packaged apps and `:desktop:run`;
 * every other Gradle run of the app, such as the IDE's, gets [DEVELOPMENT_FOLDER].
 */
internal object ScreencastNative {
    const val NEXT_UNCHANGED = 0
    private const val PACKAGED_FOLDER = "compose.application.resources.dir"
    private const val DEVELOPMENT_FOLDER = "screencast.library.dir"

    /** Null once the library is loaded. */
    val failure: String? = load()

    @JvmStatic
    external fun nativeOpen(displayIndex: Int, maxEdge: Int, framesPerSecond: Int, bitrate: Int): Long

    @JvmStatic
    external fun nativeNext(handle: Long, output: ByteBuffer, keyframe: Boolean, bitrate: Int): Int

    @JvmStatic
    external fun nativeStatus(handle: Long): String

    @JvmStatic
    external fun nativeClose(handle: Long)

    private fun load(): String? {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return "this PC is not on Windows"
        val library = listOf(PACKAGED_FOLDER, DEVELOPMENT_FOLDER)
            .mapNotNull(System::getProperty)
            .map { folder -> File(folder, "screencast.dll") }
            .firstOrNull(File::isFile)
            ?: return "screencast.dll was not found: start the app through Gradle or as a packaged app"
        return runCatching { System.load(library.canonicalPath) }
            .exceptionOrNull()
            ?.let { error -> "screencast.dll did not load: ${error.message}" }
    }
}
