package com.remotecontroller.desktop.executor

import com.remotecontroller.shared.network.ScreenCodec
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.remotecontroller.desktop.server.DesktopSettings
import com.remotecontroller.desktop.server.ScreenEngine
import com.remotecontroller.desktop.server.SecureFrames
import com.remotecontroller.desktop.server.ServerLog
import com.remotecontroller.host.screencast.Screencast
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.concurrent.Executors
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

    /** What the viewers actually get, so a fallback is never a silent one. */
    val streamCodec: StateFlow<String>
        field = MutableStateFlow("")

    /** The GPUs H.264 can run on. There is a choice to offer from two up. */
    val encoderGpus: StateFlow<List<String>>
        field = MutableStateFlow(emptyList())

    private val watchers = LinkedHashSet<String>()
    private val videoViewers = LinkedHashSet<String>()
    private val tileViewers = LinkedHashSet<String>()
    private val offers = HashMap<String, List<String>>()
    private val sends = HashMap<String, Job>()
    private val watcherLock = Any()
    private val selectionLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val captureDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screen-capture").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private var job: Job? = null
    private val videoFrames = FrameMeter { fps -> framesPerSecond.value = fps }
    private val tileFrames = FrameMeter { fps -> framesPerSecond.value = fps }
    private val video = dependentStream(H264Source { settings.screenGpu.value }, videoFrames)
    private val tiles = dependentStream(TileSource { synchronized(selectionLock) { locateSelectedBounds() } }, tileFrames)

    /** Streaming with nothing to send: a still screen costs no frames. */
    val videoLive: StateFlow<Boolean> = combine(video.live, tiles.live) { videoLive, tilesLive -> videoLive || tilesLive }
        .stateIn(scope, SharingStarted.Eagerly, false)

    @Volatile
    private var captureBounds: Rectangle? = null

    private val robot = RobotCapture()
    private var sequence = 0

    init {
        runCatching { publishDisplays() }
        scope.launch {
            settings.screenEngine.drop(1).collect { rerouteAll() }
        }
        scope.launch {
            settings.screenGpu.drop(1).collect {
                video.forgetFailure()
                rerouteAll()
            }
        }
        scope.launch {
            runCatching { Screencast.encoderGpus() }.onSuccess { encoderGpus.value = it }
        }
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

    /** The best route the user allows that this viewer plays and this PC can send. Returns what was picked, and why. */
    fun watch(endpoint: String, codecs: List<String>): String = synchronized(watcherLock) {
        offers[endpoint] = codecs
        val route = routeFor(codecs)
        joinLocked(endpoint, route)
        publishViewersLocked()
        describeRoute(route, codecs)
    }

    fun unwatch(endpoint: String) {
        synchronized(watcherLock) {
            offers.remove(endpoint)
            leaveLocked(endpoint)
            publishViewersLocked()
        }
    }

    fun requestKeyframe(endpoint: String) {
        video.requestKeyframe(endpoint)
        tiles.requestKeyframe(endpoint)
    }

    fun pauseSharing() {
        sharingPaused.value = true
        video.pause()
        tiles.pause()
        synchronized(watcherLock) {
            job?.cancel()
        }
        framesPerSecond.value = 0
    }

    fun resumeSharing() {
        sharingPaused.value = false
        video.resume()
        tiles.resume()
        synchronized(watcherLock) {
            startLoopLocked()
        }
    }

    fun restartSharing() {
        sharingPaused.value = false
        captureError.value = null
        video.restart()
        tiles.restart()
        synchronized(watcherLock) {
            job?.cancel()
            job = null
            startLoopLocked()
        }
        rerouteAll()
    }

    fun stopSharing() {
        sharingPaused.value = false
        video.clear()
        tiles.clear()
        synchronized(watcherLock) {
            watchers.clear()
            videoViewers.clear()
            tileViewers.clear()
            offers.clear()
            sends.values.forEach { it.cancel() }
            sends.clear()
            publishViewersLocked()
            job?.cancel()
        }
        framesPerSecond.value = 0
    }

    private fun dependentStream(source: StreamSource, meter: FrameMeter) = VideoStream(
        source = source,
        settings = settings,
        scope = scope,
        displayIndex = ::selectedDisplayIndex,
        onFrame = meter::count,
        onStopped = { framesPerSecond.value = 0 },
        onFailure = { message -> captureError.value = message },
        onUnavailable = ::reroute,
    )

    private fun routeFor(codecs: List<String>): ScreenEngine =
        settings.screenEngine.value.fallbacks.first { engine -> canCarry(engine, codecs) }

    private fun canCarry(engine: ScreenEngine, codecs: List<String>): Boolean = when (engine) {
        ScreenEngine.H264 -> ScreenCodec.H264 in codecs && video.canStream
        ScreenEngine.TILES -> ScreenCodec.TILES in codecs && tiles.canStream
        ScreenEngine.JPEG -> true
    }

    private fun describeRoute(route: ScreenEngine, codecs: List<String>): String {
        val wanted = settings.screenEngine.value
        if (route == wanted) return route.label
        val reason = when (wanted) {
            ScreenEngine.H264 -> if (ScreenCodec.H264 in codecs) video.unavailableReason else "this client did not ask for H.264"
            ScreenEngine.TILES -> if (ScreenCodec.TILES in codecs) tiles.unavailableReason else "this client did not ask for tiles"
            ScreenEngine.JPEG -> ""
        }
        return "${route.label}: $reason"
    }

    private fun joinLocked(endpoint: String, route: ScreenEngine) {
        when (route) {
            ScreenEngine.H264 -> {
                videoViewers.add(endpoint)
                video.add(endpoint)
            }
            ScreenEngine.TILES -> {
                tileViewers.add(endpoint)
                tiles.add(endpoint)
            }
            ScreenEngine.JPEG -> {
                watchers.add(endpoint)
                startLoopLocked()
            }
        }
    }

    private fun leaveLocked(endpoint: String) {
        if (videoViewers.remove(endpoint)) video.remove(endpoint)
        if (tileViewers.remove(endpoint)) tiles.remove(endpoint)
        sends.remove(endpoint)?.cancel()
        if (watchers.remove(endpoint) && watchers.isEmpty()) job?.cancel()
    }

    private fun currentRouteLocked(endpoint: String): ScreenEngine = when (endpoint) {
        in videoViewers -> ScreenEngine.H264
        in tileViewers -> ScreenEngine.TILES
        else -> ScreenEngine.JPEG
    }

    /** A viewer takes each change of stream as it comes: the first frame of the new one is a full picture. */
    private fun reroute(endpoints: List<String>) {
        synchronized(watcherLock) {
            endpoints.forEach { endpoint ->
                val codecs = offers[endpoint] ?: return@forEach
                val route = routeFor(codecs)
                ServerLog.record("screen viewer moved to ${describeRoute(route, codecs)}")
                leaveLocked(endpoint)
                joinLocked(endpoint, route)
            }
            publishViewersLocked()
        }
    }

    private fun rerouteAll() {
        val moved = synchronized(watcherLock) {
            offers.filter { (endpoint, codecs) -> routeFor(codecs) != currentRouteLocked(endpoint) }.keys.toList()
        }
        if (moved.isNotEmpty()) reroute(moved)
    }

    private fun publishViewersLocked() {
        viewerCount.value = watchers.size + videoViewers.size + tileViewers.size
        streamCodec.value = listOf(
            ScreenEngine.H264 to videoViewers,
            ScreenEngine.TILES to tileViewers,
            ScreenEngine.JPEG to watchers,
        ).filter { (_, viewers) -> viewers.isNotEmpty() }.joinToString(" + ") { (engine, _) -> engine.label }
    }

    private fun selectedDisplayIndex(): Int =
        // A Mac's number is a full 32 bits: the library reads the same bits back as unsigned.
        selectedDisplayId.value.takeLastWhile(Char::isDigit).toLongOrNull()?.toInt() ?: 0

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
                    val delivered = snapshotWatchers().count { endpoint -> offerFrame(endpoint, packet) } > 0
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
        val scaled = robot.grab(bounds, LONG_EDGE)
        val jpeg = encodeJpeg(scaled)
        val current = sequence
        sequence = (sequence + 1) and Int.MAX_VALUE
        return ScreenFrame.pack(scaled.width, scaled.height, current, jpeg)
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray {
        val info = ImageInfo(image.width, image.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        return Image.makeRaster(info, robot.bgra(image), image.width * 4).use { picture ->
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

    /** Counts frames and reports the rate once a second. Runs on the thread that sends them. */
    private class FrameMeter(
        private val publish: (Int) -> Unit,
    ) {
        private var frames = 0
        private var windowStarted = System.nanoTime()

        fun count(delivered: Boolean) {
            if (delivered) frames++
            val now = System.nanoTime()
            if (now - windowStarted < 1_000_000_000L) return
            publish(frames)
            frames = 0
            windowStarted = now
        }
    }

    private companion object {
        const val LONG_EDGE = 1280
        const val JPEG_QUALITY = 60
        val DISPLAY_REFRESH = 2.seconds
        val SEND_POLL = 2.milliseconds
    }
}
