package com.remotecontroller.desktop.executor

import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/** The screen through AWT Robot: slow, but it works wherever the desktop app does. Belongs to one thread. */
internal class RobotCapture {
    private var robot: Robot? = null
    private var scaledImage: BufferedImage? = null
    private var pixels = ByteArray(0)

    /** The picture in [bounds], no longer than [longEdge]. Reused by the next call. */
    fun grab(bounds: Rectangle, longEdge: Int): BufferedImage = scale(robot().createScreenCapture(bounds), longEdge)

    /** The bytes of a picture from [grab] in the order of blue, green, red and a spare byte. Reused by the next call. */
    fun bgra(image: BufferedImage): ByteArray {
        val source = (image.raster.dataBuffer as DataBufferInt).data
        if (pixels.size != source.size * 4) pixels = ByteArray(source.size * 4)
        ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(source)
        return pixels
    }

    private fun robot(): Robot = robot ?: Robot().apply {
        autoDelay = 0
        isAutoWaitForIdle = false
    }.also { robot = it }

    private fun scale(source: BufferedImage, longEdge: Int): BufferedImage {
        val (width, height) = scaledSize(source.width, source.height, longEdge)
        var dest = scaledImage
        if (dest == null || dest.width != width || dest.height != height) {
            dest = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            scaledImage = dest
        }
        val pixels = (dest.raster.dataBuffer as DataBufferInt).data
        val srcW = source.width
        val srcH = source.height
        val direct = source.raster.dataBuffer as? DataBufferInt
        val src = if (
            direct != null &&
            direct.offset == 0 &&
            direct.data.size == srcW * srcH &&
            (source.type == BufferedImage.TYPE_INT_RGB || source.type == BufferedImage.TYPE_INT_ARGB)
        ) {
            direct.data
        } else {
            null
        }
        for (y in 0 until height) {
            val sy = y * srcH / height
            val destRow = y * width
            val srcRow = sy * srcW
            for (x in 0 until width) {
                val sx = x * srcW / width
                val color = if (src != null) src[srcRow + sx] else source.getRGB(sx, sy)
                pixels[destRow + x] = color and 0x00FFFFFF
            }
        }
        return dest
    }

    private fun scaledSize(width: Int, height: Int, limit: Int): Pair<Int, Int> {
        val longEdge = max(width, height)
        if (longEdge <= limit) return width.coerceAtLeast(1) to height.coerceAtLeast(1)
        val scale = limit.toDouble() / longEdge
        val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return scaledWidth to scaledHeight
    }
}
