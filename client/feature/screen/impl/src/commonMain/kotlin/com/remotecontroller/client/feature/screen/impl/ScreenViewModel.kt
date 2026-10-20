package com.remotecontroller.client.feature.screen.impl

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remotecontroller.client.domain.model.ConnectionState
import com.remotecontroller.client.domain.model.PcConnection
import com.remotecontroller.client.domain.usecase.ObserveActiveConnectionUseCase
import com.remotecontroller.client.domain.usecase.ObserveConnectionStateUseCase
import com.remotecontroller.client.domain.usecase.ObserveScreenUseCase
import com.remotecontroller.client.domain.usecase.SendPointerCommandUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

data class ScreenUiState(
    val image: ImageBitmap? = null,
    val framesPerSecond: Int = 0,
    val message: String? = "Connecting to the screen…",
    val connectionState: ConnectionState = ConnectionState.Disconnected,
)

class ScreenViewModel(
    private val observeScreen: ObserveScreenUseCase,
    private val sendPointer: SendPointerCommandUseCase,
    observeActiveConnection: ObserveActiveConnectionUseCase,
    observeConnectionState: ObserveConnectionStateUseCase,
) : ViewModel() {

    private val connectionState = observeConnectionState()
    private val activeConnection = observeActiveConnection()

    val uiState: StateFlow<ScreenUiState>
        field = MutableStateFlow(ScreenUiState(connectionState = connectionState.value))

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

    override fun onCleared() {
        releasePointer()
        pointerEvents.close()
    }

    private suspend fun stream(connection: PcConnection) {
        var streak = 0
        while (currentCoroutineContext().isActive) {
            try {
                uiState.update { current ->
                    if (current.image == null) current.copy(message = "Connecting to the screen…") else current
                }
                observeScreen(connection).collect { picture ->
                    streak = 0
                    val image = withContext(Dispatchers.Default) { decodeJpeg(picture.jpeg) } ?: return@collect
                    val fps = noteFrame()
                    uiState.update {
                        it.copy(
                            image = image,
                            framesPerSecond = fps,
                            message = null,
                        )
                    }
                }
                streak++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                streak++
                uiState.update { current ->
                    if (current.image != null) current
                    else current.copy(message = error.message?.takeIf { it.isNotBlank() } ?: "The screen is unavailable")
                }
            }
            delay((400L * streak.coerceAtMost(5)).milliseconds)
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
}
