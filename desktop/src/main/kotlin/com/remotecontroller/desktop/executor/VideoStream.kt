package com.remotecontroller.desktop.executor

import com.remotecontroller.desktop.server.DesktopSettings
import com.remotecontroller.desktop.server.ScreenMode
import com.remotecontroller.desktop.server.SecureFrames
import com.remotecontroller.host.screencast.Screencast
import com.remotecontroller.host.screencast.ScreencastResult
import com.remotecontroller.shared.network.ScreenFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The H.264 stream: screencast.dll captures and encodes on the GPU, this class hands the bytes
 * to the viewers and keeps the load matched to what they can take.
 *
 * Frames depend on the ones before them, so a sent stream cannot skip one. The brake sits before
 * the encoder instead: while a viewer's socket is busy, nothing is captured. A viewer that falls
 * behind others waits for a keyframe, and a slow link lowers the bitrate.
 */
internal class VideoStream(
    private val settings: DesktopSettings,
    private val scope: CoroutineScope,
    private val displayIndex: () -> Int,
    /** Called on every pass of the loop, so the rate falls to zero on a still screen. */
    private val onFrame: (delivered: Boolean) -> Unit,
    private val onStopped: () -> Unit,
    private val onFailure: (String?) -> Unit,
    /** The GPU route never worked. These viewers go back to JPEG. */
    private val onUnavailable: (List<String>) -> Unit,
) {
    val live: StateFlow<Boolean>
        field = MutableStateFlow(false)

    private val lock = Any()
    private val viewers = LinkedHashMap<String, Viewer>()
    private val clock = TimeSource.Monotonic

    // The native session belongs to one thread. Every call into it happens on this one.
    private val encodeDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screen-encode").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private var job: Job? = null
    private var sequence = 0

    @Volatile
    private var paused = false

    @Volatile
    private var keyframeWanted = false

    @Volatile
    private var broken = false

    val canStream: Boolean
        get() = Screencast.isAvailable && !broken

    val unavailableReason: String
        get() = Screencast.unavailableReason ?: "the screen encoder failed on this PC"

    fun add(endpoint: String) {
        synchronized(lock) {
            viewers[endpoint] = Viewer(endpoint)
            keyframeWanted = true
            startLocked()
        }
    }

    fun remove(endpoint: String): Boolean = synchronized(lock) {
        val viewer = viewers.remove(endpoint) ?: return false
        viewer.send?.cancel()
        if (viewers.isEmpty()) stopLocked()
        true
    }

    /** A viewer's decoder lost the picture. A peer cannot use this to make the GPU work flat out. */
    fun requestKeyframe(endpoint: String) {
        synchronized(lock) {
            val viewer = viewers[endpoint] ?: return
            if (viewer.lastRequest?.let { it.elapsedNow() < KEYFRAME_REQUEST_GAP } == true) return
            viewer.lastRequest = clock.markNow()
            viewer.awaitingKeyframe = true
            keyframeWanted = true
        }
    }

    fun clear() {
        synchronized(lock) {
            viewers.values.forEach { it.send?.cancel() }
            viewers.clear()
            stopLocked()
        }
    }

    fun pause() {
        paused = true
        synchronized(lock) { stopLocked() }
    }

    fun resume() {
        paused = false
        broken = false
        synchronized(lock) {
            keyframeWanted = true
            startLocked()
        }
    }

    fun restart() {
        synchronized(lock) {
            stopLocked()
            job = null
        }
        resume()
    }

    private fun startLocked() {
        if (paused || broken || viewers.isEmpty() || job?.isActive == true) return
        job = scope.launch(encodeDispatcher) { run() }
    }

    private fun stopLocked() {
        job?.cancel()
    }

    private suspend fun run() {
        try {
            while (currentCoroutineContext().isActive && hasViewers()) {
                val wanted = currentConfig()
                val session = Screencast.open(wanted.display, wanted.mode.longEdge, wanted.mode.framesPerSecond, wanted.mode.bitrate)
                if (session == null) {
                    giveUp("The screen encoder could not start")
                    return
                }
                val keepGoing = session.use { stream(it, wanted) }
                if (!keepGoing) return
            }
        } finally {
            live.value = false
            onFailure(null)
            onStopped()
        }
    }

    private fun currentConfig() = StreamConfig(settings.screenMode.value, displayIndex())

    /** Runs until the mode or the display changes. False when the GPU route cannot work here. */
    private suspend fun stream(session: Screencast, config: StreamConfig): Boolean {
        val governor = BitrateGovernor(config.mode.bitrate)
        val interval = config.mode.frameInterval
        val opened = clock.markNow()
        var delivered = false
        var outageStart: TimeMark? = null
        keyframeWanted = true
        while (currentCoroutineContext().isActive && hasViewers()) {
            if (config != currentConfig()) return true

            val waitStart = clock.markNow()
            while (allBusy()) delay(SEND_POLL)
            governor.note(congested = waitStart.elapsedNow() > interval)

            val keyframe = keyframeWanted
            keyframeWanted = false
            when (val result = session.next(keyframe, governor.bitrate)) {
                ScreencastResult.Unchanged -> {
                    if (keyframe) keyframeWanted = true
                    onFrame(false)
                }
                ScreencastResult.Unavailable -> {
                    keyframeWanted = true
                    live.value = false
                    val since = outageStart ?: clock.markNow().also { outageStart = it }
                    if (!delivered && opened.elapsedNow() > GIVE_UP_AFTER) {
                        giveUp(session.status())
                        return false
                    }
                    if (since.elapsedNow() > OUTAGE_REPORT_AFTER) onFailure("The desktop is not available right now")
                }
                is ScreencastResult.Encoded -> {
                    if (outageStart != null) {
                        outageStart = null
                        onFailure(null)
                    }
                    live.value = true
                    delivered = true
                    deliver(result)
                }
            }
        }
        return true
    }

    private fun giveUp(reason: String) {
        println("[VideoStream] falling back to JPEG: $reason")
        broken = true
        val endpoints = synchronized(lock) {
            viewers.keys.toList().also { viewers.clear() }
        }
        if (endpoints.isNotEmpty()) onUnavailable(endpoints)
    }

    private fun deliver(result: ScreencastResult.Encoded) {
        val size = result.annexB.remaining()
        val packet = ScreenFrame.videoPacket(result.width, result.height, sequence, result.keyframe, size)
        result.annexB.get(packet, ScreenFrame.HEADER_SIZE, size)
        sequence = (sequence + 1) and Int.MAX_VALUE
        var sent = false
        synchronized(lock) {
            for (viewer in viewers.values) {
                if (viewer.send?.isActive == true) {
                    viewer.awaitingKeyframe = true
                    askForResync(viewer)
                    continue
                }
                if (viewer.awaitingKeyframe) {
                    if (!result.keyframe) {
                        askForResync(viewer)
                        continue
                    }
                    viewer.awaitingKeyframe = false
                }
                sent = true
                viewer.send = scope.launch {
                    try {
                        SecureFrames.sendBinary(viewer.endpoint, packet)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        remove(viewer.endpoint)
                    }
                }
            }
        }
        onFrame(sent)
    }

    /** Keyframes are large: a viewer that keeps falling behind must not flood the others with them. */
    private fun askForResync(viewer: Viewer) {
        if (viewer.lastResync?.let { it.elapsedNow() < RESYNC_GAP } == true) return
        viewer.lastResync = clock.markNow()
        keyframeWanted = true
    }

    private fun hasViewers(): Boolean = synchronized(lock) { viewers.isNotEmpty() }

    private fun allBusy(): Boolean = synchronized(lock) {
        viewers.isNotEmpty() && viewers.values.all { it.send?.isActive == true }
    }

    private data class StreamConfig(
        val mode: ScreenMode,
        val display: Int,
    )

    private class Viewer(
        val endpoint: String,
    ) {
        var send: Job? = null
        var awaitingKeyframe = true
        var lastRequest: TimeMark? = null
        var lastResync: TimeMark? = null
    }

    private companion object {
        val SEND_POLL = 2.milliseconds
        val KEYFRAME_REQUEST_GAP = 500.milliseconds
        val RESYNC_GAP = 2.seconds
        val GIVE_UP_AFTER = 5.seconds
        val OUTAGE_REPORT_AFTER = 2.seconds
    }
}

