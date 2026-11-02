package com.remotecontroller.desktop.server

import com.remotecontroller.shared.model.ClipNote
import java.util.Base64

internal data class ClipImage(
    val bytes: ByteArray,
    val type: String,
) {
    val base64: String
        get() = Base64.getEncoder().encodeToString(bytes)
}

internal object ClipImages {
    fun normalize(base64: String): ClipImage? {
        if (base64.length > ClipNote.MAX_IMAGE_BYTES * 2) return null
        val bytes = runCatching { Base64.getMimeDecoder().decode(base64) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > ClipNote.MAX_IMAGE_BYTES) return null
        val type = sniff(bytes) ?: return null
        return ClipImage(bytes, type)
    }

    fun sniff(bytes: ByteArray): String? = when {
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() -> "image/png"
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() -> "image/gif"
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte() -> "image/webp"
        bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "image/bmp"
        else -> null
    }
}
