package com.remotecontroller.desktop.executor

import com.remotecontroller.desktop.server.ScreenMode
import com.remotecontroller.host.screencast.Screencast
import com.remotecontroller.host.screencast.ScreencastResult
import com.remotecontroller.shared.network.ScreenFrame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.Rectangle
import java.nio.ByteBuffer
import java.util.Arrays
import kotlin.math.min

/**
 * The route for when there is no H.264 on one side: no GPU encoder and no GStreamer on the PC, or a
 * viewer with no video decoder. It needs nothing but a JPEG decoder at the far end.
 *
 * Most of a desktop stands still, so only the parts that changed are sent. A large change is cut
 * into bands and compressed on every core at once, so a full screen of motion keeps its frame rate.
 * Pixels come from the native capture where the library has it, from AWT Robot otherwise.
 */
internal class TileSource(
    private val bounds: () -> Rectangle?,
) : StreamSource {
    override val name = "Tiles"

    override val unavailableReason: String? = null

    override fun open(display: Int, mode: ScreenMode): StreamSession {
        val pixels = Screencast.openPixels(display, mode.longEdge, mode.framesPerSecond)?.let(::NativePixels)
            ?: RobotPixels(bounds, mode)
        return TileSession(pixels, mode)
    }

    private fun interface PixelFeed : AutoCloseable {
        fun next(keyframe: Boolean): ScreencastResult

        fun status(): String = "ready"

        override fun close() = Unit
    }

    private class NativePixels(
        private val screencast: Screencast,
    ) : PixelFeed {
        override fun next(keyframe: Boolean): ScreencastResult = screencast.next(keyframe, 0)

        override fun status(): String = screencast.status()

        override fun close() = screencast.close()
    }

    private class RobotPixels(
        private val bounds: () -> Rectangle?,
        private val mode: ScreenMode,
    ) : PixelFeed {
        private val capture = RobotCapture()

        override fun next(keyframe: Boolean): ScreencastResult {
            val started = System.nanoTime()
            val area = bounds()?.takeIf { it.width > 0 && it.height > 0 } ?: return ScreencastResult.Unavailable
            val image = runCatching { capture.grab(area, mode.longEdge) }.getOrNull() ?: return ScreencastResult.Unavailable
            val pixels = ByteBuffer.wrap(capture.bgra(image))
            val rest = mode.frameInterval.inWholeNanoseconds - (System.nanoTime() - started)
            if (rest > 0) Thread.sleep(rest / NANOS_PER_MS, (rest % NANOS_PER_MS).toInt())
            return ScreencastResult.Pixels(image.width, image.height, image.width * BYTES_PER_PIXEL, pixels)
        }
    }

    private class TileSession(
        private val pixels: PixelFeed,
        private val mode: ScreenMode,
    ) : StreamSession {
        private val encoder = TileEncoder()

        override fun next(keyframe: Boolean, bitrate: Int): StreamFrame =
            when (val result = pixels.next(keyframe)) {
                is ScreencastResult.Pixels -> {
                    val tiles = encoder.encode(result.width, result.height, result.stride, result.bgra, keyframe, quality(bitrate))
                    if (tiles == null) {
                        StreamFrame.Unchanged
                    } else {
                        StreamFrame.Encoded(tiles.keyframe) { sequence ->
                            ScreenFrame.packTiles(result.width, result.height, sequence, tiles.keyframe, tiles.tiles)
                        }
                    }
                }
                ScreencastResult.Failed -> StreamFrame.Failed
                ScreencastResult.NeedsAccess -> StreamFrame.NeedsAccess
                ScreencastResult.Unchanged -> StreamFrame.Unchanged
                else -> StreamFrame.Unavailable
            }

        /** The same brake as the video bitrate: a slow link gets a softer picture at the same rate. */
        private fun quality(bitrate: Int): Int =
            (BEST_QUALITY * bitrate.toLong() / mode.bitrate).toInt().coerceIn(WORST_QUALITY, BEST_QUALITY)

        override fun status(): String = pixels.status()

        override fun close() = pixels.close()
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
        const val NANOS_PER_MS = 1_000_000L
        const val BEST_QUALITY = 75
        const val WORST_QUALITY = 35
    }
}

/** Finds what changed since the last picture and compresses only that. Runs on one thread. */
internal class TileEncoder {
    private var current = ByteArray(0)
    private var previous = ByteArray(0)
    private var width = 0
    private var height = 0

    class Encoded(
        val keyframe: Boolean,
        val tiles: List<ScreenFrame.Tile>,
    )

