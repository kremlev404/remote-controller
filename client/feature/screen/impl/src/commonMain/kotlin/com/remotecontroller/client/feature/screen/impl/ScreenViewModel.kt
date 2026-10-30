package com.remotecontroller.client.feature.screen.impl

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remotecontroller.client.domain.model.ConnectionState
import com.remotecontroller.client.domain.model.PcConnection
import com.remotecontroller.client.domain.model.PointerSensitivity
import com.remotecontroller.client.domain.usecase.ObserveActiveConnectionUseCase
import com.remotecontroller.client.domain.usecase.ObserveConnectionStateUseCase
import com.remotecontroller.client.domain.usecase.ObservePointerDpiUseCase
import com.remotecontroller.client.domain.usecase.ObserveScreenTouchModeUseCase
import com.remotecontroller.client.domain.usecase.ObserveScreenUseCase
import com.remotecontroller.client.domain.usecase.SendKeyCommandUseCase
import com.remotecontroller.client.domain.usecase.SendPointerCommandUseCase
import com.remotecontroller.client.domain.usecase.SetScreenTouchModeUseCase
import com.remotecontroller.shared.network.ScreenFeed
import com.remotecontroller.shared.network.ScreenFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

data class VideoSize(
    val width: Int,
    val height: Int,
)

data class ScreenUiState(
    val image: ImageBitmap? = null,
    val video: VideoSize? = null,
    val framesPerSecond: Int = 0,
    val message: String? = "Connecting to the screen…",
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val touchMode: Boolean = false,
    val pointerScale: Float = 1f,
    val cursorX: Float = 0.5f,
    val cursorY: Float = 0.5f,
) {
    val hasPicture: Boolean
        get() = image != null || video != null
}

