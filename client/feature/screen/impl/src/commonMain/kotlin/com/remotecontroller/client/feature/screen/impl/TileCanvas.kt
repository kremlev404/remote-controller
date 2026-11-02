package com.remotecontroller.client.feature.screen.impl

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.remotecontroller.shared.network.ScreenFrame
import kotlin.concurrent.Volatile

/** The picture of a tile stream: each frame paints only what changed over the one before it. */
internal class TileCanvas {
    var bitmap: ImageBitmap? = null
        private set

    private var canvas: Canvas? = null
    private val paint = Paint()

    @Volatile
    private var awaitingKeyframe = true

    /** After a gap the picture is wrong until a full one arrives. */
    fun invalidate() {
        awaitingKeyframe = true
    }

    /** False when [frame] could not be shown, which takes a keyframe to mend. */
    fun paint(frame: ScreenFrame.Tiles): Boolean {
        if (frame.keyframe) awaitingKeyframe = false
        if (awaitingKeyframe) return false
        val target = canvasFor(frame.width, frame.height) ?: return false
        for (tile in frame.tiles) {
            val image = decodeJpeg(tile.jpeg)
            if (image == null) {
                awaitingKeyframe = true
                return false
            }
            target.drawImageRect(
                image = image,
                dstOffset = IntOffset(tile.x, tile.y),
                dstSize = IntSize(tile.width, tile.height),
                paint = paint,
            )
        }
        return true
    }

    private fun canvasFor(width: Int, height: Int): Canvas? {
        val current = bitmap
        if (current != null && current.width == width && current.height == height) return canvas
        val fresh = ImageBitmap(width, height)
        bitmap = fresh
        return Canvas(fresh).also { canvas = it }
    }
}