    /** Null when nothing changed. [whole] asks for the entire picture, for a viewer that has none. */
    fun encode(width: Int, height: Int, stride: Int, pixels: ByteBuffer, whole: Boolean, quality: Int): Encoded? {
        val resized = width != this.width || height != this.height
        if (resized) {
            this.width = width
            this.height = height
            current = ByteArray(width * height * BYTES_PER_PIXEL)
            previous = ByteArray(current.size)
        }
        copyRows(pixels, stride)

        val changed = if (whole || resized) null else changedAreas()
        if (changed != null && changed.isEmpty()) return null
        val everything = changed == null || changed.sumOf { it.width * it.height } > width * height * WHOLE_PICTURE_PERCENT / 100
        val areas = if (everything) listOf(Area(0, 0, width, height)) else changed
        val tiles = areas.orEmpty().flatMap(::bands).parallelStream().map { compress(it, quality) }.toList()

        val swap = previous
        previous = current
        current = swap
        return Encoded(everything, tiles)
    }

    private fun copyRows(pixels: ByteBuffer, stride: Int) {
        val rowBytes = width * BYTES_PER_PIXEL
        val source = pixels.duplicate()
        if (stride == rowBytes) {
            source.get(current, 0, rowBytes * height)
            return
        }
        for (row in 0 until height) {
            source.position(pixels.position() + row * stride)
            source.get(current, row * rowBytes, rowBytes)
        }
    }

    /** Whole columns of tiles, merged downwards where a run of them continues in the next row. */
    private fun changedAreas(): List<Area> {
        val rowBytes = width * BYTES_PER_PIXEL
        val areas = ArrayList<Area>()
        var open = emptyList<Area>()
        var top = 0
        while (top < height) {
            val bottom = min(top + TILE, height)
            val quiet = Arrays.equals(current, top * rowBytes, bottom * rowBytes, previous, top * rowBytes, bottom * rowBytes)
            val runs = if (quiet) emptyList() else changedRuns(top, bottom)
            val next = ArrayList<Area>(runs.size)
            for (run in runs) {
                val above = open.firstOrNull { it.x == run.first && it.width == run.last - run.first }
                if (above != null) {
                    above.height += bottom - top
                    next += above
                } else {
                    next += Area(run.first, top, run.last - run.first, bottom - top).also(areas::add)
                }
            }
            open = next
            top = bottom
        }
        return areas
    }

    private fun changedRuns(top: Int, bottom: Int): List<IntRange> {
        val runs = ArrayList<IntRange>()
        var start = -1
        var left = 0
        while (left < width) {
            val right = min(left + TILE, width)
            if (tileChanged(left, right, top, bottom)) {
                if (start < 0) start = left
            } else if (start >= 0) {
                runs += start..left
                start = -1
            }
            left = right
        }
        if (start >= 0) runs += start..width
        return runs
    }

    private fun tileChanged(left: Int, right: Int, top: Int, bottom: Int): Boolean {
        val rowBytes = width * BYTES_PER_PIXEL
        val from = left * BYTES_PER_PIXEL
        val to = right * BYTES_PER_PIXEL
        for (row in top until bottom) {
            val offset = row * rowBytes
            if (Arrays.mismatch(current, offset + from, offset + to, previous, offset + from, offset + to) >= 0) return true
        }
        return false
    }

    /** Cuts on the tile grid, which JPEG blocks line up with, so the seams do not show. */
    private fun bands(area: Area): List<Area> {
        if (area.width * area.height < BAND_PIXELS) return listOf(area)
        val rows = (area.height + TILE - 1) / TILE
        val count = min(rows, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
        val perBand = (rows + count - 1) / count * TILE
        return (0 until area.height step perBand).map { offset ->
            Area(area.x, area.y + offset, area.width, min(perBand, area.height - offset))
        }
    }

    private fun compress(area: Area, quality: Int): ScreenFrame.Tile {
        val rowBytes = area.width * BYTES_PER_PIXEL
        val pixels = ByteArray(rowBytes * area.height)
        for (row in 0 until area.height) {
            val offset = (area.y + row) * width * BYTES_PER_PIXEL + area.x * BYTES_PER_PIXEL
            System.arraycopy(current, offset, pixels, row * rowBytes, rowBytes)
        }
        val info = ImageInfo(area.width, area.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        val jpeg = Image.makeRaster(info, pixels, rowBytes).use { picture ->
            picture.encodeToData(EncodedImageFormat.JPEG, quality)?.use { it.bytes }
                ?: error("JPEG encoding failed")
        }
        return ScreenFrame.Tile(area.x, area.y, area.width, area.height, jpeg)
    }

    private class Area(
        val x: Int,
        val y: Int,
        val width: Int,
        var height: Int,
    )

    private companion object {
        const val BYTES_PER_PIXEL = 4
        const val TILE = 64
        const val BAND_PIXELS = 256 * 256
        const val WHOLE_PICTURE_PERCENT = 70
    }
}
