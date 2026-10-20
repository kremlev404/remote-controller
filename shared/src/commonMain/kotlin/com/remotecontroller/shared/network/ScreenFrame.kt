package com.remotecontroller.shared.network

/**
 * One screen frame: 10-byte header, then JPEG bytes.
 * Layout, big-endian: version u8, flags u8, width u16, height u16, sequence u32.
 */
object ScreenFrame {
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 10

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
