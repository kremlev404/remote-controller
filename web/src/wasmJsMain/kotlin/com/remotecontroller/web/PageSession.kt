package com.remotecontroller.web

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.remotecontroller.shared.model.ClipEvent
import com.remotecontroller.shared.model.ClipNote
import com.remotecontroller.shared.model.ClipRequest
import com.remotecontroller.shared.network.LinkCipher
import com.remotecontroller.shared.network.LinkCrypto
import com.remotecontroller.shared.network.LinkFrame
import com.remotecontroller.shared.network.LinkGrant
import com.remotecontroller.shared.network.RelayProtocol
import com.remotecontroller.shared.network.RemoteJson
import com.remotecontroller.shared.network.RemoteProtocol
import com.remotecontroller.shared.network.ScreenCodec
import com.remotecontroller.shared.network.ScreenFrame
import com.remotecontroller.shared.network.ScreenSignal
import kotlin.io.encoding.Base64
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image

/** Where the page connects: straight to the PC that served it, or to a PC ID through the relay. */
data class PageTarget(
    val relay: Boolean,
    val deviceId: String,
    val code: String,
) {
    fun socketUrl(path: String): String {
        if (!relay) return socketOrigin() + path
        val channel = RelayProtocol.channelName(path) ?: error("no relay channel for $path")
        return socketOrigin() + RelayProtocol.CONNECT_PATH + "/" + deviceId + "/" + channel
    }

    companion object {
        /** `#XXXX-XXXX-XXXX` (old links) or `#id=123456789&code=XXXX-XXXX-XXXX`. */
        fun fromPage(): PageTarget {
            val hash = takePageHash()
            val params = if ('=' in hash) {
                hash.split('&').mapNotNull { part ->
                    val key = part.substringBefore('=', "")
                    if (key.isEmpty()) null else key to part.substringAfter('=')
                }.toMap()
            } else {
                mapOf("code" to hash)
            }
            return PageTarget(
                relay = isRelayPage(),
                deviceId = RelayProtocol.normalizeId(params["id"].orEmpty()),
                code = params["code"].orEmpty(),
            )
        }
    }
}

/** A JPEG the page draws itself, or video the player paints behind the page. */
sealed interface ScreenPicture {
    val width: Int
    val height: Int

    data class Still(val bitmap: ImageBitmap) : ScreenPicture {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height
    }

    data class Moving(
        override val width: Int,
        override val height: Int,
    ) : ScreenPicture
}

sealed interface LinkState {
    data object Connecting : LinkState
    data object Live : LinkState
    data class NeedsCode(val message: String?) : LinkState
    data class Waiting(val checkCode: String) : LinkState
    data class Stopped(val message: String) : LinkState
}

