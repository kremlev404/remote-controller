package com.remotecontroller.shared.network

/**
 * One screen frame: 10-byte header, then the picture.
 * Layout, big-endian: version u8, flags u8, width u16, height u16, sequence u32.
 * Version 1 carries JPEG bytes. Version 2 carries one H.264 access unit in Annex B form.
 * Version 3 carries the changed parts of the picture: a u16 count, then for each part
 * x u16, y u16, width u16, height u16, size u32 and that many JPEG bytes. A keyframe covers
 * the whole picture; every other frame paints over the one before it.
 */
object ScreenFrame {
    const val VERSION: Int = 1
    const val VIDEO_VERSION: Int = 2
    const val TILES_VERSION: Int = 3
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

    /** Writes the place in the stream into a packet that was built before it was known. */
    fun stamp(packet: ByteArray, sequence: Int) {
        putInt(packet, 6, sequence)
    }

    fun isTiles(packet: ByteArray): Boolean = packet.isNotEmpty() && (packet[0].toInt() and 0xFF) == TILES_VERSION

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

    fun packTiles(width: Int, height: Int, sequence: Int, keyframe: Boolean, tiles: List<Tile>): ByteArray {
        val packet = ByteArray(HEADER_SIZE + TILES_COUNT_SIZE + tiles.sumOf { TILE_HEADER_SIZE + it.jpeg.size })
        packet[0] = TILES_VERSION.toByte()
        packet[1] = (if (keyframe) FLAG_KEYFRAME else 0).toByte()
        putShort(packet, 2, width)
        putShort(packet, 4, height)
        putInt(packet, 6, sequence)
        putShort(packet, HEADER_SIZE, tiles.size)
        var offset = HEADER_SIZE + TILES_COUNT_SIZE
        for (tile in tiles) {
            putShort(packet, offset, tile.x)
            putShort(packet, offset + 2, tile.y)
            putShort(packet, offset + 4, tile.width)
            putShort(packet, offset + 6, tile.height)
            putInt(packet, offset + 8, tile.jpeg.size)
            tile.jpeg.copyInto(packet, offset + TILE_HEADER_SIZE)
            offset += TILE_HEADER_SIZE + tile.jpeg.size
        }
        return packet
    }

    /** Null for anything that is not a well-formed tiles packet: a part outside the picture is not one. */
    fun unpackTiles(packet: ByteArray): Tiles? {
        if (packet.size < HEADER_SIZE + TILES_COUNT_SIZE) return null
        if ((packet[0].toInt() and 0xFF) != TILES_VERSION) return null
        val width = shortAt(packet, 2)
        val height = shortAt(packet, 4)
        if (width !in 1..MAX_EDGE || height !in 1..MAX_EDGE) return null
        val count = shortAt(packet, HEADER_SIZE)
        val tiles = ArrayList<Tile>(count)
        var offset = HEADER_SIZE + TILES_COUNT_SIZE
        repeat(count) {
            if (offset + TILE_HEADER_SIZE > packet.size) return null
            val x = shortAt(packet, offset)
            val y = shortAt(packet, offset + 2)
            val tileWidth = shortAt(packet, offset + 4)
            val tileHeight = shortAt(packet, offset + 6)
            val size = intAt(packet, offset + 8)
            val start = offset + TILE_HEADER_SIZE
            if (size <= 0 || size > packet.size - start) return null
            if (tileWidth <= 0 || tileHeight <= 0 || x + tileWidth > width || y + tileHeight > height) return null
            tiles += Tile(x, y, tileWidth, tileHeight, packet.copyOfRange(start, start + size))
            offset = start + size
        }
        return Tiles(
            width = width,
            height = height,
            sequence = intAt(packet, 6),
            keyframe = (packet[1].toInt() and FLAG_KEYFRAME) != 0,
            tiles = tiles,
        )
    }

    class Tile(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val jpeg: ByteArray,
    )

    class Tiles(
        val width: Int,
        val height: Int,
        val sequence: Int,
        val keyframe: Boolean,
        val tiles: List<Tile>,
    )

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
    private const val TILES_COUNT_SIZE = 2
    private const val TILE_HEADER_SIZE = 12
}
