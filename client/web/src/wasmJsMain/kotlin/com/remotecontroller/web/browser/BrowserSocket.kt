@file:OptIn(ExperimentalWasmJsInterop::class)

package com.remotecontroller.web.browser

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import org.khronos.webgl.ArrayBuffer
import org.w3c.dom.ARRAYBUFFER
import org.w3c.dom.BinaryType
import org.w3c.dom.CloseEvent
import org.w3c.dom.WebSocket
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsString
import kotlin.js.toInt

private const val BUSY_BYTES = 256 * 1024

sealed interface SocketMessage {
    class Text(val text: String) : SocketMessage

    class Bytes(val bytes: ByteArray) : SocketMessage
}

/** Ends [BrowserSocket.incoming], with the code the socket closed on. */
class SocketClosed(val code: Int) : Exception("The socket closed with code $code")

/** A websocket as a channel of messages. Anything sent while it is not open is dropped. */
class BrowserSocket(url: String) {
    private val socket = WebSocket(url).apply { binaryType = BinaryType.ARRAYBUFFER }
    val incoming: ReceiveChannel<SocketMessage>
        field = Channel<SocketMessage>(Channel.UNLIMITED)

    /** True while the browser still holds a good deal of what was sent. */
    val isBusy: Boolean
        get() = socket.readyState == WebSocket.OPEN && socket.bufferedAmount.toInt() > BUSY_BYTES

    init {
        socket.onclose = { event -> incoming.close(SocketClosed((event as CloseEvent).code.toInt())) }
        socket.onmessage = { event ->
            when (val data = event.data) {
                is JsString -> incoming.trySend(SocketMessage.Text(data.toString()))
                is ArrayBuffer -> incoming.trySend(SocketMessage.Bytes(data.toByteArray()))
                else -> Unit
            }
        }
    }

    fun send(text: String) {
        if (socket.readyState == WebSocket.OPEN) socket.send(text)
    }

    fun sendBytes(bytes: ByteArray) {
        if (socket.readyState == WebSocket.OPEN) socket.send(bytes.toUint8Array())
    }

    fun close() {
        socket.close()
    }
}
