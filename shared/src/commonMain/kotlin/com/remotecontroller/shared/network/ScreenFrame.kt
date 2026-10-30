package com.remotecontroller.shared.network

/**
 * One screen frame: 10-byte header, then the picture.
 * Layout, big-endian: version u8, flags u8, width u16, height u16, sequence u32.
 * Version 1 carries JPEG bytes. Version 2 carries one H.264 access unit in Annex B form.
 */
object ScreenFrame {
    const val VERSION: Int = 1
    const val VIDEO_VERSION: Int = 2
    const val HEADER_SIZE: Int = 10
    const val FLAG_KEYFRAME: Int = 1

    fun pack(width: Int, height: Int, sequence: Int, jpeg: ByteArray): ByteArray {
        val packet = ByteArray(HEADER_SIZE + jpeg.size)
        packet[0] = VERSION.toByte()
        packet[1] = 0
        putShort(packet, 2, width)
        putShort(packet, 4, height)
        putInt(packet, 6, sequence)
        jpeg.copyInto(packet, HEADER_SIZE)
        return packet
    }

    fun unpack(packet: ByteArray): Picture? {
        if (packet.size <= HEADER_SIZE) return null
        if ((packet[0].toInt() and 0xFF) != VERSION) return null
        val width = shortAt(packet, 2)
        val height = shortAt(packet, 4)
        if (width !in 1..MAX_EDGE || height !in 1..MAX_EDGE) return null
        val sequence = intAt(packet, 6)
        val jpeg = packet.copyOfRange(HEADER_SIZE, packet.size)
        if (jpeg.isEmpty()) return null
        return Picture(width = width, height = height, sequence = sequence, jpeg = jpeg)
    }

    /** A packet with the header filled in and room for [size] bytes of video after it. */
    fun videoPacket(width: Int, height: Int, sequence: Int, keyframe: Boolean, size: Int): ByteArray {
        val packet = ByteArray(HEADER_SIZE + size)
        packet[0] = VIDEO_VERSION.toByte()
        packet[1] = (if (keyframe) FLAG_KEYFRAME else 0).toByte()
        putShort(packet, 2, width)
        putShort(packet, 4, height)
        putInt(packet, 6, sequence)
        return packet
    }

    fun unpackVideo(packet: ByteArray): Video? {
        if (packet.size <= HEADER_SIZE) return null
        if ((packet[0].toInt() and 0xFF) != VIDEO_VERSION) return null
        val width = shortAt(packet, 2)
        val height = shortAt(packet, 4)
        if (width !in 1..MAX_EDGE || height !in 1..MAX_EDGE) return null
        return Video(
            width = width,
            height = height,
            sequence = intAt(packet, 6),
            keyframe = (packet[1].toInt() and FLAG_KEYFRAME) != 0,
            data = packet.copyOfRange(HEADER_SIZE, packet.size),
        )
    }

    class Video(
        val width: Int,
        val height: Int,
        val sequence: Int,
        val keyframe: Boolean,
        val data: ByteArray,
    )

    data class Picture(
        val width: Int,
        val height: Int,
        val sequence: Int,
        val jpeg: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Picture

            if (width != other.width) return false
            if (height != other.height) return false
            if (sequence != other.sequence) return false
            if (!jpeg.contentEquals(other.jpeg)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + sequence
            result = 31 * result + jpeg.contentHashCode()
            return result
        }
    }

    private fun putShort(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 8).toByte()
        packet[offset + 1] = value.toByte()
    }

    private fun putInt(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 24).toByte()
        packet[offset + 1] = (value ushr 16).toByte()
        packet[offset + 2] = (value ushr 8).toByte()
        packet[offset + 3] = value.toByte()
    }

    private fun shortAt(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)

    private fun intAt(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 24) or
            ((packet[offset + 1].toInt() and 0xFF) shl 16) or
            ((packet[offset + 2].toInt() and 0xFF) shl 8) or
            (packet[offset + 3].toInt() and 0xFF)

    private const val MAX_EDGE = 16_384
}
