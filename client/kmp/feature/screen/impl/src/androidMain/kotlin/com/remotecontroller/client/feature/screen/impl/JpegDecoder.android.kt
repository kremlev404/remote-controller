package com.remotecontroller.client.feature.screen.impl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

private val options = BitmapFactory.Options()
private val buffers = arrayOfNulls<Bitmap>(3)
private var index = 0

internal actual fun decodeJpeg(bytes: ByteArray): ImageBitmap? {
    options.inMutable = true
    options.inBitmap = buffers[index]?.takeUnless { it.isRecycled }
    val decoded = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (_: IllegalArgumentException) {
        options.inBitmap = null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } ?: return null
    buffers[index] = decoded
    index = (index + 1) % buffers.size
    return decoded.asImageBitmap()
}