/**
 * Lowers the bitrate fast when the link cannot keep up and raises it back slowly, the way
 * congestion control does. Runs on the encoding thread.
 */
internal class BitrateGovernor(
    private val target: Int,
) {
    var bitrate: Int = target
        private set

    private val clock = TimeSource.Monotonic
    private var windowStart = clock.markNow()
    private var congestedFrames = 0
    private var cleanWindows = 0

    fun note(congested: Boolean) {
        if (congested) congestedFrames++
        if (windowStart.elapsedNow() < WINDOW) return
        if (congestedFrames >= CONGESTED_FRAMES) {
            bitrate = (bitrate * DECREASE_PERCENT / 100).coerceAtLeast(MIN_BITRATE)
            cleanWindows = 0
        } else if (++cleanWindows >= CLEAN_WINDOWS) {
            bitrate = (bitrate * INCREASE_PERCENT / 100).coerceAtMost(target)
            cleanWindows = 0
        }
        congestedFrames = 0
        windowStart = clock.markNow()
    }

    private companion object {
        const val MIN_BITRATE = 1_000_000
        const val CONGESTED_FRAMES = 3
        const val CLEAN_WINDOWS = 5
        const val DECREASE_PERCENT = 75
        const val INCREASE_PERCENT = 110
        val WINDOW = 1.seconds
    }
}
