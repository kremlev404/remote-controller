package com.remotecontroller.web.ui

import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remotecontroller.client.domain.model.FrameRate
import com.remotecontroller.client.domain.repository.ClipSnapshot
import com.remotecontroller.client.domain.usecase.ObserveClipboardUseCase
import com.remotecontroller.client.domain.usecase.ObserveScreenUseCase
import com.remotecontroller.client.domain.usecase.SendClipboardUseCase
import com.remotecontroller.client.domain.usecase.SendRemoteInputUseCase
import com.remotecontroller.shared.network.AccessCodeRejected
import com.remotecontroller.shared.network.AccessCodeRequired
import com.remotecontroller.shared.network.GrantExpired
import com.remotecontroller.shared.network.LinkRejected
import com.remotecontroller.shared.network.RelayProtocol
import com.remotecontroller.shared.network.ScreenCodec
import com.remotecontroller.shared.network.ScreenFeed
import com.remotecontroller.shared.network.ScreenFrame
import com.remotecontroller.web.browser.KeyboardCapture
import com.remotecontroller.web.browser.SocketClosed
import com.remotecontroller.web.browser.fullscreenChanges
import com.remotecontroller.web.browser.pastedImages
import com.remotecontroller.web.browser.pointerLetGo
import com.remotecontroller.web.browser.toggleFullscreen
import com.remotecontroller.web.data.PageTarget
import com.remotecontroller.web.video.VideoPlayer
import com.remotecontroller.web.video.canPlayVideo
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val CLIP_RETRY = 1_500.milliseconds
private val OFFLINE_RETRY = 5.seconds
private val STREAM_RETRY = 1.seconds

/** One try at the PC: a new code, or asking again, starts the next. */
private data class Attempt(
    val code: String,
    val number: Int = 0,
)

