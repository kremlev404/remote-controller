package com.remotecontroller.desktop.executor

import com.remotecontroller.shared.network.ScreenFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.remotecontroller.desktop.server.DesktopSettings
import com.remotecontroller.desktop.server.SecureFrames
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

data class SharedDisplay(
    val id: String,
    val label: String,
    val primary: Boolean,
)

class ScreenCapturer(
    private val settings: DesktopSettings,
) {
    val displays: StateFlow<List<SharedDisplay>>
        field = MutableStateFlow(emptyList())

    val selectedDisplayId: StateFlow<String>
        field = MutableStateFlow("")

    val framesPerSecond: StateFlow<Int>
        field = MutableStateFlow(0)

    val viewerCount: StateFlow<Int>
        field = MutableStateFlow(0)

    val captureError: StateFlow<String?>
        field = MutableStateFlow(null)

    val sharingPaused: StateFlow<Boolean>
        field = MutableStateFlow(false)

    private val watchers = LinkedHashSet<String>()
    private val sends = HashMap<String, Job>()
    private val watcherLock = Any()
    private val selectionLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val captureDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screen-capture").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private var job: Job? = null

    @Volatile
    private var captureBounds: Rectangle? = null

    private var robot: Robot? = null
    private var scaledImage: BufferedImage? = null
    private var sequence = 0
    private var jpegPixels = ByteArray(0)

    init {
        runCatching { publishDisplays() }
        scope.launch {
            while (isActive) {
                delay(DISPLAY_REFRESH)
                runCatching { publishDisplays() }
            }
        }
    }

    fun selectDisplay(id: String) {
        synchronized(selectionLock) {
            if (displays.value.none { it.id == id }) return
            selectedDisplayId.value = id
            captureBounds = locateSelectedBounds()
        }
    }

    fun toScreen(x: Float, y: Float): Point? {
        if (!x.isFinite() || !y.isFinite()) return null
        val bounds = captureBounds ?: synchronized(selectionLock) {
            captureBounds ?: locateSelectedBounds()?.also { captureBounds = it }
        } ?: return null
        if (bounds.width <= 0 || bounds.height <= 0) return null
        val px = bounds.x + (x.coerceIn(0f, 1f) * (bounds.width - 1)).roundToInt()
        val py = bounds.y + (y.coerceIn(0f, 1f) * (bounds.height - 1)).roundToInt()
        return Point(px, py)
    }

    fun watch(endpoint: String) {
        synchronized(watcherLock) {
            watchers.add(endpoint)
            viewerCount.value = watchers.size
            startLoopLocked()
        }
    }

    fun unwatch(endpoint: String) {
        synchronized(watcherLock) {
            sends.remove(endpoint)?.cancel()
            if (!watchers.remove(endpoint)) return
            viewerCount.value = watchers.size
            if (watchers.isEmpty()) job?.cancel()
        }
    }

    fun pauseSharing() {
        sharingPaused.value = true
        synchronized(watcherLock) {
            job?.cancel()
        }
        framesPerSecond.value = 0
    }

    fun resumeSharing() {
        sharingPaused.value = false
        synchronized(watcherLock) {
            startLoopLocked()
        }
    }

    fun restartSharing() {
        sharingPaused.value = false
        captureError.value = null
        synchronized(watcherLock) {
            job?.cancel()
            job = null
            startLoopLocked()
        }
    }

    fun stopSharing() {
        sharingPaused.value = false
        synchronized(watcherLock) {
            watchers.clear()
            sends.values.forEach { it.cancel() }
            sends.clear()
            viewerCount.value = 0
            job?.cancel()
        }
        framesPerSecond.value = 0
    }

    private fun startLoopLocked() {
        if (sharingPaused.value || watchers.isEmpty() || job?.isActive == true) return
        job = scope.launch(captureDispatcher) { runLoop() }
    }

    private suspend fun runLoop() {
        var framesInWindow = 0
        var windowStarted = System.nanoTime()
        var lastError: String? = null
        try {
            while (currentCoroutineContext().isActive) {
                if (snapshotWatchers().isEmpty()) break
                val interval = settings.screenMode.value.frameInterval
                if (allBusy()) {
                    delay(SEND_POLL)
                    continue
                }
                val started = System.nanoTime()
                val packet = runCatching { capturePacket() }
                    .onFailure { error ->
                        val message = error.message ?: "Screen capture failed"
                        if (message != lastError) {
                            println("[ScreenCapturer] $message")
                            lastError = message
                        }
                        captureError.value = message
                    }
                    .getOrNull()
                if (packet != null) {
                    lastError = null
                    captureError.value = null
                    val delivered = snapshotWatchers().any { endpoint -> offerFrame(endpoint, packet) }
                    if (delivered) {
                        framesInWindow++
                        val now = System.nanoTime()
                        if (now - windowStarted >= 1_000_000_000L) {
                            framesPerSecond.value = framesInWindow
                            framesInWindow = 0
                            windowStarted = now
                        }
                    }
                }
                val spent = (System.nanoTime() - started).nanoseconds
                val rest = if (packet == null) interval else interval - spent
                if (rest.isPositive()) delay(rest)
            }
        } finally {
            framesPerSecond.value = 0
        }
    }

    private fun capturePacket(): ByteArray? {
        val bounds = synchronized(selectionLock) {
            locateSelectedBounds()?.also { captureBounds = it }
        } ?: return null
        if (bounds.width <= 0 || bounds.height <= 0) return null
        val source = robot().createScreenCapture(bounds)
        val scaled = scale(source)
        val jpeg = encodeJpeg(scaled)
        val current = sequence
        sequence = (sequence + 1) and Int.MAX_VALUE
        return ScreenFrame.pack(scaled.width, scaled.height, current, jpeg)
    }

    private fun robot(): Robot = robot ?: Robot().apply {
        autoDelay = 0
        isAutoWaitForIdle = false
    }.also { robot = it }

    private fun scale(source: BufferedImage): BufferedImage {
        val (width, height) = scaledSize(source.width, source.height)
        var dest = scaledImage
        if (dest == null || dest.width != width || dest.height != height) {
            dest = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            scaledImage = dest
        }
        val pixels = (dest.raster.dataBuffer as DataBufferInt).data
        val srcW = source.width
        val srcH = source.height
        val direct = source.raster.dataBuffer as? DataBufferInt
        val src = if (
            direct != null &&
            direct.offset == 0 &&
            direct.data.size == srcW * srcH &&
            (source.type == BufferedImage.TYPE_INT_RGB || source.type == BufferedImage.TYPE_INT_ARGB)
        ) {
            direct.data
        } else {
            null
        }
        for (y in 0 until height) {
            val sy = y * srcH / height
            val destRow = y * width
            val srcRow = sy * srcW
            for (x in 0 until width) {
                val sx = x * srcW / width
                val color = if (src != null) src[srcRow + sx] else source.getRGB(sx, sy)
                pixels[destRow + x] = color and 0x00FFFFFF
            }
        }
        return dest
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray {
        val pixels = (image.raster.dataBuffer as DataBufferInt).data
        if (jpegPixels.size != pixels.size * 4) jpegPixels = ByteArray(pixels.size * 4)
        ByteBuffer.wrap(jpegPixels).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(pixels)
        val info = ImageInfo(image.width, image.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        return Image.makeRaster(info, jpegPixels, image.width * 4).use { picture ->
            picture.encodeToData(EncodedImageFormat.JPEG, JPEG_QUALITY)?.use { it.bytes }
                ?: error("JPEG encoding failed")
        }
    }

    private fun publishDisplays() {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val primary = environment.defaultScreenDevice
        var others = 1
        val options = environment.screenDevices.map { device ->
            val bounds = device.defaultConfiguration.bounds
            val isPrimary = device.iDstring == primary.iDstring
            val name = if (isPrimary) "Primary" else "Display ${++others}"
            SharedDisplay(
                id = device.iDstring,
                label = "$name · ${bounds.width}×${bounds.height}",
                primary = isPrimary,
            )
        }
        synchronized(selectionLock) {
            displays.value = options
            val selected = selectedDisplayId.value
            if (options.none { it.id == selected }) {
                val fallback = options.firstOrNull { it.primary } ?: options.firstOrNull()
                selectedDisplayId.value = fallback?.id.orEmpty()
                captureBounds = locateSelectedBounds()
            }
        }
    }

    private fun locateSelectedBounds(): Rectangle? {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val selected = selectedDisplayId.value
        val device = environment.screenDevices.firstOrNull { it.iDstring == selected }
            ?: environment.defaultScreenDevice
        return device.defaultConfiguration.bounds
    }

    private fun snapshotWatchers(): List<String> = synchronized(watcherLock) { watchers.toList() }

    private fun allBusy(): Boolean = synchronized(watcherLock) {
        watchers.isNotEmpty() && watchers.all { endpoint -> sends[endpoint]?.isActive == true }
    }

    private fun offerFrame(endpoint: String, packet: ByteArray): Boolean = synchronized(watcherLock) {
        if (!watchers.contains(endpoint)) return false
        val current = sends[endpoint]
        if (current != null && current.isActive) return false
        sends[endpoint] = scope.launch {
            try {
                SecureFrames.sendBinary(endpoint, packet)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                unwatch(endpoint)
            }
        }
        true
    }

    private companion object {
        const val LONG_EDGE = 1280
        const val JPEG_QUALITY = 60
        val DISPLAY_REFRESH = 2.seconds
        val SEND_POLL = 2.milliseconds

        fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
            val longEdge = max(width, height)
            if (longEdge <= LONG_EDGE) return width.coerceAtLeast(1) to height.coerceAtLeast(1)
            val scale = LONG_EDGE.toDouble() / longEdge
            val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
            val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
            return scaledWidth to scaledHeight
        }
    }
}