class PageSession(
    private val target: PageTarget,
) {
    val linkState: StateFlow<LinkState>
        field = MutableStateFlow<LinkState>(LinkState.Connecting)

    val notes: StateFlow<List<ClipNote>>
        field = MutableStateFlow(emptyList())

    val clipBanner: StateFlow<String?>
        field = MutableStateFlow(null)

    val clipConnected: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val picture: StateFlow<ScreenPicture?>
        field = MutableStateFlow(null)

    val framesPerSecond: StateFlow<Int>
        field = MutableStateFlow(0)

    val watching: StateFlow<Boolean>
        field = MutableStateFlow(true)

    val hostPaused: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val streamMessage: StateFlow<String>
        field = MutableStateFlow("Connecting to the screen…")

    val keyboardCaptured: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val fullscreen: StateFlow<Boolean>
        field = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clipSocket: BrowserSocket? = null
    private var streamSocket: BrowserSocket? = null
    private var clipCipher: LinkCipher? = null
    private var streamCipher: LinkCipher? = null
    private var player: VideoPlayer? = null
    private var closed = false
    private var halted = false
    private var accessCode = target.code
    private var grant: LinkGrant? = null
    private val clock = TimeSource.Monotonic
    private var windowStart = clock.markNow()
    private var framesInWindow = 0
    private val keyboard = installKeyboardCapture(
        onKey = { code, down -> sendClip(ClipRequest.Key(code, down)) },
        onReleased = { keyboardCaptured.value = false },
    )

    fun start() {
        listenForFullscreen { active ->
            fullscreen.value = active
            if (active) captureKeyboard() else releaseKeyboard()
        }
        listenForImagePaste { dataUrl, type ->
            val encoded = dataUrl.substringAfter("base64,", "")
            if (encoded.isNotEmpty()) send(text = "", image = encoded, imageType = type)
        }
        openClip()
    }

    fun submitCode(code: String) {
        accessCode = code
        retry()
    }

    fun retry() {
        halted = false
        linkState.value = LinkState.Connecting
        openClip()
    }

    fun send(text: String, image: String? = null, imageType: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && image.isNullOrEmpty()) return
        if (trimmed.length > ClipNote.MAX_TEXT) {
            clipBanner.value = "That text is too long to send."
            return
        }
        sendClip(
            ClipRequest.Send(
                text = trimmed,
                image = image,
                imageType = imageType,
            ),
        )
        clipBanner.value = null
    }

    fun clearHistory() {
        sendClip(ClipRequest.Clear)
    }

    fun captureKeyboard() {
        if (clipCipher == null || picture.value == null || !watching.value) return
        keyboardCaptured.value = true
        keyboard.engage()
    }

    fun releaseKeyboard() {
        keyboard.release()
    }

    fun switchFullscreen() {
        toggleFullscreen()
    }

    fun stopStream() {
        releaseKeyboard()
        watching.value = false
        hostPaused.value = false
        framesPerSecond.value = 0
        streamMessage.value = "Screen sharing is stopped."
        streamSocket?.close()
        streamSocket = null
        streamCipher = null
    }

    fun resumeStream() {
        watching.value = true
        openStream()
    }

    /** Where the picture sits on the page, in CSS pixels. */
    fun placeVideo(left: Double, top: Double, width: Double, height: Double) {
        player?.place(left, top, width, height)
    }

    fun pointer(x: Float, y: Float, phase: String, button: String) {
        sendClip(
            ClipRequest.Pointer(
                x = x.coerceIn(0f, 1f),
                y = y.coerceIn(0f, 1f),
                phase = phase,
                button = button,
            ),
        )
    }

    fun type(text: String, backspaces: Int) {
        sendClip(ClipRequest.Type(text, backspaces))
    }

    fun pressEnter() {
        sendClip(ClipRequest.Type("\n"))
    }

    fun scroll(x: Float, y: Float, deltaY: Int) {
        if (deltaY == 0) return
        sendClip(
            ClipRequest.Scroll(
                x = x.coerceIn(0f, 1f),
                y = y.coerceIn(0f, 1f),
                deltaY = deltaY.coerceIn(-20, 20),
            ),
        )
    }

    private fun sendClip(request: ClipRequest) {
        val cipher = clipCipher ?: return
        val socket = clipSocket ?: return
        val json = RemoteJson.encodeRequest(request)
        scope.launch {
            cipher.seal(LinkFrame.pack(LinkFrame.TEXT, json.encodeToByteArray())) { box ->
                socket.sendBytes(Base64.encode(box))
            }
        }
    }

    fun close() {
        releaseKeyboard()
        closed = true
        watching.value = false
        clipSocket?.close()
        streamSocket?.close()
        player?.close()
        player = null
        clipSocket = null
        streamSocket = null
        clipCipher = null
        streamCipher = null
    }

    private fun openClip() {
        if (closed || halted || clipSocket != null) return
        clipSocket = openSecure(
            url = target.socketUrl(RemoteProtocol.CLIP_WS_PATH),
            onLive = { bytes ->
                scope.launch {
                    val cipher = clipCipher ?: return@launch
                    // A frame that does not open was replayed or altered: drop the link.
                    val plain = runCatching { cipher.open(bytes) }.getOrNull()
                    if (plain == null) {
                        clipSocket?.close()
                        return@launch
                    }
                    val (kind, body) = LinkFrame.unpack(plain)
                    if (kind == LinkFrame.TEXT) onClipText(body.decodeToString())
                }
            },
            onOpen = {},
            onClose = { code ->
                releaseKeyboard()
                clipSocket = null
                clipCipher = null
                clipConnected.value = false
                if (code == RelayProtocol.CLOSE_OFFLINE.toInt()) {
                    clipBanner.value = "This PC is not online right now."
                }
                if (!closed && !halted) {
                    scope.launch {
                        delay(if (code == RelayProtocol.CLOSE_OFFLINE.toInt()) 5_000 else 1_500)
                        openClip()
                    }
                }
            },
            onReady = { cipher ->
                clipCipher = cipher
                clipConnected.value = true
                clipBanner.value = null
                linkState.value = LinkState.Live
                sendClip(ClipRequest.Present)
                // The stream waits for the clipboard link, so a PC that asks its owner asks once.
                openStream()
            },
        )
    }

    private fun onClipText(text: String) {
        val event = runCatching { RemoteJson.decode<ClipEvent>(text) }.getOrNull() ?: return
        when (event) {
            is ClipEvent.History -> {
                notes.value = event.messages
                clipBanner.value = null
            }
            is ClipEvent.Note -> {
                notes.value = notes.value + event.message
                clipBanner.value = null
            }
            is ClipEvent.Cleared -> notes.value = emptyList()
            is ClipEvent.Rejected -> clipBanner.value = event.message
        }
    }

    private fun openStream() {
        if (closed || halted || clipCipher == null || !watching.value || streamSocket != null) return
        hostPaused.value = false
        streamMessage.value = "Connecting to the screen…"
        streamSocket = openSecure(
            url = target.socketUrl(RemoteProtocol.STREAM_PATH),
            onLive = { bytes ->
                scope.launch {
                    val cipher = streamCipher ?: return@launch
                    val plain = runCatching { cipher.open(bytes) }.getOrNull()
                    if (plain == null) {
                        streamSocket?.close()
                        return@launch
                    }
                    val (kind, body) = LinkFrame.unpack(plain)
                    when (kind) {
                        LinkFrame.TEXT -> onStreamText(body.decodeToString())
                        LinkFrame.BYTES -> onStreamFrame(body)
                    }
                }
            },
            onOpen = {},
            onClose = {
                streamSocket = null
                streamCipher = null
                framesPerSecond.value = 0
                player?.reset()
                if (watching.value && !closed && !halted) {
                    streamMessage.value = "Reconnecting…"
                    scope.launch {
                        delay(1_000)
                        openStream()
                    }
                }
            },
            onReady = { cipher ->
                streamCipher = cipher
                val codecs = if (canPlayVideo()) listOf(ScreenCodec.H264) else emptyList()
                sendStream(ScreenSignal.viewer(codecs))
            },
        )
    }

    private fun sendStream(text: String) {
        val cipher = streamCipher ?: return
        val socket = streamSocket ?: return
        scope.launch {
            cipher.seal(LinkFrame.pack(LinkFrame.TEXT, text.encodeToByteArray())) { box ->
                socket.sendBytes(Base64.encode(box))
            }
        }
    }

    private fun onStreamText(text: String) {
        when {
            ScreenSignal.isPaused(text) -> {
                hostPaused.value = true
                framesPerSecond.value = 0
                streamMessage.value = "Paused on the computer"
            }
            ScreenSignal.isLive(text) -> {
                hostPaused.value = false
                if (picture.value == null) streamMessage.value = "Connecting to the screen…"
            }
        }
    }

    private fun onStreamFrame(packet: ByteArray) {
        val video = ScreenFrame.unpackVideo(packet)
        if (video != null) {
            videoPlayer().decode(Base64.encode(video.data), video.keyframe)
            return
        }
        val still = ScreenFrame.unpack(packet) ?: return
        val bitmap = runCatching {
            Image.makeFromEncoded(still.jpeg).toComposeImageBitmap()
        }.getOrNull() ?: return
        picture.value = ScreenPicture.Still(bitmap)
        noteShownFrame()
    }

    private fun videoPlayer(): VideoPlayer = player ?: openVideoPlayer(
        onShown = { width, height ->
            val moving = ScreenPicture.Moving(width, height)
            if (picture.value != moving) picture.value = moving
            noteShownFrame()
        },
        onLost = { sendStream(ScreenSignal.KEYFRAME) },
    ).also { player = it }

    private fun noteShownFrame() {
        hostPaused.value = false
        framesInWindow++
        if (windowStart.elapsedNow().inWholeMilliseconds >= 1_000) {
            framesPerSecond.value = framesInWindow
            framesInWindow = 0
            windowStart = clock.markNow()
        }
    }

    private fun openSecure(
        url: String,
        onLive: (ByteArray) -> Unit,
        onOpen: () -> Unit,
        onClose: (Int) -> Unit,
        onReady: (LinkCipher) -> Unit,
    ): BrowserSocket {
        val hello = CompletableDeferred<String>()
        // The sealed `ready`; empty for "unknown grant"; null when the socket closed first.
        val reply = CompletableDeferred<ByteArray?>()
        val phase = intArrayOf(0)
        val socket = openBrowserSocket(
            url = url,
            onText = { text ->
                when {
                    phase[0] == 0 && !hello.isCompleted -> hello.complete(text)
                    phase[0] == 1 && text == LinkCrypto.UNKNOWN_GRANT -> reply.complete(ByteArray(0))
                }
            },
            onFrame = { encoded ->
                val bytes = runCatching { Base64.decode(encoded) }.getOrNull() ?: return@openBrowserSocket
                when (phase[0]) {
                    1 -> reply.complete(bytes)
                    2 -> onLive(bytes)
                }
            },
            onOpen = onOpen,
            onClose = { code ->
                if (!hello.isCompleted) hello.completeExceptionally(IllegalStateException("closed"))
                reply.complete(null)
                onClose(code)
            },
        )
        scope.launch {
            try {
                val offer = LinkCrypto.offer(hello.await())
                val pc = offer.hello.pc.ifBlank { target.deviceId }
                val usedGrant = grant ?: storedGrant(pc)
                if (usedGrant == null && offer.needsCode && !LinkCrypto.isCode(accessCode)) {
                    halt(LinkState.NeedsCode(null))
                    socket.close()
                    return@launch
                }
                val (join, cipher) = LinkCrypto.join(offer, accessCode, usedGrant, browserLabel())
                phase[0] = 1
                socket.send(join)
                cipher.seal(LinkCrypto.CONFIRM.encodeToByteArray()) { box -> socket.sendBytes(Base64.encode(box)) }
                val waiting = !offer.needsCode && usedGrant == null
                if (waiting) linkState.value = LinkState.Waiting(cipher.checkCode)
                val box = reply.await()
                if (box != null && box.isEmpty()) {
                    forgetGrant(pc)
                    socket.close()
                    return@launch
                }
                val ready = box?.let { runCatching { LinkCrypto.openReady(cipher, it) }.getOrNull() }
                if (ready == null) {
                    when {
                        // A socket that dropped says nothing about the grant: only an answer from the PC does.
                        usedGrant != null -> if (box != null) forgetGrant(pc)
                        offer.needsCode -> halt(LinkState.NeedsCode("The access code does not match this PC."))
                        waiting -> halt(LinkState.Stopped("The PC did not allow this connection."))
                    }
                    socket.close()
                    return@launch
                }
                val grantId = ready.grantId
                val grantKey = ready.grantKey
                if (grantId != null && grantKey != null) keepGrant(pc, LinkGrant(grantId, grantKey))
                onReady(cipher)
                phase[0] = 2
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (error is com.remotecontroller.shared.network.LinkRejected) halt(LinkState.Stopped(error.message.orEmpty()))
                socket.close()
            }
        }
        return socket
    }

    private fun storedGrant(pc: String): LinkGrant? =
        readStored(grantKey(pc))?.let { runCatching { RemoteJson.decode<LinkGrant>(it) }.getOrNull() }

    private fun keepGrant(pc: String, kept: LinkGrant) {
        grant = kept
        writeStored(grantKey(pc), RemoteJson.encode(kept))
    }

    private fun forgetGrant(pc: String) {
        grant = null
        removeStored(grantKey(pc))
    }

    private fun grantKey(pc: String): String = "remote-controller.grant.$pc"

    private fun halt(state: LinkState) {
        halted = true
        linkState.value = state
    }
}