/** The page's state. Connections live as long as the coroutines that collect them. */
class PageViewModel(
    private val target: PageTarget,
    private val observeClipboard: ObserveClipboardUseCase,
    private val sendClipboard: SendClipboardUseCase,
    private val observeScreen: ObserveScreenUseCase,
    private val input: SendRemoteInputUseCase,
    private val approvalCode: StateFlow<String?>,
) : ViewModel() {
    val uiState: StateFlow<PageUiState>
        field = MutableStateFlow(PageUiState())

    private val attempts = MutableStateFlow(Attempt(target.code))
    private val keyframeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_LATEST)
    private val frameRate = FrameRate()
    private val keyboard = KeyboardCapture(
        onKey = input::key,
        onReleased = { uiState.update { it.copy(keyboardCaptured = false) } },
    )
    private val openPlayer = lazy {
        VideoPlayer(
            scope = viewModelScope,
            onShown = { width, height ->
                updateScreen { it.copy(picture = ScreenPicture.Moving(width, height)) }
                noteFrame()
            },
            onLost = { keyframeRequests.tryEmit(Unit) },
        )
    }
    private val player by openPlayer

    init {
        viewModelScope.launch { input.deliver() }
        viewModelScope.launch { pointerLetGo().collect { input.release() } }
        viewModelScope.launch { pastedImages().collect { sendClipboard.send("", it.encoded, it.type) } }
        viewModelScope.launch {
            approvalCode.filterNotNull().collect { code -> uiState.update { it.copy(link = LinkState.Waiting(code)) } }
        }
        viewModelScope.launch {
            fullscreenChanges().collect { active ->
                uiState.update { it.copy(fullscreen = active) }
                if (active) captureKeyboard() else releaseKeyboard()
            }
        }
        viewModelScope.launch {
            attempts.collectLatest { attempt ->
                coroutineScope {
                    launch { keepClipboard(attempt.code) }
                    launch { keepScreen(attempt.code) }
                }
            }
        }
    }

    fun submitCode(code: String) = attempts.update { Attempt(code, it.number + 1) }

    fun retry() {
        uiState.update { it.copy(link = LinkState.Connecting) }
        attempts.update { it.copy(number = it.number + 1) }
    }

    fun send(text: String) {
        viewModelScope.launch {
            val sent = sendClipboard.send(text)
            updateClip { it.copy(banner = if (sent) null else "That text is too long to send.") }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { sendClipboard.clear() }
    }

    fun captureKeyboard() {
        val state = uiState.value
        if (!state.clip.connected || state.screen.picture == null || !state.screen.watching) return
        uiState.update { it.copy(keyboardCaptured = true) }
        keyboard.engage()
    }

    fun releaseKeyboard() = keyboard.release()

    fun switchFullscreen() = toggleFullscreen()

    fun stopStream() {
        releaseKeyboard()
        updateScreen { it.copy(watching = false, hostPaused = false, framesPerSecond = 0, message = "Screen sharing is stopped.") }
    }

    fun resumeStream() = updateScreen { it.copy(watching = true) }

    /** Where the picture sits on the page, in CSS pixels. */
    fun placeVideo(left: Double, top: Double, width: Double, height: Double) = player.place(left, top, width, height)

    fun pointerDown(x: Float, y: Float, button: String) = input.pointerDown(x, y, button)

    fun pointerMove(x: Float, y: Float, button: String) = input.pointerMove(x, y, button)

    fun pointerUp(x: Float, y: Float, button: String) = input.pointerUp(x, y, button)

    fun releasePointer() = input.release()

    fun scroll(x: Float, y: Float, deltaY: Int) = input.scroll(x, y, deltaY)

    fun type(text: String, backspaces: Int) = input.type(text, backspaces)

    fun pressEnter() = input.pressEnter()

    override fun onCleared() {
        releaseKeyboard()
        input.close()
        if (openPlayer.isInitialized()) player.close()
    }

    private suspend fun keepClipboard(code: String) {
        observeClipboard(target.connection(code))
            .retryWhen { cause, _ -> reconnectClipboard(cause) }
            .catch { /* The PC refused: the state says so, and the page waits for a new attempt. */ }
            .collect { snapshot ->
                uiState.update { it.copy(clip = snapshot, link = if (snapshot.connected) LinkState.Live else it.link) }
            }
    }

    private suspend fun reconnectClipboard(cause: Throwable): Boolean {
        releaseKeyboard()
        val offline = cause is SocketClosed && cause.code == RelayProtocol.CLOSE_OFFLINE.toInt()
        updateClip { it.copy(connected = false, banner = if (offline) "This PC is not online right now." else it.banner) }
        val refused = (cause as? LinkRejected)?.let(::refusal)
        if (refused != null) {
            uiState.update { it.copy(link = refused) }
            return false
        }
        delay(if (offline) OFFLINE_RETRY else CLIP_RETRY)
        return true
    }

    /** What to show the user, or null when the next socket can simply try again without the grant. */
    private fun refusal(error: LinkRejected): LinkState? = when (error) {
        is AccessCodeRequired -> LinkState.NeedsCode(null)
        is AccessCodeRejected -> LinkState.NeedsCode(error.message)
        is GrantExpired -> null
        else -> LinkState.Stopped(error.message.orEmpty())
    }

    /** The stream waits for the clipboard link, so a PC that asks its owner asks once. */
    private suspend fun keepScreen(code: String) {
        uiState.map { it.clip.connected && it.screen.watching }
            .distinctUntilChanged()
            .collectLatest { wanted -> if (wanted) watchScreen(code) }
    }

    private suspend fun watchScreen(code: String) {
        val codecs = if (canPlayVideo()) listOf(ScreenCodec.H264, ScreenCodec.TILES) else listOf(ScreenCodec.TILES)
        updateScreen { it.copy(hostPaused = false, message = ScreenState.CONNECTING) }
        try {
            observeScreen(target.connection(code), codecs, keyframeRequests)
                .retryWhen { _, _ ->
                    resetPlayer()
                    updateScreen { it.copy(framesPerSecond = 0, message = "Reconnecting…") }
                    delay(STREAM_RETRY)
                    true
                }
                .collect(::show)
        } finally {
            resetPlayer()
            updateScreen { it.copy(framesPerSecond = 0) }
        }
    }

    private fun show(feed: ScreenFeed) {
        when (feed) {
            is ScreenFeed.Video -> player.decode(feed.video.data, feed.video.keyframe)
            is ScreenFeed.Tiles -> player.paintTiles(feed.tiles)
            is ScreenFeed.Picture -> showStill(feed.picture)
            ScreenFeed.HostPaused -> updateScreen {
                it.copy(hostPaused = true, framesPerSecond = 0, message = "Paused on the computer")
            }
            ScreenFeed.HostLive -> updateScreen { screen ->
                screen.copy(hostPaused = false, message = if (screen.picture == null) ScreenState.CONNECTING else screen.message)
            }
        }
    }

    private fun showStill(still: ScreenFrame.Picture) {
        val bitmap = runCatching { Image.makeFromEncoded(still.jpeg).toComposeImageBitmap() }.getOrNull() ?: return
        updateScreen { it.copy(picture = ScreenPicture.Still(bitmap)) }
        noteFrame()
    }

    private fun noteFrame() {
        val rate = frameRate.count()
        updateScreen { it.copy(hostPaused = false, framesPerSecond = rate ?: it.framesPerSecond) }
    }

    private fun resetPlayer() {
        if (openPlayer.isInitialized()) player.reset()
    }

    private fun updateClip(change: (ClipSnapshot) -> ClipSnapshot) = uiState.update { it.copy(clip = change(it.clip)) }

    private fun updateScreen(change: (ScreenState) -> ScreenState) = uiState.update { it.copy(screen = change(it.screen)) }
}
