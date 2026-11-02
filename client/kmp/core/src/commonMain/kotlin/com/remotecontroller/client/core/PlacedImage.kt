package com.remotecontroller.client.core

import androidx.compose.ui.geometry.Offset
import kotlin.math.min

/** Where a desktop picture sits inside its box, so a pointer position can be turned into a place on the PC's screen. */
data class PlacedImage(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    fun contains(position: Offset): Boolean =
        position.x in left..(left + width) && position.y in top..(top + height)

    fun normalize(position: Offset): Offset {
        val x = if (width <= 0f) 0f else ((position.x - left) / width).coerceIn(0f, 1f)
        val y = if (height <= 0f) 0f else ((position.y - top) / height).coerceIn(0f, 1f)
        return Offset(x, y)
    }
}

fun letterbox(boxWidth: Float, boxHeight: Float, imageWidth: Int, imageHeight: Int): PlacedImage {
    if (boxWidth <= 0f || boxHeight <= 0f || imageWidth <= 0 || imageHeight <= 0) {
        return PlacedImage(0f, 0f, boxWidth.coerceAtLeast(0f), boxHeight.coerceAtLeast(0f))
    }
    val scale = min(boxWidth / imageWidth, boxHeight / imageHeight)
    val drawnWidth = imageWidth * scale
    val drawnHeight = imageHeight * scale
    return PlacedImage(
        left = (boxWidth - drawnWidth) / 2f,
        top = (boxHeight - drawnHeight) / 2f,
        width = drawnWidth,
        height = drawnHeight,
    )
}
