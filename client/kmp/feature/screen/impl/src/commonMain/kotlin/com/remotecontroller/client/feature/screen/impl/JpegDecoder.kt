package com.remotecontroller.client.feature.screen.impl

import androidx.compose.ui.graphics.ImageBitmap

internal expect fun decodeJpeg(bytes: ByteArray): ImageBitmap?
