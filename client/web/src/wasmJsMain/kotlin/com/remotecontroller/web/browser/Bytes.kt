package com.remotecontroller.web.browser

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int32Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

private const val WORD_BYTES = 4
private const val BITS_PER_BYTE = 8
private const val BYTE_MASK = 0xFF

/**
 * Each element of a typed array is one call out of Wasm, which adds up over a video frame, so bytes
 * cross four at a time. Typed arrays use the CPU's byte order: little endian wherever browsers run.
 */
fun ArrayBuffer.toByteArray(): ByteArray {
    val size = byteLength
    val whole = size / WORD_BYTES * WORD_BYTES
    val bytes = ByteArray(size)
    val words = Int32Array(this, 0, size / WORD_BYTES)
    for (index in 0 until words.length) {
        val word = words[index]
        val offset = index * WORD_BYTES
        for (part in 0 until WORD_BYTES) bytes[offset + part] = (word ushr (part * BITS_PER_BYTE)).toByte()
    }
    val tail = Uint8Array(this, whole, size - whole)
    for (index in 0 until tail.length) bytes[whole + index] = tail[index]
    return bytes
}

fun ByteArray.toUint8Array(): Uint8Array {
    val buffer = ArrayBuffer(size)
    val whole = size / WORD_BYTES * WORD_BYTES
    val words = Int32Array(buffer, 0, size / WORD_BYTES)
    for (index in 0 until words.length) {
        val offset = index * WORD_BYTES
        var word = 0
        for (part in 0 until WORD_BYTES) word = word or ((this[offset + part].toInt() and BYTE_MASK) shl (part * BITS_PER_BYTE))
        words[index] = word
    }
    val tail = Uint8Array(buffer, whole, size - whole)
    for (index in 0 until tail.length) tail[index] = this[whole + index]
    return Uint8Array(buffer)
}