class ScreenViewModel(
    private val observeScreen: ObserveScreenUseCase,
    private val sendPointer: SendPointerCommandUseCase,
    private val sendKey: SendKeyCommandUseCase,
    private val setScreenTouchMode: SetScreenTouchModeUseCase,
    observeActiveConnection: ObserveActiveConnectionUseCase,
    observeConnectionState: ObserveConnectionStateUseCase,
    observePointerDpi: ObservePointerDpiUseCase,
    observeScreenTouchMode: ObserveScreenTouchModeUseCase,
) : ViewModel() {

    private val connectionState = observeConnectionState()
    private val activeConnection = observeActiveConnection()

    val uiState: StateFlow<ScreenUiState>
        field = MutableStateFlow(ScreenUiState(connectionState = connectionState.value))

    /** Frames bypass the state: drawing happens on the surface, without a recomposition. */
    private val videoChannel = Channel<ScreenFrame.Video>(capacity = VIDEO_BACKLOG, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val keyframeRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_LATEST,
    )
    private val latestPicture = MutableStateFlow<ScreenFrame.Picture?>(null)

    val videoFrames: ReceiveChannel<ScreenFrame.Video>
        get() = videoChannel

    private val pointerEvents = Channel<PointerEvent>(capacity = Channel.UNLIMITED)
    private val pointerJob = SupervisorJob()
    private val pointerScope = CoroutineScope(pointerJob + Dispatchers.Default)
    private var holding = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastButton = "LEFT"
    private val clock = TimeSource.Monotonic
    private var windowStart = clock.markNow()
    private var framesInWindow = 0

    init {
        pointerScope.launch {
            try {
                drainPointer()
            } finally {
                pointerJob.cancel()
            }
        }
        viewModelScope.launch {
            connectionState.collect { state ->
                uiState.update { it.copy(connectionState = state) }
            }
        }
        viewModelScope.launch {
            observeScreenTouchMode().collect { enabled ->
                uiState.update { it.copy(touchMode = enabled) }
            }
        }
        viewModelScope.launch {
            observePointerDpi().collect { dpi ->
                uiState.update { it.copy(pointerScale = PointerSensitivity.scale(dpi)) }
            }
        }
        viewModelScope.launch {
            // A StateFlow keeps only the newest, so a slow phone skips JPEG frames, never queues them.
            latestPicture.filterNotNull().collect { picture ->
                val image = withContext(Dispatchers.Default) { decodeJpeg(picture.jpeg) } ?: return@collect
                val fps = noteFrame()
                uiState.update {
                    it.copy(image = image, video = null, framesPerSecond = fps, message = null)
                }
            }
        }
        viewModelScope.launch {
            activeConnection.collectLatest { connection ->
                if (connection == null) {
                    uiState.update { current ->
                        current.copy(message = current.message ?: "Not connected to the laptop")
                    }
                    return@collectLatest
                }
                stream(connection)
            }
        }
    }

    fun pointerDown(x: Float, y: Float, button: String) {
        holding = true
        remember(x, y, button)
        pointerEvents.trySend(PointerEvent.Down(x, y, button))
    }

    fun pointerMove(x: Float, y: Float, button: String) {
        remember(x, y, button)
        pointerEvents.trySend(PointerEvent.Move(x, y, button))
    }

    fun pointerUp(x: Float, y: Float, button: String) {
        holding = false
        remember(x, y, button)
        pointerEvents.trySend(PointerEvent.Up(x, y, button))
    }

    fun pointerScroll(x: Float, y: Float, deltaY: Int) {
        if (deltaY == 0) return
        remember(x, y, lastButton)
        pointerEvents.trySend(PointerEvent.Scroll(x, y, deltaY))
    }

    fun releasePointer() {
        if (!holding) return
        holding = false
        pointerEvents.trySend(PointerEvent.Up(lastX, lastY, lastButton))
    }

    fun setTouchMode(enabled: Boolean) {
        releasePointer()
        viewModelScope.launch { setScreenTouchMode(enabled) }
    }

    /** The trackpad keeps its own cursor: the picture from the PC does not show one. */
    fun trackpadMove(dx: Float, dy: Float) {
        val state = uiState.value
        val x = (state.cursorX + dx).coerceIn(0f, 1f)
        val y = (state.cursorY + dy).coerceIn(0f, 1f)
        uiState.update { it.copy(cursorX = x, cursorY = y) }
        pointerMove(x, y, LEFT_BUTTON)
    }

    fun trackpadClick(button: String) {
        val state = uiState.value
        pointerDown(state.cursorX, state.cursorY, button)
        pointerUp(state.cursorX, state.cursorY, button)
    }

    fun trackpadPress() {
        val state = uiState.value
        pointerDown(state.cursorX, state.cursorY, LEFT_BUTTON)
    }

    fun trackpadRelease() {
        val state = uiState.value
        pointerUp(state.cursorX, state.cursorY, LEFT_BUTTON)
    }

    fun trackpadScroll(deltaY: Int) {
        val state = uiState.value
        pointerScroll(state.cursorX, state.cursorY, deltaY)
    }

    fun type(text: String, backspaces: Int) {
        pointerEvents.trySend(PointerEvent.Type(text, backspaces))
    }

    fun pressEnter() {
        pointerEvents.trySend(PointerEvent.Key("ENTER"))
    }

    fun videoLost() {
        keyframeRequests.tryEmit(Unit)
    }

    fun videoRate(framesPerSecond: Int) {
        uiState.update { it.copy(framesPerSecond = framesPerSecond) }
    }

    override fun onCleared() {
        releasePointer()
        pointerEvents.close()
        videoChannel.close()
    }

    private suspend fun stream(connection: PcConnection) {
        var streak = 0
        while (currentCoroutineContext().isActive) {
            try {
                uiState.update { current ->
                    if (current.hasPicture) current else current.copy(message = "Connecting to the screen…")
                }
                observeScreen(connection, videoCodecs, keyframeRequests).collect { feed ->
                    streak = 0
                    when (feed) {
                        ScreenFeed.HostPaused -> uiState.update {
                            it.copy(
                                image = null,
                                video = null,
                                framesPerSecond = 0,
                                message = "Screen sharing is paused on the PC",
                            )
                        }
                        ScreenFeed.HostLive -> uiState.update { it.copy(message = "Connecting to the screen…") }
                        is ScreenFeed.Picture -> latestPicture.value = feed.picture
                        is ScreenFeed.Video -> showVideo(feed.video)
                    }
                }
                streak++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                streak++
                uiState.update { current ->
                    if (current.hasPicture) current
                    else current.copy(message = error.message?.takeIf { it.isNotBlank() } ?: "The screen is unavailable")
                }
            }
            delay((400L * streak.coerceAtMost(5)).milliseconds)
        }
    }

    private fun showVideo(video: ScreenFrame.Video) {
        videoChannel.trySend(video)
        val size = VideoSize(video.width, video.height)
        if (uiState.value.video != size) {
            uiState.update { it.copy(image = null, video = size, message = null) }
        }
    }

    private fun noteFrame(): Int {
        framesInWindow++
        if (windowStart.elapsedNow() < 1.seconds) return uiState.value.framesPerSecond
        val fps = framesInWindow
        framesInWindow = 0
        windowStart = clock.markNow()
        return fps
    }

    private fun remember(x: Float, y: Float, button: String) {
        lastX = x
        lastY = y
        lastButton = button
    }

    private suspend fun drainPointer() {
        while (currentCoroutineContext().isActive) {
            val first = pointerEvents.receiveCatching().getOrNull() ?: break
            val batch = ArrayDeque<PointerEvent>()
            batch.add(first)
            while (true) {
                val next = pointerEvents.tryReceive().getOrNull() ?: break
                if (next is PointerEvent.Move && batch.lastOrNull() is PointerEvent.Move) {
                    batch.removeLast()
                }
                batch.add(next)
            }
            for (event in batch) deliver(event)
        }
    }

    private suspend fun deliver(event: PointerEvent) {
        try {
            when (event) {
                is PointerEvent.Down -> sendPointer.down(event.x, event.y, event.button)
                is PointerEvent.Move -> sendPointer.move(event.x, event.y, event.button)
                is PointerEvent.Up -> sendPointer.up(event.x, event.y, event.button)
                is PointerEvent.Scroll -> sendPointer.scroll(event.x, event.y, event.deltaY)
                is PointerEvent.Type -> sendKey.type(event.text, event.backspaces)
                is PointerEvent.Key -> sendKey.pressKey(event.key)
            }
        } catch (error: CancellationException) {
            if (!currentCoroutineContext().isActive) throw error
        }
    }
}

private sealed interface PointerEvent {
    data class Down(val x: Float, val y: Float, val button: String) : PointerEvent
    data class Move(val x: Float, val y: Float, val button: String) : PointerEvent
    data class Up(val x: Float, val y: Float, val button: String) : PointerEvent
    data class Scroll(val x: Float, val y: Float, val deltaY: Int) : PointerEvent
    data class Type(val text: String, val backspaces: Int) : PointerEvent
    data class Key(val key: String) : PointerEvent
}

private const val LEFT_BUTTON = "LEFT"
private const val VIDEO_BACKLOG = 16
