package com.remotecontroller.shared.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenFeedTest {
    private val bytes = byteArrayOf(1, 2, 3, 4, 5)

    @Test
    fun aVideoPacketIsVideo() {
        val packet = ScreenFrame.videoPacket(1920, 1080, 7, true, bytes.size)
        bytes.copyInto(packet, ScreenFrame.HEADER_SIZE)

        val feed = assertIs<ScreenFeed.Video>(ScreenFeed.read(LinkFrame.BYTES, packet))

        assertEquals(1920, feed.video.width)
        assertTrue(feed.video.keyframe)
        assertContentEquals(bytes, feed.video.data)
    }

    @Test
    fun aTilePacketIsTiles() {
        val tile = ScreenFrame.Tile(64, 128, 32, 16, bytes)
        val packet = ScreenFrame.packTiles(1280, 720, 3, false, listOf(tile))

        val feed = assertIs<ScreenFeed.Tiles>(ScreenFeed.read(LinkFrame.BYTES, packet))

        assertEquals(1280, feed.tiles.width)
        assertEquals(64, feed.tiles.tiles.single().x)
        assertContentEquals(bytes, feed.tiles.tiles.single().jpeg)
    }

    @Test
    fun aJpegPacketIsAPicture() {
        val feed = assertIs<ScreenFeed.Picture>(ScreenFeed.read(LinkFrame.BYTES, ScreenFrame.pack(640, 480, 1, bytes)))

        assertEquals(640, feed.picture.width)
        assertContentEquals(bytes, feed.picture.jpeg)
    }

    @Test
    fun signalsSayWhetherThePcIsSharing() {
        assertEquals(ScreenFeed.HostPaused, ScreenFeed.read(LinkFrame.TEXT, ScreenSignal.PAUSED.encodeToByteArray()))
        assertEquals(ScreenFeed.HostLive, ScreenFeed.read(LinkFrame.TEXT, ScreenSignal.LIVE.encodeToByteArray()))
    }

    @Test
    fun whatAViewerHasNoUseForIsIgnored() {
        assertNull(ScreenFeed.read(LinkFrame.TEXT, """{"type":"other"}""".encodeToByteArray()))
        assertNull(ScreenFeed.read(0, bytes))
        assertNull(ScreenFeed.read(LinkFrame.BYTES, ByteArray(0)))
    }
}
