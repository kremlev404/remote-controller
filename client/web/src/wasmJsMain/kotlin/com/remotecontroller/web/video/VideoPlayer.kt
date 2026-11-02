@file:OptIn(ExperimentalWasmJsInterop::class)

package com.remotecontroller.web.video

import com.remotecontroller.shared.network.ScreenFrame
import com.remotecontroller.web.browser.toUint8Array
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.ImageBitmap
import kotlin.js.ExperimentalWasmJsInterop

private const val MAX_QUEUE = 8
private const val FRAME_MICROS = 16667.0
private const val MAX_TILE_PACKETS = 8
private const val NAL_TYPE_MASK = 31
private const val NAL_SPS = 7
private const val BYTE_MASK = 0xFF

/** The sequence header names the profile and level, in the form WebCodecs asks for. */
private fun codecOf(bytes: ByteArray): String {
    for (index in 0 until bytes.size - 7) {
        val startCode = bytes[index].toInt() == 0 && bytes[index + 1].toInt() == 0 && bytes[index + 2].toInt() == 1
        if (startCode && (bytes[index + 3].toInt() and NAL_TYPE_MASK) == NAL_SPS) {
            return "avc1." + (4..6).joinToString("") { offset ->
                (bytes[index + offset].toInt() and BYTE_MASK).toString(16).padStart(2, '0')
            }
        }
    }
    return ""
}

/**
 * Plays H.264 with the browser's decoder, onto a canvas behind the Compose page. The page
 * leaves a clear hole over it, so its buttons stay on top and clicks still reach Compose.
 * Only the newest frame is painted, once per display refresh. [onLost] asks for a new keyframe:
 * frames depend on each other, so after a gap nothing can be shown until one arrives.
 * A tile stream paints onto the same canvas: each packet's parts land together, in order.
 */
class VideoPlayer(
    private val scope: CoroutineScope,
    private val onShown: (Int, Int) -> Unit,
    private val onLost: () -> Unit,
) {
    private val canvas = (document.createElement("canvas") as HTMLCanvasElement).apply {
        style.cssText = "position:fixed;left:0;top:0;width:0;height:0;z-index:0;pointer-events:none"
        document.body?.appendChild(this)
    }
    private val paper = canvas.getContext("2d", opaqueCanvas()) as CanvasRenderingContext2D

    private var decoder: VideoDecoder? = null
    private var codec = ""
    private var awaitingKey = true
    private var timestamp = 0.0
    private var latest: VideoFrame? = null
    private var painting = false

    private var awaitingTiles = true
    private var tilePackets = 0
    private var tileEpoch = 0
    private var lastTiles: Job? = null

    fun decode(bytes: ByteArray, keyframe: Boolean) {
        if (awaitingKey && !keyframe) return
        if (keyframe) {
            val wanted = codecOf(bytes)
            if (wanted.isNotEmpty() && wanted != codec) {
                (decoder ?: openDecoder().also { decoder = it }).configure(decoderConfig(wanted))
                codec = wanted
            }
            awaitingKey = false
        }
        val current = decoder ?: return
        if (codec.isEmpty()) return
        if (current.decodeQueueSize > MAX_QUEUE) {
            lose()
            return
        }
        timestamp += FRAME_MICROS
        val type = if (keyframe) "key" else "delta"
        current.decode(EncodedVideoChunk(videoChunk(type, timestamp, bytes.toUint8Array())))
    }

    fun paintTiles(frame: ScreenFrame.Tiles) {
        if (awaitingTiles && !frame.keyframe) return
        if (tilePackets >= MAX_TILE_PACKETS) {
            awaitingTiles = true
            onLost()
            return
        }
        awaitingTiles = false
        tilePackets++
        // Every part starts decoding now, and a packet waits for the one before it to land.
        val parts = frame.tiles.map { tile -> tile to decodeJpeg(tile.jpeg.toUint8Array()) }
        val epoch = tileEpoch
        val before = lastTiles
        lastTiles = scope.launch {
            before?.join()
            try {
                val images = parts.map { (tile, picture) -> tile to picture.await<ImageBitmap>() }
                tilePackets--
                if (epoch == tileEpoch) {
                    fitCanvas(frame.width, frame.height)
                    images.forEach { (tile, image) -> paper.drawImage(image, tile.x.toDouble(), tile.y.toDouble()) }
                }
                images.forEach { (_, image) -> image.close() }
                if (epoch == tileEpoch) onShown(frame.width, frame.height)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                tilePackets--
                awaitingTiles = true
                onLost()
            }
        }
    }

    /** Where the picture sits on the page, in CSS pixels. */
    fun place(left: Double, top: Double, width: Double, height: Double) {
        canvas.style.apply {
            this.left = "${left}px"
            this.top = "${top}px"
            this.width = "${width}px"
            this.height = "${height}px"
        }
    }

    fun reset() {
        awaitingKey = true
        awaitingTiles = true
        tileEpoch++
        closeDecoder()
    }

    fun close() {
        tileEpoch++
        closeDecoder()
        latest?.close()
        latest = null
        canvas.remove()
    }

    private fun openDecoder() = VideoDecoder(
        decoderCallbacks(
            output = { frame ->
                latest?.close()
                latest = frame
                if (!painting) {
                    painting = true
                    window.requestAnimationFrame { paint() }
                }
            },
            error = {
                decoder = null
                codec = ""
                lose()
            },
        ),
    )

    private fun closeDecoder() {
        decoder?.takeIf { it.state != "closed" }?.close()
        decoder = null
        codec = ""
    }

    private fun paint() {
        painting = false
        val frame = latest ?: return
        latest = null
        val width = frame.displayWidth
        val height = frame.displayHeight
        fitCanvas(width, height)
        paper.drawImage(frame, 0.0, 0.0)
        frame.close()
        onShown(width, height)
    }

    private fun fitCanvas(width: Int, height: Int) {
        if (canvas.width != width) canvas.width = width
        if (canvas.height != height) canvas.height = height
    }

    private fun lose() {
        awaitingKey = true
        onLost()
    }
}
